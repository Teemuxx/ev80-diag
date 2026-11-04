package io.github.ev80diag.ui.screen

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ev80diag.R
import io.github.ev80diag.data.store.SampleEntity
import io.github.ev80diag.ui.charts.ChartZone
import io.github.ev80diag.ui.charts.TimeSeriesChart
import io.github.ev80diag.ui.components.StatusChip
import io.github.ev80diag.ui.components.TemperaturePanel
import io.github.ev80diag.ui.theme.Blue
import io.github.ev80diag.ui.theme.StatusCritical
import io.github.ev80diag.ui.theme.StatusGood
import io.github.ev80diag.ui.theme.StatusWarning
import io.github.ev80diag.warnings.Level
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Aqua = Color(0xFF1BAF7A)

@Composable
fun ChargingScreen(modifier: Modifier = Modifier, vm: ChargingViewModel = viewModel(), onShare: (Long) -> Unit = {}) {
    val screen by vm.screen.collectAsState()
    val s = screen
    if (s == null) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.charge_none)) }
        return
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val stats: @Composable () -> Unit = { Stats(s, vm, onShare) }
    val charts: @Composable () -> Unit = { Charts(s) }
    if (landscape) {
        Row(modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { stats() }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { charts() }
        }
    } else {
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            stats(); charts()
        }
    }
}

@Composable
private fun Stats(s: ChargingScreenState, vm: ChargingViewModel, onShare: (Long) -> Unit) {
    val ui = s.ui
    var showInsulation by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s.waiting) Text(stringResource(R.string.charge_waiting), color = MaterialTheme.colorScheme.onSurfaceVariant)
        // not live: say which charge these numbers belong to, so old values are not mistaken for the current ones
        if (!s.active) {
            val at = remember(s.session.startMs) { SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(s.session.startMs)) }
            Text(stringResource(R.string.charge_past, at), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Column {
                Label(R.string.charge_power)
                Text(ui.powerKw?.let { "%.1f kW".format(it) } ?: "–", fontSize = 44.sp, style = MaterialTheme.typography.displaySmall)
                val sub = listOfNotNull(ui.packV?.let { "%.0f V".format(it) }, ui.packA?.let { "%.0f A".format(kotlin.math.abs(it)) },
                    if (ui.tapering) stringResource(R.string.charge_tapering) else null).joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                // the app's own SOC when it is current, else the BMS value, labelled as such
                val app = ui.socAppPercent
                Label(if (app != null) R.string.charge_soc_app else R.string.charge_soc_bms_label)
                Text((app ?: ui.socBmsPercent)?.let { "%.0f %%".format(it) } ?: "–", fontSize = 24.sp, style = MaterialTheme.typography.titleLarge)
                if (app != null) ui.socBmsPercent?.let { Text(stringResource(R.string.charge_soc_bms, "%.1f".format(it)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tile(R.string.charge_charged, ui.chargedKwh?.let { "%.1f kWh".format(it) } ?: "–", Modifier.weight(1f))
            Tile(R.string.charge_time, formatDuration(ui.elapsedMs), Modifier.weight(1f))
            if (!ui.finished) Tile(R.string.charge_to_full,
                ui.timeToFullMin?.let { stringResource(R.string.charge_to_full_value, "%.0f".format(it)) } ?: "–", Modifier.weight(1f), R.string.charge_estimate)
            // an ended charge shows how it ended: "Finished" would hide a stop by the battery
            else Tile(R.string.charge_result, stringResource(outcomeLabel(s.session.outcome) ?: R.string.charge_finished)
                .replaceFirstChar { it.uppercase() }, Modifier.weight(1f))
        }
        TemperaturePanel(ui)
        Row(Modifier.fillMaxWidth().clickable { showInsulation = true }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.charge_insulation) + "  " + (ui.insulationKOhm?.let { formatKOhm(it) } ?: stringResource(R.string.ins_none)))
            if (ui.insulationKOhm != null) StatusChip(ui.insulationLevel, okRes = R.string.ins_good, warnRes = R.string.ins_low, critRes = R.string.ins_critical)
        }
        if (ui.cellMinMv != null && ui.cellMaxMv != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.charge_cells, "%.2f".format(ui.cellMinMv / 1000.0), "%.2f".format(ui.cellMaxMv / 1000.0), ui.spreadMv ?: 0), fontSize = 12.sp)
                if (ui.spreadLevel == Level.OK && (ui.spreadMv ?: 0) >= 80) Text(stringResource(R.string.spread_normal_full), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else if (ui.spreadLevel != Level.OK) StatusChip(ui.spreadLevel, warnRes = R.string.ins_low)
            }
        }
        // saved with the button (or the keyboard's Done), so it is clear when the name is stored
        var charger by remember(s.session.id) { mutableStateOf(s.session.chargerName.orEmpty()) }
        val saved = charger.trim() == s.session.chargerName.orEmpty()
        val focus = LocalFocusManager.current
        fun save() { vm.setChargerName(s.session.id, charger); focus.clearFocus() }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(charger, { charger = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.charge_charger)) },
                placeholder = { Text(stringResource(R.string.charge_charger_hint)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { save() }))
            if (saved && charger.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = StatusGood, modifier = Modifier.padding(end = 4.dp))
                Text(stringResource(R.string.charger_saved), color = StatusGood)
            }
            else Button(onClick = { save() }, enabled = !saved) { Text(stringResource(R.string.btn_save)) }
        }
        OutlinedButton({ onShare(s.session.id) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.btn_share)) }
    }
    if (showInsulation) AlertDialog(onDismissRequest = { showInsulation = false },
        title = { Text(stringResource(R.string.sig_insulation_kohm)) },
        text = { Text(stringResource(R.string.sig_insulation_kohm_expl)) },
        confirmButton = { TextButton({ showInsulation = false }) { Text(stringResource(R.string.btn_ok)) } })
}

@Composable
private fun Charts(s: ChargingScreenState) {
    val samples = s.samples
    val xMin = s.session.startMs
    val xMax = maxOf(xMin + 60_000, s.session.endMs ?: samples.lastOrNull()?.tsMs ?: xMin)
    val power = remember(samples) { samples.mapNotNull { p -> if (p.packV != null && p.packA != null) p.tsMs to maxOf(0.0, -p.packV * p.packA / 1000) else null } }
    val insulation = remember(samples) { samples.mapNotNull { p -> p.insulationKOhm?.takeIf { it < 10_000 }?.let { p.tsMs to it } } }
    val lowKOhm = s.insulationLowKOhm
    // 35 kW covers the EV80 on every DC charger seen so far, 10 kW its on-board AC charger (6.6 kW);
    // the axis grows if a session goes above it
    val floorKw = if (s.session.type == "CHARGE_AC") 10.0 else 35.0
    val powerMax = remember(power, floorKw) { maxOf(floorKw, kotlin.math.ceil((power.maxOfOrNull { it.second } ?: 0.0) / 10) * 10) }
    val powerTicks = remember(powerMax) { (0..powerMax.toInt() step if (powerMax <= 10) 2 else 10).map { it.toDouble() to "$it" } }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TimeSeriesChart(stringResource(R.string.chart_power), power, xMin, xMax, 0.0, powerMax,
            powerTicks, Blue, fill = true, showNow = s.active)
        TimeSeriesChart(stringResource(R.string.chart_insulation), insulation, xMin, xMax, 10.0, 10_000.0,
            listOf(10.0 to "10 k", 100.0 to "100 k", 1_000.0 to "1 M", 10_000.0 to "10 M"), Aqua, log = true, height = 130.dp, showNow = s.active,
            zones = listOf(
                ChartZone(10.0, 36.0, StatusCritical.copy(alpha = 0.22f), stringResource(R.string.zone_critical)),
                ChartZone(36.0, lowKOhm.toDouble(), StatusWarning.copy(alpha = 0.14f), stringResource(R.string.zone_low, lowKOhm))))
    }
}

@Composable private fun Label(res: Int) = Text(stringResource(res), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Tile(labelRes: Int, value: String, modifier: Modifier = Modifier, subRes: Int? = null) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(8.dp)) {
            Label(labelRes)
            Text(value, fontSize = 15.sp, style = MaterialTheme.typography.titleMedium)
            subRes?.let { Text(stringResource(it), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun formatDuration(ms: Long): String { val m = ms / 60_000; return "%d:%02d".format(m / 60, m % 60) }
private fun formatKOhm(k: Double) = if (k >= 1000) "%.2f MΩ".format(k / 1000) else "%.0f kΩ".format(k)
