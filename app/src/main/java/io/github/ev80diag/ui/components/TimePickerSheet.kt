package io.github.ev80diag.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ev80diag.R
import io.github.ev80diag.battery.TimeWindow
import io.github.ev80diag.data.store.SessionEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A quick range's label: "15 min", "1 h", "7 d" (translated units). */
@Composable
fun quickLabel(ms: Long): String = when {
    ms < 3_600_000L -> stringResource(R.string.battery_quick_min, ms / 60_000)
    ms < 86_400_000L -> stringResource(R.string.battery_quick_h, ms / 3_600_000)
    else -> stringResource(R.string.battery_quick_d, ms / 86_400_000L)
}

/** One recorded session as a picker row: date, type, charger or km. */
@Composable
fun SessionPickRow(s: SessionEntity, onClick: () -> Unit) {
    val typeRes = when (s.type) {
        "CHARGE_DC" -> R.string.session_charge_dc; "CHARGE_AC" -> R.string.session_charge_ac
        "CHARGE_ATTEMPT" -> R.string.session_charge_attempt; else -> R.string.session_drive
    }
    val date = remember(s.startMs) { SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(s.startMs)) }
    val failed = s.type == "CHARGE_ATTEMPT" || s.outcome == "NO_CHARGE"
    val extra = s.chargerName ?: s.distanceKm?.let { stringResource(R.string.battery_session_km, "%.1f".format(it)) }
    Text((if (failed) "✕ " else "") + listOfNotNull(date, stringResource(typeRes), extra).joinToString(" · "),
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp))
}

private enum class Step { NONE, DATES, FROM_TIME, TO_TIME }

/** Time picker (spec §5.5): quick ranges, recorded sessions, since last full charge, from → to, earlier/later/zoom out. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TimePickerSheet(
    sessions: List<SessionEntity>,
    onQuick: (Long) -> Unit, onSession: (SessionEntity) -> Unit, onSinceFull: () -> Unit, onRange: (Long, Long) -> Unit,
    onEarlier: () -> Unit, onLater: () -> Unit, onZoomOut: () -> Unit, onDismiss: () -> Unit,
) {
    var step by remember { mutableStateOf(Step.NONE) }
    var fromDay by remember { mutableStateOf(0L) }
    var toDay by remember { mutableStateOf(0L) }
    var fromMinutes by remember { mutableStateOf(0) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.battery_quick), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TimeWindow.QUICK.forEach { q -> AssistChip(onClick = { onQuick(q); onDismiss() }, label = { Text(quickLabel(q)) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton({ onEarlier() }) { Text(stringResource(R.string.battery_earlier)) }
                OutlinedButton({ onZoomOut() }) { Text(stringResource(R.string.battery_zoom_out)) }
                OutlinedButton({ onLater() }) { Text(stringResource(R.string.battery_later)) }
            }
            TextButton({ onSinceFull(); onDismiss() }) { Text(stringResource(R.string.battery_since_full)) }
            TextButton({ step = Step.DATES }) { Text(stringResource(R.string.battery_absolute)) }
            Text(stringResource(R.string.battery_sessions), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            if (sessions.isEmpty()) Text(stringResource(R.string.battery_no_sessions), fontSize = 13.sp)
            sessions.take(30).forEach { s -> SessionPickRow(s) { onSession(s); onDismiss() } }
        }
    }

    when (step) {
        Step.DATES -> {
            val state = rememberDateRangePickerState()
            DatePickerDialog(onDismissRequest = { step = Step.NONE },
                confirmButton = {
                    TextButton(enabled = state.selectedStartDateMillis != null, onClick = {
                        fromDay = state.selectedStartDateMillis!!; toDay = state.selectedEndDateMillis ?: fromDay; step = Step.FROM_TIME
                    }) { Text(stringResource(android.R.string.ok)) }
                },
                dismissButton = { TextButton({ step = Step.NONE }) { Text(stringResource(android.R.string.cancel)) } }) {
                DateRangePicker(state, Modifier.weight(1f))
            }
        }
        Step.FROM_TIME, Step.TO_TIME -> {
            val from = step == Step.FROM_TIME
            val state = rememberTimePickerState(if (from) 0 else 23, if (from) 0 else 59, is24Hour = true)
            AlertDialog(onDismissRequest = { step = Step.NONE },
                title = { Text(stringResource(if (from) R.string.battery_from_time else R.string.battery_to_time)) },
                text = { TimePicker(state) },
                confirmButton = {
                    TextButton({
                        val minutes = state.hour * 60 + state.minute
                        if (from) { fromMinutes = minutes; step = Step.TO_TIME }
                        else { onRange(localTime(fromDay, fromMinutes), localTime(toDay, minutes)); step = Step.NONE; onDismiss() }
                    }) { Text(stringResource(android.R.string.ok)) }
                },
                dismissButton = { TextButton({ step = Step.NONE }) { Text(stringResource(android.R.string.cancel)) } })
        }
        Step.NONE -> {}
    }
}

/** The date pickers give UTC midnight; turn that day plus a local clock time into epoch ms. */
private fun localTime(utcDayMs: Long, minutes: Int): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcDayMs }
    return Calendar.getInstance().apply {
        clear(); set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), minutes / 60, minutes % 60)
    }.timeInMillis
}
