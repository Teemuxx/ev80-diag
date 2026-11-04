package io.github.ev80diag.ui.screen

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ev80diag.R
import io.github.ev80diag.battery.TimeWindow
import io.github.ev80diag.ui.charts.CellChart
import io.github.ev80diag.ui.components.CellColors
import io.github.ev80diag.ui.components.CellHeatmap
import io.github.ev80diag.ui.components.SessionPickRow
import io.github.ev80diag.ui.components.StatusChip
import io.github.ev80diag.ui.components.TimePickerSheet
import io.github.ev80diag.warnings.Level
import io.github.ev80diag.warnings.WarningRules
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Battery (spec §5.5): time bar, 104-cell heatmap at the snapshot time, cell chart over the period. */
@Composable
fun BatteryScreen(modifier: Modifier = Modifier, vm: BatteryViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val s = ui ?: return
    var sheet by remember { mutableStateOf(false) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Column(modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TimeBar(s.window, onEarlier = vm::earlier, onZoomOut = vm::zoomOut, onLater = vm::later, onOpen = { sheet = true })
        val view = s.view
        if (view == null) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.battery_empty), modifier = Modifier.padding(vertical = 12.dp))
                Text(stringResource(R.string.battery_sessions), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                sessions.take(10).forEach { SessionPickRow(it) { vm.session(it) } }
            }
        } else {
            val at = s.snapshotMs?.let { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it)) } ?: "–"
            Text(stringResource(R.string.battery_snapshot, at, view.minIdx + 1, "%.3f".format(view.mv[view.minIdx] / 1000.0),
                view.maxIdx + 1, "%.3f".format(view.mv[view.maxIdx] / 1000.0)), fontSize = 12.sp)
            val heatmap: @Composable (Int, Modifier) -> Unit = { perRow, m ->
                Column(m, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CellHeatmap(view, perRow, s.extra, onTap = vm::toggleCell)
                    SpreadLine(view.spreadMv, view.mv[view.maxIdx], s.charging, vm.spreadWarnMv)
                }
            }
            val chart: @Composable (Modifier) -> Unit = { m ->
                Column(m) {
                    s.series?.let { data ->
                        CellChart(data, s.window.fromMs, s.window.toMs, s.snapshotMs, onTapTime = vm::pickTime,
                            onTransform = { zoom, pan, focus ->
                                val len = s.window.lengthMs
                                if (zoom != 1f) vm.zoom(zoom.toDouble(), s.window.fromMs + (focus * len).toLong())
                                if (pan != 0f) vm.pan((pan * len).toLong())
                            })
                    }
                    ChartLegend()
                }
            }
            if (landscape) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    heatmap(15, Modifier.weight(1.2f).verticalScroll(rememberScrollState()))
                    chart(Modifier.weight(1f))
                }
            } else {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    heatmap(10, Modifier); chart(Modifier)
                }
            }
        }
    }

    if (sheet) TimePickerSheet(sessions, onQuick = vm::quick, onSession = vm::session, onSinceFull = { vm.sinceLastFull() },
        onRange = vm::range, onEarlier = vm::earlier, onLater = vm::later, onZoomOut = vm::zoomOut, onDismiss = { sheet = false })
}

@Composable
private fun TimeBar(w: TimeWindow, onEarlier: () -> Unit, onZoomOut: () -> Unit, onLater: () -> Unit, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onOpen, Modifier.weight(1f)) {
            Icon(Icons.Filled.DateRange, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
            Text(periodText(w), fontSize = 13.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.battery_pick_period))
        }
        IconButton(onEarlier) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.battery_earlier)) }
        IconButton(onZoomOut) { Icon(Icons.Filled.ZoomOut, stringResource(R.string.battery_zoom_out)) }
        IconButton(onLater, enabled = !w.followNow) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.battery_later)) }
    }
}

private fun periodText(w: TimeWindow): String {
    val day = SimpleDateFormat("d MMM", Locale.getDefault())
    val time = SimpleDateFormat("HH:mm", Locale.getDefault())
    val a = Calendar.getInstance().apply { timeInMillis = w.fromMs }
    val b = Calendar.getInstance().apply { timeInMillis = w.toMs }
    val sameDay = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    return if (sameDay) "${day.format(Date(w.fromMs))} · ${time.format(Date(w.fromMs))} – ${time.format(Date(w.toMs))}"
    else "${day.format(Date(w.fromMs))} ${time.format(Date(w.fromMs))} – ${day.format(Date(w.toMs))} ${time.format(Date(w.toMs))}"
}

/** Spread with its level; a wide spread at the top of a charge is shown as "normal at full", not as a warning. */
@Composable
private fun SpreadLine(spreadMv: Int, maxMv: Int, charging: Boolean, thresholdMv: Int) {
    val level = WarningRules.spread(spreadMv, maxMv, null, charging, thresholdMv)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.live_spread, spreadMv), fontSize = 14.sp)
        if (level == Level.OK && spreadMv >= thresholdMv) Text(stringResource(R.string.spread_normal_full), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else StatusChip(level, okRes = R.string.spread_ok, warnRes = R.string.spread_high)
        Text(stringResource(R.string.battery_tap_hint), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChartLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
        listOf(CellColors.low to R.string.battery_legend_lowest, CellColors.high to R.string.battery_legend_highest,
            CellColors.median to R.string.battery_legend_others).forEach { (c, res) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.padding(end = 4.dp).size(10.dp)) { drawRect(c) }
                Text(stringResource(res), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

