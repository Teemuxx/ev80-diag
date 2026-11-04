package io.github.ev80diag.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ev80diag.R
import io.github.ev80diag.battery.CellStats
import io.github.ev80diag.battery.CellView

/** Colour scale A (spec §5.5): gray at the pack median, red below, blue above, saturating at ±60 mV. */
object CellColors {
    val median = Color(0xFF6E6E6A)
    val low = Color(0xFFD6453D)
    val high = Color(0xFF3987E5)
    fun of(f: Float): Color = if (f < 0) lerp(median, low, -f) else lerp(median, high, f)
}

/** The 104 cells in their two banks (1–60, 61–104), numbered; lowest and highest outlined; tap toggles a chart line. */
@Composable
fun CellHeatmap(view: CellView, perRow: Int, selected: Set<Int>, onTap: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Bank(stringResource(R.string.battery_bank1), 0 until 60, view, perRow, selected, onTap)
        Bank(stringResource(R.string.battery_bank2), 60 until 104, view, perRow, selected, onTap)
        Legend(view.median)
    }
}

@Composable
private fun Bank(title: String, cells: IntRange, view: CellView, perRow: Int, selected: Set<Int>, onTap: (Int) -> Unit) {
    Text(title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        cells.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                row.forEach { i ->
                    val c = CellColors.of(CellStats.colourFraction(view.mv[i], view.median))
                    val extreme = i == view.minIdx || i == view.maxIdx
                    val outline = when { extreme -> MaterialTheme.colorScheme.onSurface; i in selected -> MaterialTheme.colorScheme.primary; else -> Color.Transparent }
                    Box(Modifier.weight(1f).aspectRatio(1.3f).background(c, RoundedCornerShape(3.dp))
                        .border(if (extreme || i in selected) 2.dp else 0.dp, outline, RoundedCornerShape(3.dp))
                        .clickable { onTap(i) }, contentAlignment = Alignment.Center) {
                        Text("${i + 1}", fontSize = 9.sp, color = Color.White)
                    }
                }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Legend(median: Int) {
    Column {
        Box(Modifier.fillMaxWidth().height(8.dp).background(Brush.horizontalGradient(listOf(CellColors.low, CellColors.median, CellColors.high)), RoundedCornerShape(4.dp)))
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("−${CellStats.SATURATION_MV} mV", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.battery_median, "%.3f".format(median / 1000.0)), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("+${CellStats.SATURATION_MV} mV", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
