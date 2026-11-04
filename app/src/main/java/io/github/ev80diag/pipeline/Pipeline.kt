package io.github.ev80diag.pipeline

import io.github.ev80diag.DebugLog
import io.github.ev80diag.calc.DriveMetrics
import io.github.ev80diag.calc.LoadChangeTracker
import io.github.ev80diag.can.CanFrame
import io.github.ev80diag.charging.ChargeMetrics
import io.github.ev80diag.data.store.CellBlob
import io.github.ev80diag.data.store.CellSnapshotEntity
import io.github.ev80diag.data.store.SampleEntity
import io.github.ev80diag.data.store.SessionRepository
import io.github.ev80diag.recording.PreRollBuffer
import io.github.ev80diag.recording.RawLogWriter
import io.github.ev80diag.session.PowerState
import io.github.ev80diag.session.SessionDetector
import io.github.ev80diag.session.SessionEvent
import io.github.ev80diag.session.SessionType
import io.github.ev80diag.session.VanStateTracker
import io.github.ev80diag.settings.FullCanLogging
import io.github.ev80diag.signals.Decoder
import io.github.ev80diag.transport.AdapterRefusedException
import io.github.ev80diag.transport.CanSource
import io.github.ev80diag.transport.SourceEvent
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlin.coroutines.coroutineContext

/**
 * source thread --> FrameBus --+--> raw consumer    (all frames -> gzip candump, with pre-roll)
 *                              +--> decode consumer (signals, van state, sessions, samples, UI state)
 * The source never waits for a consumer; each consumer has its own buffer and drop counter.
 * One Pipeline outlives reconnects, so a Bluetooth hiccup doesn't split a charge session.
 *
 * [replayMode] is for replaying a log file: the bus blocks the (much faster than real time) source instead of dropping.
 */
class Pipeline(
    private val sessionsDir: File,
    private val fullCanLogging: () -> FullCanLogging,
    private val repo: SessionRepository,
    private val profileId: Long,
    private val soc: SocFeed = SocFeed.None,
    private val clock: () -> Long = System::currentTimeMillis,
    private val replayMode: Boolean = false,
) {
    private val _state = MutableStateFlow(RecorderState())
    val state: StateFlow<RecorderState> = _state

    @Volatile private var link = LinkStatus.DISCONNECTED
    @Volatile private var adapterName: String? = null
    @Volatile private var adapterKind = "UNKNOWN"
    @Volatile private var fps = 0.0
    /** Whether the most recent source run delivered data (the caller resets its back-off then). */
    @Volatile var lastRunConnected = false
        private set
    private val errorLines = AtomicInteger()
    private val sessionStartedAtMs = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private sealed interface RawCmd {
        data class Start(val baseName: String, val fromMs: Long) : RawCmd
        /** Close the file once the raw consumer has read every frame up to [afterTs] (it may lag the decoder). */
        data class Stop(val afterTs: Long) : RawCmd
        data object Flush : RawCmd
        data class Mark(val tsMs: Long, val text: String) : RawCmd
    }

    private val cmds = Channel<RawCmd>(Channel.UNLIMITED)

    /** Adds a timestamped note to the open session's marks file. */
    fun mark(text: String) { cmds.trySend(RawCmd.Mark(clock(), text)) }

    /**
     * Runs until [nextSource] returns null. It is called first with [SourceResult.NONE], then after each source
     * run with how that run ended; the caller decides about retries and back-off.
     */
    suspend fun run(nextSource: suspend (SourceResult) -> CanSource?) {
        val bus = FrameBus(blocking = replayMode)
        val cap = if (replayMode) REPLAY_BUFFER else LIVE_BUFFER
        val rawSub = bus.subscribe("raw", cap)
        val decSub = bus.subscribe("decode", cap)
        val ticks = Channel<Long>(Channel.CONFLATED)
        // The consumers live in their own scope: cancelling run() (the user taps Disconnect) must not cancel them
        // before they have ended the open session and closed the raw log.
        val consumers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rawJob = consumers.launch(Dispatchers.IO) { rawConsumer(rawSub) }
        val decJob = consumers.launch { decodeConsumer(decSub, rawSub, ticks) }
        val evJob = consumers.launch { bus.events.collect { onSourceEvent(it) } }
        val tickJob = consumers.launch { while (isActive) { delay(1_000); ticks.trySend(clock()); cmds.trySend(RawCmd.Flush) } }
        try {
            var last = SourceResult.NONE
            while (true) {
                val src = nextSource(last) ?: break
                adapterName = src.info.name
                adapterKind = src.info.kind.name
                link = LinkStatus.CONNECTING
                lastRunConnected = false
                publishLink()
                last = try {
                    src.run(bus)
                    SourceResult.ENDED
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AdapterRefusedException) {
                    DebugLog.log("adapter refused: ${e.message}")
                    link = LinkStatus.REFUSED
                    publishLink()
                    SourceResult.REFUSED
                } catch (e: IOException) {
                    DebugLog.log("source failed: ${e.message}")
                    link = LinkStatus.LOST
                    publishLink()
                    SourceResult.FAILED
                }
            }
        } finally {
            withContext(NonCancellable) {
                bus.close()
                decJob.join()           // the decode consumer closes the open session when its input ends
                cmds.close()
                rawJob.join()
                tickJob.cancel(); evJob.cancel(); consumers.cancel()
                if (link != LinkStatus.REFUSED) link = LinkStatus.DISCONNECTED
                publishFinal()
            }
        }
    }

    private fun onSourceEvent(e: SourceEvent) {
        when (e) {
            is SourceEvent.ErrorLine -> errorLines.incrementAndGet()
            is SourceEvent.Stats -> { fps = e.framesPerSec; link = LinkStatus.CONNECTED; lastRunConnected = true }
        }
    }

    // ---- raw consumer -------------------------------------------------------------------------

    private suspend fun rawConsumer(sub: Subscription) {
        val preRoll = PreRollBuffer()
        var writer: RawLogWriter? = null
        var framesOpen = true
        var cmdsOpen = true
        var lastTs = 0L
        var pendingStop: Long? = null
        fun closeIfDue() {
            val p = pendingStop ?: return
            if (lastTs >= p || !framesOpen) { writer?.close(); writer = null; pendingStop = null }
        }
        try {
            while (framesOpen || cmdsOpen) {
                select<Unit> {
                    if (framesOpen) sub.frames.onReceiveCatching { r ->
                        val f = r.getOrNull()
                        if (f == null) framesOpen = false else { lastTs = f.timestamp; preRoll.add(f); writer?.write(f) }
                        closeIfDue()
                    }
                    if (cmdsOpen) cmds.onReceiveCatching { r ->
                        when (val c = r.getOrNull()) {
                            null -> cmdsOpen = false
                            is RawCmd.Start -> {
                                writer?.close()
                                pendingStop = null
                                writer = RawLogWriter(sessionsDir, c.baseName).also { w ->
                                    preRoll.drain().filter { it.timestamp >= c.fromMs }.forEach(w::write)
                                }
                            }
                            is RawCmd.Stop -> { pendingStop = c.afterTs; closeIfDue() }
                            RawCmd.Flush -> if (clock() - lastTs >= SILENCE_FLUSH_MS) writer?.flush()
                            is RawCmd.Mark -> writer?.mark(c.tsMs, c.text)
                        }
                    }
                }
            }
        } finally {
            writer?.close()
        }
    }

    // ---- decode consumer ----------------------------------------------------------------------

    private suspend fun decodeConsumer(sub: Subscription, raw: Subscription, ticks: Channel<Long>) {
        val decoder = Decoder()
        val tracker = VanStateTracker()
        val detector = SessionDetector()
        val latest = HashMap<String, Double>()
        var frames = 0L
        var lastTs = 0L
        var lastPublish = 0L
        var lastSample = 0L

        var openId: Long? = null
        var openType = SessionType.DRIVE
        var openStart = 0L
        var metrics: ChargeMetrics? = null
        var baseName: String? = null
        var framesAtStart = 0L
        var droppedAtStart = 0L
        var drive: DriveMetrics? = null
        var lastDriveSave = 0L
        val load = LoadChangeTracker()
        var power = PowerState.OFF
        var storedCells: Any? = null
        var lastCellsTs = 0L

        suspend fun apply(events: List<SessionEvent>) {
            for (e in events) when (e) {
                is SessionEvent.Started -> {
                    val id = repo.startSession(profileId, e.type, e.startMs, adapterKind, adapterName)
                    openId = id; openType = e.type; openStart = e.startMs
                    framesAtStart = frames; droppedAtStart = sub.dropped.get() + raw.dropped.get()
                    metrics = if (e.type != SessionType.DRIVE) ChargeMetrics() else null
                    drive = if (e.type == SessionType.DRIVE) DriveMetrics() else null
                    lastDriveSave = e.startMs
                    baseName = null
                    if (e.type != SessionType.DRIVE || fullCanLogging() == FullCanLogging.ALWAYS) {
                        val name = "ev80_${sessionStartedAtMs.format(Date(e.startMs))}_${e.type.name.lowercase(Locale.US)}_$id"
                        baseName = name
                        cmds.send(RawCmd.Start(name, e.preRollFromMs))
                        // stored now, so the log stays reachable if the app is killed before the session ends
                        repo.setRawPaths(id, File(sessionsDir, "$name.log.gz").path, File(sessionsDir, "$name.marks").path)
                    }
                    DebugLog.log("session $id started: ${e.type}")
                }
                is SessionEvent.TypeChanged -> {
                    openId?.let { repo.updateType(it, e.type) }
                    openType = e.type
                }
                is SessionEvent.Ended -> {
                    val id = openId ?: continue
                    val name = baseName
                    // by frame time, not wall time: after a tick-based end the raw file must not wait for the next frame
                    if (name != null) cmds.send(RawCmd.Stop(lastTs))
                    repo.endSession(
                        id, e.endMs, e.outcome, metrics?.chargedKwh,
                        framesTotal = frames - framesAtStart,
                        droppedFrames = sub.dropped.get() + raw.dropped.get() - droppedAtStart,
                    )
                    drive?.snapshot()?.let { d -> repo.setDriveTotals(id, d.km, d.kwh) }
                    DebugLog.log("session $id ended: ${e.outcome}")
                    openId = null; metrics = null; baseName = null; drive = null
                }
            }
        }

        fun publish(now: Long, force: Boolean = false) {
            if (!force && now - lastPublish < PUBLISH_EVERY_MS) return
            lastPublish = now
            val m = metrics
            _state.value = RecorderState(
                link = link, adapterName = adapterName, framesPerSec = fps,
                van = tracker.onTick(now), decoded = FreshValues.filter(latest, decoder.lastSeen, lastTs),
                cells = decoder.latestCells?.voltages, temps = decoder.latestTemps,
                session = openId?.let { SessionInfo(it, openType, openStart) },
                charge = if (m != null && m.powerKw > 0) ChargeSnapshot(m.powerKw, m.chargedKwh, m.elapsedMs, m.timeToFullMin()) else null,
                droppedByConsumer = mapOf("decode" to sub.dropped.get(), "raw" to raw.dropped.get()),
                errorLines = errorLines.get(), framesTotal = frames, socAppPercent = soc.socPercent(),
                raw = HashMap(decoder.lastRaw),
                drive = drive?.snapshot(), msSinceLoadChange = load.msSinceChange(now),
            )
        }

        suspend fun onTick(now: Long) {
            // While the adapter link is down, silence says nothing about the van: hold the session timers, so a
            // walk out of Bluetooth range doesn't end a charge. After the grace time the van is treated as gone.
            val linkDown = link != LinkStatus.CONNECTED && now - lastTs < LINK_LOSS_GRACE_MS
            if (!linkDown) apply(detector.onState(tracker.onTick(now), now))
            publish(now, force = true)
        }

        var open = true
        while (open) {
            select<Unit> {
                sub.frames.onReceiveCatching { r ->
                    val f = r.getOrNull()
                    if (f == null) { open = false; return@onReceiveCatching }
                    frames++; lastTs = f.timestamp
                    val decoded = decoder.decode(f)
                    if (decoded.isNotEmpty()) latest.putAll(decoded)
                    feedDerived(f, decoded, latest, metrics)
                    feedDrive(f, decoded, latest, drive, load)
                    val state = tracker.onFrame(f, decoded)
                    power = state.power
                    apply(detector.onState(state, f.timestamp))
                    val cells = decoder.latestCells
                    if (cells != null && cells !== storedCells && power != PowerState.OFF) {
                        val charging = power in CHARGING_STATES
                        if (f.timestamp - lastCellsTs >= if (charging) CELLS_CHARGING_MS else CELLS_OTHER_MS) {
                            storedCells = cells; lastCellsTs = f.timestamp
                            repo.addCellSnapshot(CellSnapshotEntity(profileId, f.timestamp, CellBlob.pack(cells.voltages), charging))
                        }
                    }
                    val d = drive
                    val dId = openId
                    if (d != null && dId != null && f.timestamp - lastDriveSave >= DRIVE_SAVE_EVERY_MS) {
                        lastDriveSave = f.timestamp
                        d.snapshot().let { repo.setDriveTotals(dId, it.km, it.kwh) }
                    }
                    val id = openId
                    if (id != null && f.timestamp - lastSample >= SAMPLE_EVERY_MS) {
                        lastSample = f.timestamp
                        repo.addSample(sampleOf(id, f.timestamp, latest))
                    }
                    publish(f.timestamp)
                }
                ticks.onReceive { now -> if (lastTs != 0L && !replayMode) onTick(now) }
            }
        }
        apply(detector.finish(lastTs))
        publish(lastTs, force = true)
    }

    private fun feedDerived(f: CanFrame, decoded: Map<String, Double>, latest: Map<String, Double>, metrics: ChargeMetrics?) {
        if (f.id == 0x550) {
            val a = decoded["pack_a"] ?: return
            soc.onCurrent(f.timestamp, a.toFloat())
            val v = latest["pack_v"] ?: return
            metrics?.onSample(f.timestamp, v, a, latest["energy_kwh"], latest["soc_bms"])
        } else if (f.id == 0x530) {
            val mx = decoded["cell_max_mv"] ?: return
            val mn = decoded["cell_min_mv"] ?: return
            soc.onCellExtremes(f.timestamp, mx.toInt(), mn.toInt(), (latest["pack_a"] ?: 0.0).toFloat())
        }
    }

    private fun feedDrive(f: CanFrame, decoded: Map<String, Double>, latest: Map<String, Double>, drive: DriveMetrics?, load: LoadChangeTracker) {
        if (f.id == 0x550) {
            val a = decoded["pack_a"] ?: return
            load.onCurrent(f.timestamp, a)
            val v = latest["pack_v"] ?: return
            drive?.onPack(f.timestamp, v, a)
        } else if (f.id == 0x380) {
            decoded["odo_km"]?.let { drive?.onOdometer(f.timestamp, it) }
        }
    }

    private fun sampleOf(sessionId: Long, ts: Long, v: Map<String, Double>) = SampleEntity(
        sessionId = sessionId, tsMs = ts, packV = v["pack_v"], packA = v["pack_a"], socBms = v["soc_bms"],
        socApp = soc.socPercent()?.toDouble(), insulationKOhm = v["insulation_kohm"], tempAvg = v["temp_avg"],
        tempMin = v["temp_min"], tempMax = v["temp_max"], cellMinMv = v["cell_min_mv"], cellMaxMv = v["cell_max_mv"],
        energyKwh = v["energy_kwh"],
    )

    /** Link changes show at once, not only with the next frame or tick (there are none while connecting). */
    private fun publishLink() {
        _state.value = _state.value.copy(link = link, adapterName = adapterName)
    }

    private fun publishFinal() {
        _state.value = _state.value.copy(link = link, session = null, charge = null, framesPerSec = 0.0)
    }

    private companion object {
        const val LIVE_BUFFER = 65_536
        const val REPLAY_BUFFER = 4_096
        /** A tick flushes the raw log only when frames have stopped; while they flow, members end every few seconds by themselves. */
        const val SILENCE_FLUSH_MS = 5_000L
        const val PUBLISH_EVERY_MS = 250L
        const val SAMPLE_EVERY_MS = 1_000L
        const val LINK_LOSS_GRACE_MS = 5 * 60_000L
        /** Cell snapshots: one per complete set, at most every 2 s while charging, every 10 s otherwise (spec §7). */
        const val CELLS_CHARGING_MS = 2_000L
        const val CELLS_OTHER_MS = 10_000L
        val CHARGING_STATES = setOf(PowerState.CHARGING_AC, PowerState.CHARGING_DC, PowerState.CHARGE_COMPLETE)
        const val DRIVE_SAVE_EVERY_MS = 60_000L
    }
}
