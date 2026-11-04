package io.github.ev80diag.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A shaded band on the y axis (e.g. "BMS stops charging"). */
data class ChartZone(val from: Double, val to: Double, val color: Color, val label: String)

/**
 * One line on one y axis (linear or log10) over time. Charts that belong together share [xMin]/[xMax]
 * so that moments line up. No dual axes by design.
 */
@Composable
fun TimeSeriesChart(
    title: String,
    points: List<Pair<Long, Double>>,
    xMin: Long, xMax: Long,
    yMin: Double, yMax: Double,
    yTicks: List<Pair<Double, String>>,
    lineColor: Color,
    modifier: Modifier = Modifier,
    log: Boolean = false,
    fill: Boolean = false,
    zones: List<ChartZone> = emptyList(),
    showNow: Boolean = false,
    height: Dp = 110.dp,
) {
    val measurer = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val label = TextStyle(fontSize = 9.sp, color = textColor)
    // short sessions get seconds, or the four time labels repeat the same minute
    val clock = SimpleDateFormat(if (xMax - xMin < 10 * 60_000) "HH:mm:ss" else "HH:mm", Locale.getDefault())

    Canvas(modifier.fillMaxWidth().height(height)) {
        val left = 38.dp.toPx(); val right = 6.dp.toPx(); val top = 14.dp.toPx(); val bottom = 14.dp.toPx()
        val w = size.width - left - right; val h = size.height - top - bottom
        if (w <= 0f || h <= 0f || xMax <= xMin) return@Canvas
        fun x(t: Long) = left + w * ((t - xMin).toFloat() / (xMax - xMin)).coerceIn(0f, 1f)
        fun y(v: Double) = top + h * (1f - ChartMath.fraction(v, yMin, yMax, log).toFloat())

        zones.forEach { z ->
            drawRect(z.color, Offset(left, y(z.to)), androidx.compose.ui.geometry.Size(w, y(z.from) - y(z.to)))
            val tl = measurer.measure(z.label, label)
            drawText(tl, topLeft = Offset(left + w - tl.size.width - 3.dp.toPx(), y(z.to) + 1.dp.toPx()))
        }
        yTicks.forEach { (v, text) ->
            drawLine(grid, Offset(left, y(v)), Offset(left + w, y(v)), strokeWidth = 1f)
            val tl = measurer.measure(text, label)
            drawText(tl, topLeft = Offset(left - tl.size.width - 4.dp.toPx(), y(v) - tl.size.height / 2f))
        }
        drawText(measurer.measure(title, TextStyle(fontSize = 10.sp, color = textColor)), topLeft = Offset(left + 4.dp.toPx(), 0f))
        for (i in 0..3) {
            val t = xMin + (xMax - xMin) * i / 3
            val tl = measurer.measure(clock.format(Date(t)), label)
            drawText(tl, topLeft = Offset((x(t) - tl.size.width / 2f).coerceIn(left, size.width - tl.size.width.toFloat()), size.height - tl.size.height))
        }
        val pts = ChartMath.decimate(points.filter { it.first in xMin..xMax }, MAX_POINTS)
        if (pts.size >= 2) {
            val path = Path().apply { moveTo(x(pts[0].first), y(pts[0].second)); pts.drop(1).forEach { lineTo(x(it.first), y(it.second)) } }
            if (fill) {
                val area = Path().apply { addPath(path); lineTo(x(pts.last().first), y(yMin)); lineTo(x(pts.first().first), y(yMin)); close() }
                drawPath(area, lineColor.copy(alpha = 0.18f))
            }
            drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))
        }
        if (showNow && points.isNotEmpty()) {
            val nx = x(points.last().first)
            drawLine(textColor, Offset(nx, top), Offset(nx, top + h), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        }
    }
}

private const val MAX_POINTS = 600
