package io.github.ev80diag.battery

/** One 104-cell snapshot with what the heatmap needs. Indices are 0-based (cell number = index + 1). */
class CellView(val mv: IntArray, val median: Int, val minIdx: Int, val maxIdx: Int, val spreadMv: Int)

object CellStats {
    /** The colour scale saturates this far from the median (spec §5.5). */
    const val SATURATION_MV = 60

    fun of(mv: IntArray): CellView {
        val sorted = mv.sorted()
        val n = sorted.size
        val median = if (n % 2 == 0) Math.round((sorted[n / 2 - 1] + sorted[n / 2]) / 2.0).toInt() else sorted[n / 2]
        val minIdx = mv.indices.minBy { mv[it] }
        val maxIdx = mv.indices.maxBy { mv[it] }
        return CellView(mv, median, minIdx, maxIdx, mv[maxIdx] - mv[minIdx])
    }

    /** −1 (red, far below the median) … 0 (gray, at the median) … 1 (blue, far above). */
    fun colourFraction(mv: Int, median: Int): Float =
        ((mv - median).toFloat() / SATURATION_MV).coerceIn(-1f, 1f)
}
