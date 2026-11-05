package io.github.ev80diag.ui.screen

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import io.github.ev80diag.data.store.ProfileEntity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ev80diag.R
import io.github.ev80diag.pipeline.LinkStatus
import io.github.ev80diag.service.Recorder
import io.github.ev80diag.session.PowerState
import io.github.ev80diag.settings.LastSource
import io.github.ev80diag.ui.components.labelRes
import io.github.ev80diag.ui.theme.StatusCritical
import io.github.ev80diag.ui.theme.StatusGood

private const val ACTION_USB_PERMISSION = "io.github.ev80diag.USB_PERMISSION"

@Composable
fun ConnectScreen(modifier: Modifier = Modifier, onEditVan: (Long) -> Unit = {}, vm: ConnectViewModel = viewModel()) {
    val ctx = LocalContext.current
    val state by Recorder.state.collectAsState()
    val vans by vm.vans.collectAsState()
    val activeVan by vm.active.collectAsState()
    var useUsb by remember { mutableStateOf(vm.settings.lastSource == LastSource.USB) }
    var address by remember { mutableStateOf(vm.settings.lastBluetoothAddress) }
    var paired by remember { mutableStateOf(vm.pairedDevices()) }
    var usbAdapters by remember { mutableStateOf(vm.usbAdapters()) }
    var permissionHint by remember { mutableStateOf(false) }
    val connected = state.link == LinkStatus.CONNECTING || state.link == LinkStatus.CONNECTED || state.link == LinkStatus.LOST

    fun connectNow() {
        if (useUsb) usbAdapters.firstOrNull()?.let { Recorder.connectUsb(ctx, it.deviceName) }
        else address?.let { Recorder.connectBluetooth(ctx, it) }
    }

    fun proceedConnect() {
        if (useUsb) {
            val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
            val dev = usbAdapters.firstOrNull() ?: return
            if (!usb.hasPermission(dev)) {
                val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ACTION_USB_PERMISSION).setPackage(ctx.packageName), PendingIntent.FLAG_MUTABLE)
                usb.requestPermission(dev, pi); return
            }
        }
        connectNow()
    }

    // Android 12+: without BLUETOOTH_CONNECT even the list of paired devices is hidden, so ask for it up front.
    fun btGranted() = Build.VERSION.SDK_INT < 31 ||
        ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
    var btAllowed by remember { mutableStateOf(btGranted()) }
    var connectAfterGrant by remember { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        btAllowed = btGranted()
        paired = vm.pairedDevices()
        // notifications are optional: only a missing Bluetooth permission blocks connecting
        permissionHint = !useUsb && !btAllowed
        if (connectAfterGrant && !permissionHint) proceedConnect()
        connectAfterGrant = false
    }
    fun requestBluetooth() {
        connectAfterGrant = false
        if (Build.VERSION.SDK_INT >= 31) permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
    }
    LaunchedEffect(Unit) { if (!useUsb && !btAllowed) requestBluetooth() }
    val replayPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) Recorder.replay(ctx, uri)
    }

    // USB permission result and plug/unplug
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                usbAdapters = vm.usbAdapters()
                if (i.action == ACTION_USB_PERMISSION && i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connectNow()
            }
        }
        val f = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION); addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(ctx, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { ctx.unregisterReceiver(receiver) }
    }
    LaunchedEffect(state.link) { paired = vm.pairedDevices() }

    var notificationsAsked by remember { mutableStateOf(false) }
    fun onConnect() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= 31 && !useUsb) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33 && !notificationsAsked) add(Manifest.permission.POST_NOTIFICATIONS)   // asked once, optional
        }.filter { ctx.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            notificationsAsked = true; connectAfterGrant = true
            permissions.launch(needed.toTypedArray()); return
        }
        proceedConnect()
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.vans_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        // while connected, another van takes this connection's data along: ask first (a look at its history is not meant)
        var confirmVan by remember { mutableStateOf<ProfileEntity?>(null) }
        vans.forEach { v ->
            VanCard(v, selected = v.id == activeVan?.id, onEdit = { onEditVan(v.id) },
                onSelect = { if (v.id != activeVan?.id) { if (connected) confirmVan = v else vm.select(v.id) } })
        }
        confirmVan?.let { v ->
            AlertDialog(onDismissRequest = { confirmVan = null },
                text = { Text(stringResource(R.string.van_switch_confirm, v.name)) },
                confirmButton = { TextButton({ vm.select(v.id); confirmVan = null }) { Text(stringResource(R.string.btn_ok)) } },
                dismissButton = { TextButton({ confirmVan = null }) { Text(stringResource(android.R.string.cancel)) } })
        }
        TextButton({ onEditVan(0L) }) { Text(stringResource(R.string.van_add)) }

        Text(stringResource(R.string.connect_adapter), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

        AdapterCard(selected = !useUsb, enabled = !connected, onSelect = { useUsb = false },
            title = stringResource(R.string.adapter_obdlink), subtitle = stringResource(R.string.adapter_full_logging)) {
            if (!btAllowed) {
                Text(stringResource(R.string.bt_permission_needed), style = MaterialTheme.typography.bodySmall)
                OutlinedButton({ requestBluetooth() }) { Text(stringResource(R.string.btn_allow_bluetooth)) }
            }
            else if (paired.isEmpty()) Text(stringResource(R.string.adapter_no_paired), style = MaterialTheme.typography.bodySmall)
            else paired.forEach { d ->
                Row(Modifier.fillMaxWidth().selectable(address == d.address, enabled = !connected, role = Role.RadioButton) { address = d.address; useUsb = false },
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(address == d.address, onClick = null, modifier = Modifier.padding(end = 8.dp))
                    Text(d.name)
                }
            }
        }
        AdapterCard(selected = useUsb, enabled = !connected, onSelect = { useUsb = true; usbAdapters = vm.usbAdapters() },
            title = stringResource(R.string.adapter_usb), subtitle = stringResource(R.string.adapter_full_logging)) {
            Text(usbAdapters.firstOrNull()?.let { stringResource(R.string.adapter_usb_found, it.productName ?: it.deviceName) }
                ?: stringResource(R.string.adapter_usb_none), style = MaterialTheme.typography.bodySmall)
        }

        if (connected) OutlinedButton({ Recorder.stop(ctx) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.btn_disconnect)) }
        else Button({ onConnect() }, Modifier.fillMaxWidth(), enabled = if (useUsb) usbAdapters.isNotEmpty() else address != null) {
            Text(stringResource(R.string.btn_connect))
        }
        if (permissionHint) Text(stringResource(R.string.permission_needed), color = StatusCritical)
        if (state.link == LinkStatus.REFUSED) Text(stringResource(R.string.adapter_not_stn), color = StatusCritical)

        if (state.link != LinkStatus.DISCONNECTED) {
            Step(state.link == LinkStatus.CONNECTED, stringResource(R.string.step_adapter))
            Step(state.framesPerSec > 0, stringResource(R.string.step_frames, "%.0f".format(state.framesPerSec)))
            Step(state.link == LinkStatus.CONNECTED && state.van.power != PowerState.OFF,
                stringResource(R.string.step_van, stringResource(state.van.power.labelRes())))
        }

        if ((ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            OutlinedButton({ replayPicker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.replay_button)) }
        }
    }
}

@Composable
private fun VanCard(van: ProfileEntity, selected: Boolean, onSelect: () -> Unit, onEdit: () -> Unit) {
    Card(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onSelect),
        border = BorderStroke(if (selected) 2.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(van.name, style = MaterialTheme.typography.titleMedium)
                Text(vanSubtitle(van), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onEdit) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.van_edit_title)) }
        }
    }
}

/** "49,551 km · last seen 05.11. 14:20", or "not seen yet". */
@Composable
fun vanSubtitle(van: ProfileEntity): String {
    val seen = van.lastSeenMs ?: return stringResource(R.string.van_not_seen)
    val time = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(seen))
    val odo = van.lastOdoKm?.let { "%,.0f km".format(it) }
    return if (odo == null) stringResource(R.string.van_last_seen, time) else stringResource(R.string.van_odo_last_seen, odo, time)
}

@Composable
private fun AdapterCard(selected: Boolean, enabled: Boolean, onSelect: () -> Unit, title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
        border = BorderStroke(if (selected) 2.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun Step(done: Boolean, text: String) =
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, contentDescription = null,
            tint = if (done) StatusGood else MaterialTheme.colorScheme.outline, modifier = Modifier.padding(end = 8.dp))
        Text(text)
    }
