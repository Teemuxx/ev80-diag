package io.github.ev80diag.ui.screen

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ev80diag.R
import io.github.ev80diag.ui.components.StatusChip
import io.github.ev80diag.ui.components.ValueTile
import io.github.ev80diag.ui.theme.Blue
import io.github.ev80diag.ui.theme.StatusCritical
import io.github.ev80diag.warnings.Level
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Live (spec §5.2): cell health first, then power, then six tiles. */
@Composable
fun LiveScreen(modifier: Modifier = Modifier, vm: LiveViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val s = ui ?: return
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Column(modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StaleLine(s)
        if (landscape) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CellHero(s); PowerBand(s)
                }
                Column(Modifier.weight(1f)) { LiveTiles(s, perRow = 2) }
            }
        } else {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CellHero(s); PowerBand(s); LiveTiles(s, perRow = 2)
            }
        }
    }
}

/** Shown when the values are not live, so old numbers are never taken for current ones. */
@Composable
fun StaleLine(s: LiveUi) {
    if (!s.stale) return
    val at = s.valuesFromMs?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) }
    Text(if (at != null) stringResource(R.string.live_stale, at) else stringResource(R.string.live_no_data),
        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
}

private fun volts(mv: Int?) = mv?.let { "%.3f V".format(it / 1000.0) } ?: "–"

@Composable
private fun CellHero(s: LiveUi) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp).alpha(if (s.stale) 0.45f else 1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.minCellNo?.let { stringResource(R.string.live_lowest_cell, it) } ?: stringResource(R.string.live_lowest_cell_plain),
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (s.lowCellLevel != Level.OK) StatusChip(s.lowCellLevel, warnRes = R.string.cell_low, critRes = R.string.cell_low)
            }
            Text(volts(s.minCellMv), fontSize = 44.sp, style = MaterialTheme.typography.displaySmall)
            CellBar(s)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.spreadMv?.let { stringResource(R.string.live_spread, it) } ?: "–", fontSize = 15.sp)
                if (s.spreadMv != null) StatusChip(s.spreadLevel, okRes = R.string.spread_ok, warnRes = R.string.spread_high)
                if (s.maxCellNo != null && s.maxCellMv != null)
                    Text(stringResource(R.string.live_max_cell, s.maxCellNo, volts(s.maxCellMv)), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 2.90 V (empty) … 3.65 V (full) with marks for the lowest and highest cell. */
@Composable
private fun CellBar(s: LiveUi) {
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val mark = MaterialTheme.colorScheme.onSurface
    Column {
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val h = size.height
            drawRoundRect(Brush.horizontalGradient(listOf(StatusCritical.copy(alpha = 0.55f), track, Blue.copy(alpha = 0.55f))),
                Offset(0f, h * 0.3f), Size(size.width, h * 0.4f), CornerRadius(h, h))
            val lo = s.barMinFraction; val hi = s.barMaxFraction
            if (lo != null && hi != null) drawRect(mark.copy(alpha = 0.35f), Offset(lo * size.width, h * 0.3f), Size((hi - lo) * size.width, h * 0.4f))
            for (f in listOfNotNull(lo, hi)) drawRect(mark, Offset(f * size.width - 2f, 0f), Size(4f, h))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.live_bar_empty, "%.2f".format(LiveUiBuilder.BAR_EMPTY_MV / 1000.0)), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.live_bar_full, "%.2f".format(LiveUiBuilder.BAR_FULL_MV / 1000.0)), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PowerBand(s: LiveUi) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.fillMaxWidth().padding(12.dp).alpha(if (s.stale) 0.45f else 1f), verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(stringResource(R.string.live_power), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(s.powerKw?.let { "%.1f kW".format(it) } ?: "–", fontSize = 34.sp)
                    if ((s.powerKw ?: 0.0) < -0.05) Text(" " + stringResource(R.string.live_regen), fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.live_pack) + "  " + (s.packV?.let { "%.1f V".format(it) } ?: "–"), fontSize = 14.sp)
                Text(stringResource(R.string.live_current) + "  " + (s.packA?.let { "%.0f A".format(it) } ?: "–"), fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun LiveTiles(s: LiveUi, perRow: Int) {
    val tiles = listOf(
        Triple(stringResource(R.string.live_soc_app), s.socApp?.let { "%.0f %%".format(it) } ?: "–",
            s.socBms?.let { stringResource(R.string.live_soc_bms, "%.1f".format(it)) }),
        Triple(stringResource(R.string.live_range_app), s.rangeAppKm?.let { "%.0f km".format(it) } ?: "–",
            s.rangeDashKm?.let { stringResource(R.string.live_range_dash, "%.0f".format(it)) }),
        Triple(stringResource(R.string.live_speed), s.speedKmh?.let { "%.0f km/h".format(it) } ?: "–", null),
        Triple(stringResource(R.string.live_consumption), s.kwhPer100Now?.let { "%.1f".format(it) } ?: "–",
            stringResource(R.string.live_consumption_sub)),
        Triple(stringResource(R.string.live_v12), s.v12?.let { "%.1f V".format(it) } ?: "–", null),
        Triple(stringResource(R.string.live_temps), s.tempBatt?.let { "%.0f °C".format(it) } ?: "–", null),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value, sub) -> ValueTile(label, value, sub, Modifier.weight(1f), dimmed = s.stale) }
            }
        }
    }
}

