package io.github.ev80diag.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.ev80diag.DebugLog
import io.github.ev80diag.R
import io.github.ev80diag.data.store.Ev80Database
import io.github.ev80diag.data.store.RoomSessionRepository
import io.github.ev80diag.pipeline.LinkStatus
import io.github.ev80diag.pipeline.Pipeline
import io.github.ev80diag.pipeline.SocFeed
import io.github.ev80diag.profile.ActiveProfile
import io.github.ev80diag.pipeline.SourceResult
import io.github.ev80diag.session.PowerState
import io.github.ev80diag.session.SessionType
import io.github.ev80diag.settings.AppSettings
import io.github.ev80diag.settings.LastSource
import io.github.ev80diag.transport.CanSource
import io.github.ev80diag.transport.GsUsbProtocol
import io.github.ev80diag.transport.GsUsbSource
import io.github.ev80diag.transport.ReplaySource
import io.github.ev80diag.transport.StnBluetoothSource
import io.github.ev80diag.ui.MainActivity
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the [Pipeline]: connects the chosen source (Bluetooth STN, USB gs_usb or a
 * replayed log), reconnects per [ReconnectPolicy] and keeps recording with the screen off.
 */
class RecorderService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var pipeline: Pipeline? = null
    private lateinit var settings: AppSettings
    private var lastSessionType: SessionType? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifText: String? = null
    private var lastNotifLink: LinkStatus? = null
    private var lastNotifAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {     // these two come by startService, never need the foreground promotion
            Recorder.ACTION_STOP -> { stopRecording(); stopSelf(); return START_NOT_STICKY }
            Recorder.ACTION_MARK -> { pipeline?.mark(intent.getStringExtra(Recorder.EXTRA_TEXT).orEmpty()); return START_STICKY }
            Recorder.ACTION_CHOOSE_PROFILE -> {
                val id = intent.getLongExtra(Recorder.EXTRA_PROFILE_ID, -1L)
                if (id > 0) pipeline?.chooseProfile(id) ?: ActiveProfile.set(this, id)
                return START_STICKY
            }
        }
        if (!startInForeground()) { stopSelf(); return START_NOT_STICKY }
        when (intent?.action) {
            Recorder.ACTION_CONNECT_BT -> {
                settings.lastSource = LastSource.BLUETOOTH
                settings.lastBluetoothAddress = intent.getStringExtra(Recorder.EXTRA_ADDRESS)
                start { sourceFor(LastSource.BLUETOOTH, null) }
            }
            Recorder.ACTION_CONNECT_USB -> {
                settings.lastSource = LastSource.USB
                val name = intent.getStringExtra(Recorder.EXTRA_USB_NAME)
                start { sourceFor(LastSource.USB, name) }
            }
            Recorder.ACTION_REPLAY -> {
                val uri = Uri.parse(intent.getStringExtra(Recorder.EXTRA_URI))
                val speed = intent.getDoubleExtra(Recorder.EXTRA_SPEED, 10.0)
                start(replay = true) { ReplaySource({ contentResolver.openInputStream(uri)!! }, speed) }
            }
            else -> {                                       // resume at app start, after boot, or after being killed
                if (job?.isActive == true) return START_STICKY
                when (settings.lastSource) {
                    LastSource.NONE -> { stopSelf(); return START_NOT_STICKY }
                    LastSource.BLUETOOTH -> start { sourceFor(LastSource.BLUETOOTH, null) }
                    LastSource.USB -> start { sourceFor(LastSource.USB, null) }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRecording()
        scope.cancel()
        super.onDestroy()
    }

    // ---- connection ---------------------------------------------------------------------------

    private fun start(replay: Boolean = false, source: () -> CanSource?) {
        val previous = job
        acquireWakeLock()
        job = scope.launch {
            previous?.cancelAndJoin()       // the old pipeline ends its session and releases the adapter first
            val db = Ev80Database.get(this@RecorderService)
            val repo = RoomSessionRepository(db)
            val profile = ActiveProfile.current(this@RecorderService)
            repo.closeOrphans().let { if (it > 0) DebugLog.log("closed $it session(s) cut off by an app kill") }
            cleanupOldDriveLogs(db)
            val p = Pipeline(File(filesDir, "sessions"), { settings.fullCanLogging }, repo, profile.id,
                if (replay) SocFeed.Fresh() else SocFeed.Tracker, replayMode = replay, recognize = !replay)
            pipeline = p
            // recognition or the owner picked another van: the screens follow
            val vanWatch = launch { if (!replay) p.profileId.collect { id -> ActiveProfile.set(this@RecorderService, id) } }
            var attempt = 0
            val runJob = launch {
                p.run { last ->
                    if (replay) return@run if (last == SourceResult.NONE) source() else null
                    if (p.lastRunConnected) attempt = 0
                    when (val d = ReconnectPolicy.decide(last, attempt)) {
                        ReconnectPolicy.Decision.Stop -> null
                        is ReconnectPolicy.Decision.Connect -> {
                            if (last != SourceResult.NONE) attempt++
                            delay(d.delayMs)
                            source()
                        }
                    }
                }
            }
            val watch = launch { p.state.collect { onState(it) } }
            val sleepWatch = launch {
                // "only while the app is open" also ends when the van sleeps (spec §11), even with the link up
                var offSince: Long? = null
                while (!replay) {
                    delay(30_000)
                    if (p.state.value.van.power != PowerState.OFF) { offSince = null; continue }
                    val since = offSince ?: SystemClock.elapsedRealtime().also { offSince = it }
                    if (ReconnectPolicy.stopBecauseVanAsleep(settings.recordingMode, SystemClock.elapsedRealtime() - since)) {
                        DebugLog.log("van off for 10 min, recording stops (mode: only while app open)")
                        runJob.cancel()
                        break
                    }
                }
            }
            runJob.join()
            watch.cancel(); sleepWatch.cancel(); vanWatch.cancel()
            onState(p.state.value)          // the final state (DISCONNECTED or REFUSED)
            stopSelf()
        }
    }

    @SuppressLint("MissingPermission")
    private fun sourceFor(kind: LastSource, usbName: String?): CanSource? = when (kind) {
        LastSource.BLUETOOTH -> {
            val address = settings.lastBluetoothAddress
            val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
            val granted = Build.VERSION.SDK_INT < 31 ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (address == null || adapter == null || !granted) { DebugLog.log("no Bluetooth address/permission"); null }
            else StnBluetoothSource(adapter.getRemoteDevice(address))
        }
        LastSource.USB -> {
            // by VID/PID: a re-plugged adapter gets a new device name
            val usb = getSystemService(USB_SERVICE) as UsbManager
            val supported = usb.deviceList.values.filter { GsUsbProtocol.isSupported(it.vendorId, it.productId) }
            val dev = supported.firstOrNull { it.deviceName == usbName } ?: supported.firstOrNull()
            if (dev == null || !usb.hasPermission(dev)) { DebugLog.log("no USB adapter/permission"); null } else GsUsbSource(usb, dev)
        }
        LastSource.NONE -> null
    }

    private fun stopRecording() {
        job?.cancel(); job = null
        pipeline = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        val last = Recorder.state.value
        // a refusal stays on screen until the next connect, so the owner sees why nothing happened
        val link = if (last.link == LinkStatus.REFUSED) LinkStatus.REFUSED else LinkStatus.DISCONNECTED
        Recorder.publish(last.copy(link = link, session = null, charge = null))
    }

    /** Without it the CPU sleeps with the screen off: ticks, flushes and Bluetooth reads would stall. */
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val wl = wakeLock ?: (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ev80diag:recorder").apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        if (!wl.isHeld) wl.acquire()
    }

    private fun releaseWakeLock() { wakeLock?.takeIf { it.isHeld }?.release() }

    private suspend fun cleanupOldDriveLogs(db: Ev80Database) {
        val cutoff = System.currentTimeMillis() - settings.driveRawRetentionDays * 86_400_000L
        for (s in db.sessions().driveRawBefore(cutoff)) {
            s.rawLogPath?.let { File(it).delete() }
            s.marksPath?.let { File(it).delete() }
            db.sessions().setRawPaths(s.id, null, null)
        }
    }

    // ---- notifications ------------------------------------------------------------------------

    private fun onState(s: io.github.ev80diag.pipeline.RecorderState) {
        Recorder.publish(s)
        val nm = getSystemService(NotificationManager::class.java)
        // state arrives several times a second; the system drops notification updates beyond ~5/s per app
        val text = notificationText(s)
        val now = SystemClock.elapsedRealtime()
        if (s.link != lastNotifLink || (text != lastNotifText && now - lastNotifAt >= NOTIF_EVERY_MS)) {
            nm.notify(NOTIF_ID, buildNotification(text))
            lastNotifText = text; lastNotifLink = s.link; lastNotifAt = now
        }
        val type = s.session?.type
        val isCharge = type == SessionType.CHARGE_AC || type == SessionType.CHARGE_DC
        val wasCharge = lastSessionType == SessionType.CHARGE_AC || lastSessionType == SessionType.CHARGE_DC
        if (isCharge && !wasCharge && !Recorder.appInForeground && settings.openChargingOnStart) {
            nm.notify(EVENT_NOTIF_ID, NotificationCompat.Builder(this, CH_EVENTS)
                .setSmallIcon(R.mipmap.ic_launcher).setContentTitle(getString(R.string.notif_charge_started))
                .setContentIntent(openApp()).setAutoCancel(true).build())
        }
        lastSessionType = type
    }

    private fun notificationText(s: io.github.ev80diag.pipeline.RecorderState): String = when (s.link) {
        LinkStatus.CONNECTING -> getString(R.string.notif_connecting)
        LinkStatus.LOST -> getString(R.string.notif_lost)
        LinkStatus.REFUSED -> getString(R.string.notif_refused)
        else -> s.charge?.let { getString(R.string.notif_charging, it.powerKw, it.chargedKwh) }
            ?: getString(R.string.notif_recording, "%.0f".format(s.framesPerSec))
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CH_RECORDING).setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name)).setContentText(text).setOngoing(true)
            .setContentIntent(openApp()).build()

    private fun openApp(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("open", "charging"),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /**
     * False when Android refuses: a sticky restart from the background (API 31+), or no Bluetooth/USB permission
     * yet for a connectedDevice service (API 34+).
     */
    private fun startInForeground(): Boolean = try {
        val n = buildNotification(getString(R.string.notif_connecting))
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTIF_ID, n)
        true
    } catch (e: Exception) {
        DebugLog.log("foreground start refused: $e")
        false
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return          // channels exist from Android 8
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_RECORDING, getString(R.string.channel_recording), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_EVENTS, getString(R.string.channel_events), NotificationManager.IMPORTANCE_DEFAULT))
    }

    private companion object {
        const val CH_RECORDING = "recording"
        const val CH_EVENTS = "events"
        const val NOTIF_ID = 1
        const val EVENT_NOTIF_ID = 2
        const val NOTIF_EVERY_MS = 5_000L
    }
}
