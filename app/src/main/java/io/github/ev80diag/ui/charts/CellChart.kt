package io.github.ev80diag.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ev80diag.battery.CellSeriesData
import io.github.ev80diag.ui.components.CellColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Cell voltages over time on one V axis: the 10 lowest cells red, the 10 highest blue, all others as a gray
 * min–max band; the lowest and highest bold and labelled; tapped cells in the text colour.
 * Tap sets the snapshot time; pinch zooms and drag pans ([onTransform]: zoom factor, pan as a fraction of the
 * width, focus as a fraction of the width).
 */
@Composable
fun CellChart(
    data: CellSeriesData, xMin: Long, xMax: Long, snapshotMs: Long?,
    onTapTime: (Long) -> Unit, onTransform: (zoom: Float, panFraction: Float, focusFraction: Float) -> Unit,
    modifier: Modifier = Modifier, height: Dp = 220.dp,
) {
    val measurer = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val text = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val label = TextStyle(fontSize = 9.sp, color = muted)
    val clock = SimpleDateFormat(if (xMax - xMin < 10 * 60_000) "HH:mm:ss" else if (xMax - xMin < 2 * 86_400_000L) "HH:mm" else "d MMM", Locale.getDefault())
    val leftDp = 38.dp; val rightDp = 26.dp

    // The gesture handlers live as long as the chart (keyed on Unit) and read the current window through
    // these: keyed on the window they would restart after every step and a drag would stall.
    val range by rememberUpdatedState(xMin to xMax)
    val tap by rememberUpdatedState(onTapTime)
    val transform by rememberUpdatedState(onTransform)
    Canvas(modifier.fillMaxWidth().height(height)
        .pointerInput(Unit) {
            detectTapGestures { o ->
                val (from, to) = range
                val left = leftDp.toPx(); val w = size.width - left - rightDp.toPx()
                if (w > 0) tap(from + (((o.x - left) / w).coerceIn(0f, 1f) * (to - from)).toLong())
            }
        }
        .pointerInput(Unit) {
            // pinch zooms, a horizontal drag pans; a vertical drag is left to the page scroll
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val event = awaitPointerEvent()
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    if (zoom != 1f || (pan.x != 0f && abs(pan.x) > abs(pan.y))) {
                        transform(zoom, -pan.x / size.width, (event.calculateCentroid().x / size.width).coerceIn(0f, 1f))
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        }) {
        val left = leftDp.toPx(); val right = rightDp.toPx(); val top = 8.dp.toPx(); val bottom = 14.dp.toPx()
        val w = size.width - left - right; val h = size.height - top - bottom
        if (w <= 0f || h <= 0f || xMax <= xMin || data.times.isEmpty()) return@Canvas
        val all = data.lines.values.flatMap { it.asList() } + data.bandMin.asList() + data.bandMax.asList()
        val yLo = floor((all.min() - 10) / 50.0) * 50.0
        val yHi = ceil((all.max() + 10) / 50.0) * 50.0
        fun x(t: Long) = left + w * ((t - xMin).toFloat() / (xMax - xMin)).coerceIn(0f, 1f)
        fun y(mv: Int) = top + h * (1f - ((mv - yLo) / (yHi - yLo)).toFloat())

        // grid and V labels every 50 or 100 mV
        val step = if (yHi - yLo > 300) 100 else 50
        var v = yLo
        while (v <= yHi) {
            drawLine(grid, Offset(left, y(v.toInt())), Offset(left + w, y(v.toInt())), strokeWidth = 1f)
            val tl = measurer.measure("%.2f".format(v / 1000.0), label)
            drawText(tl, topLeft = Offset(left - tl.size.width - 4.dp.toPx(), y(v.toInt()) - tl.size.height / 2f))
            v += step
        }
        for (i in 0..3) {
            val t = xMin + (xMax - xMin) * i / 3
            val tl = measurer.measure(clock.format(Date(t)), label)
            drawText(tl, topLeft = Offset((x(t) - tl.size.width / 2f).coerceIn(left, size.width - tl.size.width.toFloat()), size.height - tl.size.height))
        }

        // band of all other cells
        if (data.times.size >= 2) {
            val band = Path().apply {
                moveTo(x(data.times[0]), y(data.bandMax[0]))
                for (i in 1 until data.times.size) lineTo(x(data.times[i]), y(data.bandMax[i]))
                for (i in data.times.indices.reversed()) lineTo(x(data.times[i]), y(data.bandMin[i]))
                close()
            }
            drawPath(band, CellColors.median.copy(alpha = 0.45f))
        }
        val lowest = data.lowest.firstOrNull(); val highest = data.highest.firstOrNull()
        fun line(cell: Int, color: Color, width: Float) {
            val vals = data.lines[cell] ?: return
            if (data.times.size == 1) { drawCircle(color, width * 1.5f, Offset(x(data.times[0]), y(vals[0]))); return }
            val p = Path().apply { moveTo(x(data.times[0]), y(vals[0])); for (i in 1 until vals.size) lineTo(x(data.times[i]), y(vals[i])) }
            drawPath(p, color, style = Stroke(width = width))
        }
        val thin = 1.dp.toPx(); val bold = 2.5.dp.toPx()
        data.lowest.forEach { if (it != lowest) line(it, CellColors.low.copy(alpha = 0.7f), thin) }
        data.highest.forEach { if (it != highest) line(it, CellColors.high.copy(alpha = 0.7f), thin) }
        (data.lines.keys - data.lowest.toSet() - data.highest.toSet()).forEach { line(it, text, 2.dp.toPx()) }
        lowest?.let { line(it, CellColors.low, bold) }
        highest?.let { line(it, CellColors.high, bold) }
        // labels at the right end: extremes and tapped cells
        val labelled = listOfNotNull(lowest, highest) + (data.lines.keys - data.lowest.toSet() - data.highest.toSet())
        labelled.forEach { c ->
            val vals = data.lines[c] ?: return@forEach
            val tl = measurer.measure("#${c + 1}", TextStyle(fontSize = 9.sp, color = text))
            drawText(tl, topLeft = Offset(left + w + 2.dp.toPx(), y(vals.last()) - tl.size.height / 2f))
        }
        snapshotMs?.let { t ->
            val sx = x(t)
            drawLine(muted, Offset(sx, top), Offset(sx, top + h), strokeWidth = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        }
    }
}
