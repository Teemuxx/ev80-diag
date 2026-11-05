package io.github.ev80diag.ui.screen

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ev80diag.DebugLog
import io.github.ev80diag.R
import io.github.ev80diag.export.SessionExporter
import io.github.ev80diag.pipeline.LinkStatus
import io.github.ev80diag.service.Recorder
import io.github.ev80diag.ui.theme.StatusCritical
import kotlinx.coroutines.launch

/** Add ([vanId] 0) or edit a van: name, VIN, capacity, notes, recognition values, data size, delete. */
@Composable
fun VanEditScreen(vanId: Long, onDone: () -> Unit, modifier: Modifier = Modifier, vm: VanEditViewModel = viewModel()) {
    LaunchedEffect(vanId) { vm.load(vanId) }
    val form by vm.form.collectAsState()
    val f = form ?: return
    val vanCount by vm.vanCount.collectAsState()
    val state by Recorder.state.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(f.van.id) { mutableStateOf(f.van.name) }
    var vin by rememberSaveable(f.van.id) { mutableStateOf(f.van.vin.orEmpty()) }
    var notes by rememberSaveable(f.van.id) { mutableStateOf(f.van.notes.orEmpty()) }
    var capacity by rememberSaveable(f.van.id) { mutableStateOf("%.0f".format(f.capacityAh)) }
    var askDelete by remember { mutableStateOf(0) }       // 0 none, 1 first question, 2 confirm
    var exporting by remember { mutableStateOf(false) }
    val isNew = f.van.id == 0L
    val capacityAh = capacity.trim().replace(',', '.').toFloatOrNull()?.takeIf { it in 50f..300f }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(if (isNew) R.string.van_new_title else R.string.van_edit_title), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.van_field_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(vin, { vin = it }, label = { Text(stringResource(R.string.van_field_vin)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(capacity, { capacity = it }, label = { Text(stringResource(R.string.van_field_capacity)) }, singleLine = true,
            isError = capacityAh == null, supportingText = { Text(stringResource(R.string.van_field_capacity_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(notes, { notes = it }, label = { Text(stringResource(R.string.van_field_notes)) }, minLines = 3, modifier = Modifier.fillMaxWidth())
        if (!isNew) {
            Text(stringResource(R.string.van_recognition,
                f.van.lastOdoKm?.let { "%,.0f km".format(it) } ?: "–", f.van.lastChargeKwh?.let { "%,.0f kWh".format(it) } ?: "–"),
                style = MaterialTheme.typography.bodySmall)
            Text(pluralStringResource(R.plurals.van_data_size, f.sessions, f.sessions, Formatter.formatShortFileSize(ctx, f.bytes)), style = MaterialTheme.typography.bodySmall)
        }
        Button({ vm.save(name, vin, notes, capacityAh, onDone) }, Modifier.fillMaxWidth(), enabled = name.isNotBlank() && capacityAh != null) {
            Text(stringResource(R.string.btn_save))
        }
        if (!isNew) {
            val recording = state.link != LinkStatus.DISCONNECTED && state.link != LinkStatus.REFUSED
            OutlinedButton({ askDelete = 1 }, Modifier.fillMaxWidth(), enabled = vanCount > 1 && !recording) {
                Text(stringResource(R.string.van_delete), color = if (vanCount > 1 && !recording) StatusCritical else MaterialTheme.colorScheme.outline)
            }
            if (vanCount <= 1) Text(stringResource(R.string.van_delete_only_one), style = MaterialTheme.typography.bodySmall)
            else if (recording) Text(stringResource(R.string.van_delete_disconnect_first), style = MaterialTheme.typography.bodySmall)
        }
    }

    if (askDelete == 1) AlertDialog(
        onDismissRequest = { askDelete = 0 },
        text = { Text(stringResource(R.string.van_delete_q1, f.van.name)) },
        confirmButton = { TextButton({ askDelete = 2 }) { Text(stringResource(R.string.van_delete_continue)) } },
        dismissButton = {
            TextButton({
                exporting = true
                scope.launch {
                    val zip = runCatching { vm.exportAll() }.onFailure { DebugLog.log("export before delete failed: $it") }.getOrNull()
                    exporting = false
                    zip?.let { SessionExporter.share(ctx, it, R.string.share_title) }
                }
            }, enabled = !exporting) { Text(stringResource(if (exporting) R.string.export_all_busy else R.string.van_delete_export_first, f.van.name)) }
        },
    )
    if (askDelete == 2) AlertDialog(
        onDismissRequest = { askDelete = 0 },
        text = { Text(stringResource(R.string.van_delete_q2, f.van.name)) },
        confirmButton = { TextButton({ askDelete = 0; vm.delete(onDone) }) { Text(stringResource(R.string.van_delete_confirm), color = StatusCritical) } },
        dismissButton = { TextButton({ askDelete = 0 }) { Text(stringResource(android.R.string.cancel)) } },
    )
}
