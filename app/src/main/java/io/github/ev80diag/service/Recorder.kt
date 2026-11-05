package io.github.ev80diag.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import io.github.ev80diag.pipeline.LinkStatus
import io.github.ev80diag.pipeline.RecorderState
import io.github.ev80diag.profile.ActiveProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Process-wide handle between the UI and [RecorderService]. */
object Recorder {
    private val _state = MutableStateFlow(RecorderState())
    val state: StateFlow<RecorderState> = _state
    internal fun publish(s: RecorderState) { _state.value = s }

    /** Set by MainActivity; the service posts a notification instead of opening a screen while the app is hidden. */
    @Volatile var appInForeground = false

    const val ACTION_CONNECT_BT = "io.github.ev80diag.CONNECT_BT"
    const val ACTION_CONNECT_USB = "io.github.ev80diag.CONNECT_USB"
    const val ACTION_REPLAY = "io.github.ev80diag.REPLAY"
    const val ACTION_STOP = "io.github.ev80diag.STOP"
    const val ACTION_MARK = "io.github.ev80diag.MARK"
    const val ACTION_CHOOSE_PROFILE = "io.github.ev80diag.CHOOSE_PROFILE"
    const val EXTRA_PROFILE_ID = "profile_id"
    /** Reconnect to the last adapter (automatic mode at app start); ignored while recording. */
    const val ACTION_RESUME = "io.github.ev80diag.RESUME"
    const val EXTRA_ADDRESS = "address"
    const val EXTRA_USB_NAME = "usb_name"
    const val EXTRA_URI = "uri"
    const val EXTRA_SPEED = "speed"
    const val EXTRA_TEXT = "text"

    private fun send(ctx: Context, action: String, fill: Intent.() -> Unit = {}) =
        ContextCompat.startForegroundService(ctx, Intent(ctx, RecorderService::class.java).setAction(action).apply(fill))

    fun connectBluetooth(ctx: Context, address: String) = send(ctx, ACTION_CONNECT_BT) { putExtra(EXTRA_ADDRESS, address) }
    fun connectUsb(ctx: Context, deviceName: String) = send(ctx, ACTION_CONNECT_USB) { putExtra(EXTRA_USB_NAME, deviceName) }
    fun replay(ctx: Context, uri: Uri, speed: Double = 10.0) = send(ctx, ACTION_REPLAY) { putExtra(EXTRA_URI, uri.toString()); putExtra(EXTRA_SPEED, speed) }
    fun resume(ctx: Context) = send(ctx, ACTION_RESUME)
    fun stop(ctx: Context) = ctx.startService(Intent(ctx, RecorderService::class.java).setAction(ACTION_STOP))
    /** Another van: while recording, the pipeline moves this connection's data to it; otherwise it just becomes active. */
    fun chooseProfile(ctx: Context, id: Long) {
        if (state.value.link == LinkStatus.DISCONNECTED || state.value.link == LinkStatus.REFUSED) ActiveProfile.set(ctx, id)
        else ctx.startService(Intent(ctx, RecorderService::class.java).setAction(ACTION_CHOOSE_PROFILE).putExtra(EXTRA_PROFILE_ID, id))
    }
    fun mark(ctx: Context, text: String) = ctx.startService(Intent(ctx, RecorderService::class.java).setAction(ACTION_MARK).putExtra(EXTRA_TEXT, text))
}
