package io.github.ev80diag.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.ev80diag.settings.AppSettings
import io.github.ev80diag.settings.LastSource
import io.github.ev80diag.settings.RecordingMode

/** Starts the recorder after boot, only in automatic mode and only if an adapter was used before. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val s = AppSettings(context)
        if (s.recordingMode == RecordingMode.AUTOMATIC && s.lastSource != LastSource.NONE) {
            ContextCompat.startForegroundService(context, Intent(context, RecorderService::class.java))
        }
    }
}
