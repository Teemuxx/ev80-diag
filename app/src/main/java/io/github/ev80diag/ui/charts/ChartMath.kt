package io.github.ev80diag.ui.charts

import kotlin.math.ceil
import kotlin.math.log10

object ChartMath {
    /** Position of [value] between [min] and [max] as 0..1 (linear or log10), clamped. */
    fun fraction(value: Double, min: Double, max: Double, log: Boolean): Double {
        val f = if (log) (log10(value.coerceAtLeast(min)) - log10(min)) / (log10(max) - log10(min))
        else (value - min) / (max - min)
        return f.coerceIn(0.0, 1.0)
    }

    /** At most [maxPoints] points: first, last and the min and max of each bucket, in x order. */
    fun decimate(points: List<Pair<Long, Double>>, maxPoints: Int): List<Pair<Long, Double>> {
        if (points.size <= maxPoints || maxPoints < 4) return points
        val inner = points.subList(1, points.size - 1)
        val buckets = (maxPoints - 2) / 2
        val per = ceil(inner.size / buckets.toDouble()).toInt()
        val out = ArrayList<Pair<Long, Double>>(maxPoints)
        out.add(points.first())
        var i = 0
        while (i < inner.size) {
            val chunk = inner.subList(i, minOf(i + per, inner.size))
            val lo = chunk.minBy { it.second }
            val hi = chunk.maxBy { it.second }
            if (lo === hi) out.add(lo) else if (lo.first <= hi.first) { out.add(lo); out.add(hi) } else { out.add(hi); out.add(lo) }
            i += per
        }
        out.add(points.last())
        return out
    }
}
