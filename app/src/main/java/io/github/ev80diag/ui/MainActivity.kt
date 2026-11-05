package io.github.ev80diag.ui

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.ev80diag.profile.ActiveProfile
import io.github.ev80diag.pipeline.LinkStatus
import io.github.ev80diag.service.Recorder
import androidx.core.content.IntentCompat
import io.github.ev80diag.settings.AppSettings
import io.github.ev80diag.settings.LastSource
import io.github.ev80diag.settings.RecordingMode
import io.github.ev80diag.ui.navigation.AppNavGraph
import io.github.ev80diag.ui.navigation.NavRequests
import io.github.ev80diag.ui.navigation.Screen
import io.github.ev80diag.ui.theme.BatteryAnalyserTheme

class MainActivity : AppCompatActivity() {
    private lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        enableEdgeToEdge()
        handleIntent(intent)
        // automatic mode records from app start too (spec §11), not only after boot or a USB plug-in
        if (savedInstanceState == null && settings.recordingMode == RecordingMode.AUTOMATIC &&
            settings.lastSource != LastSource.NONE && Recorder.state.value.link == LinkStatus.DISCONNECTED) Recorder.resume(this)
        setContent {
            BatteryAnalyserTheme(settings.theme) {
                val state by Recorder.state.collectAsState()
                LaunchedEffect(state.link) {
                    // keep the screen on only while an adapter is connected (setting)
                    if (settings.keepScreenOn && state.link == LinkStatus.CONNECTED) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                val van by ActiveProfile.flow(this@MainActivity).collectAsState()
                AppNavGraph(vanName = van?.name ?: getString(io.github.ev80diag.R.string.default_van_name))
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() { super.onStart(); Recorder.appInForeground = true }
    override fun onStop() { Recorder.appInForeground = false; super.onStop() }

    private fun handleIntent(i: Intent?) {
        if (i?.getStringExtra("open") == "charging") NavRequests.route.value = Screen.Charging.route
        if (i?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED && settings.recordingMode == RecordingMode.AUTOMATIC) {
            // the attach intent filter grants USB permission for this device
            IntentCompat.getParcelableExtra(i, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                ?.let { Recorder.connectUsb(this, it.deviceName) }
        }
    }
}
