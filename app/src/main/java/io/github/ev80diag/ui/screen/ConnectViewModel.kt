package io.github.ev80diag.ui.screen

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ev80diag.R
import io.github.ev80diag.data.store.Ev80Database
import io.github.ev80diag.data.store.ProfileEntity
import io.github.ev80diag.data.store.RoomSessionRepository
import io.github.ev80diag.settings.AppSettings
import io.github.ev80diag.transport.GsUsbProtocol
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BtDevice(val name: String, val address: String)

class ConnectViewModel(app: Application) : AndroidViewModel(app) {
    private val db = Ev80Database.get(app)
    val settings = AppSettings(app)

    val profile: StateFlow<ProfileEntity?> = db.profiles().all().map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch { RoomSessionRepository(db).ensureDefaultProfile(app.getString(R.string.default_van_name)) }
    }

    fun rename(name: String) {
        val p = profile.value ?: return
        if (name.isNotBlank() && name != p.name) viewModelScope.launch { db.profiles().update(p.copy(name = name.trim())) }
    }

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
