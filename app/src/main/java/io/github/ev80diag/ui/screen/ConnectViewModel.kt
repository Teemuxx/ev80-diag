package io.github.ev80diag.ui.screen

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ev80diag.data.store.Ev80Database
import io.github.ev80diag.data.store.ProfileEntity
import io.github.ev80diag.profile.ActiveProfile
import io.github.ev80diag.service.Recorder
import io.github.ev80diag.settings.AppSettings
import io.github.ev80diag.transport.GsUsbProtocol
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

data class BtDevice(val name: String, val address: String)

class ConnectViewModel(app: Application) : AndroidViewModel(app) {
    private val db = Ev80Database.get(app)
    val settings = AppSettings(app)

    val vans: StateFlow<List<ProfileEntity>> = db.profiles().all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val active: StateFlow<ProfileEntity?> = ActiveProfile.flow(app)

    /** Tapping a van card: while recording, this connection's data moves to that van too. */
    fun select(id: Long) { if (id != active.value?.id) Recorder.chooseProfile(getApplication(), id) }

    /** Paired Bluetooth devices, OBDLink adapters first. Needs BLUETOOTH_CONNECT on Android 12+. */
    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<BtDevice> = runCatching {
        val adapter = (getApplication<Application>().getSystemService(android.content.Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        adapter?.bondedDevices.orEmpty().map { BtDevice(it.name ?: it.address, it.address) }
            .sortedWith(compareByDescending<BtDevice> { it.name.contains("OBDLink", ignoreCase = true) }.thenBy { it.name })
    }.getOrDefault(emptyList())

    /** gs_usb / candleLight adapters currently plugged in (vendor/product IDs of usb_device_filter.xml). */
    fun usbAdapters(): List<UsbDevice> {
        val usb = getApplication<Application>().getSystemService(android.content.Context.USB_SERVICE) as UsbManager
        return usb.deviceList.values.filter { GsUsbProtocol.isSupported(it.vendorId, it.productId) }
    }
}
