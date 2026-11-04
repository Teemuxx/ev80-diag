package io.github.ev80diag.ui.screen

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ev80diag.DebugLog
import io.github.ev80diag.R
import io.github.ev80diag.data.store.SessionEntity
import io.github.ev80diag.export.ExportFormat
import io.github.ev80diag.export.SessionExporter
import io.github.ev80diag.service.Recorder
import io.github.ev80diag.ui.theme.StatusCritical
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shares a session in the given format; returns false if there was nothing to export. */
suspend fun shareSession(ctx: android.content.Context, sessionId: Long, format: ExportFormat): Boolean {
    val f = SessionExporter.build(ctx, sessionId, format) ?: return false
    SessionExporter.share(ctx, f, R.string.share_title)
    return true
}

@Composable
fun ExportDebugScreen(modifier: Modifier = Modifier, vm: ExportDebugViewModel = viewModel()) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessions by vm.sessions.collectAsState()
    val state by Recorder.state.collectAsState()
    val storage by vm.storageBytes.collectAsState()
    var format by remember { mutableStateOf(ExportFormat.BUNDLE) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val profileName by vm.profileName.collectAsState()
    var logLines by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(state.framesTotal / 2000) { logLines = withContext(Dispatchers.IO) { DebugLog.tail(200) } }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Heading(R.string.export_sessions)
        if (sessions.isEmpty()) Text(stringResource(R.string.export_none))
        sessions.take(50).forEach { s ->
            SessionRow(s) { scope.launch { failed = !shareSession(ctx, s.id, format) } }
        }
        // everything recorded for this van in one zip: every session's bundle, a session list and all cell snapshots
        if (sessions.isNotEmpty()) OutlinedButton({
            busy = true
            scope.launch {
                val f = SessionExporter.buildAll(ctx)
                busy = false
                if (f != null) SessionExporter.share(ctx, f, R.string.share_title) else failed = true
            }
        }, Modifier.fillMaxWidth(), enabled = !busy) {
            Text(stringResource(if (busy) R.string.export_all_busy else R.string.export_all, profileName ?: ""))
        }
        if (failed) Text(stringResource(R.string.export_failed), color = StatusCritical)

        Heading(R.string.export_format)
        Column(Modifier.selectableGroup()) {
            listOf(ExportFormat.BUNDLE to R.string.fmt_bundle, ExportFormat.CSV to R.string.fmt_csv, ExportFormat.CANDUMP to R.string.fmt_candump).forEach { (f, res) ->
                Row(Modifier.fillMaxWidth().selectable(format == f, role = Role.RadioButton) { format = f }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(format == f, onClick = null, modifier = Modifier.padding(end = 8.dp)); Text(stringResource(res), fontSize = 14.sp)
                }
            }
        }

        Heading(R.string.export_health)
        Text(stringResource(R.string.health_line, state.adapterName ?: "–", "%.0f".format(state.framesPerSec),
            state.droppedByConsumer.values.sum(), state.errorLines), fontSize = 13.sp)
        Text(stringResource(R.string.export_storage, Formatter.formatFileSize(ctx, storage)), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Heading(R.string.debug_log)
        Text(logLines.takeLast(30).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton({
            val dir = File(ctx.filesDir, "logs")
            SessionExporter.shareDebugLogs(ctx, listOf(File(dir, "debug.log"), File(dir, "debug.log.1")), R.string.btn_send_debug)
        }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.btn_send_debug)) }
    }
}

@Composable
private fun SessionRow(s: SessionEntity, onShare: () -> Unit) {
    val failed = s.outcome == "STOPPED_BY_BMS" || s.outcome == "NO_CHARGE"
    val typeRes = when (s.type) {
        "CHARGE_DC" -> R.string.session_charge_dc; "CHARGE_AC" -> R.string.session_charge_ac
        "CHARGE_ATTEMPT" -> R.string.session_charge_attempt; else -> R.string.session_drive
    }
    val outcomeRes = outcomeLabel(s.outcome)
    val date = remember(s.startMs) { SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(s.startMs)) }
    val minutes = ((s.endMs ?: System.currentTimeMillis()) - s.startMs) / 60_000
    val size = s.rawLogPath?.let { File(it).takeIf(File::exists)?.length() }
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Icon(when { failed -> Icons.Filled.Error; s.type == "DRIVE" -> Icons.Filled.DirectionsCar; else -> Icons.Filled.BatteryChargingFull }, null,
                tint = if (failed) StatusCritical else MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
            Column {
                Text("$date · ${stringResource(typeRes)}" + (s.chargerName?.let { " · $it" } ?: ""), fontSize = 14.sp)
                Text(listOfNotNull(outcomeRes?.let { stringResource(it) }, "$minutes min", s.chargedKwh?.let { "%.1f kWh".format(it) },
                    size?.let { Formatter.formatShortFileSize(ctx, it) }).joinToString(" · "), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        OutlinedButton(onShare) { Text(stringResource(R.string.btn_share)) }
    }
}

@Composable
private fun Heading(res: Int) = Text(stringResource(res), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))

/** How a session ended, as shown to the user; null while it is still running. */
internal fun outcomeLabel(outcome: String?): Int? = when (outcome) {
    "COMPLETE" -> R.string.outcome_complete; "STOPPED_BY_BMS" -> R.string.outcome_stopped
    "NO_CHARGE" -> R.string.outcome_no_charge; "ENDED" -> R.string.outcome_ended
    "INTERRUPTED" -> R.string.outcome_interrupted; else -> null
}
