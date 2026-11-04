package io.github.ev80diag.battery

/**
 * The Battery chart's data: the 10 lowest and 10 highest cells (chosen at the snapshot time) as lines,
 * every other cell folded into a min–max band. [lines] holds those 20 plus any tapped cells, by index.
 */
class CellSeriesData(
    val times: LongArray,
    val lowest: List<Int>,
    val highest: List<Int>,
    val lines: Map<Int, IntArray>,
    val bandMin: IntArray,
    val bandMax: IntArray,
)

object CellSeries {
    private const val PICK = 10

    fun build(snapshots: List<Pair<Long, IntArray>>, atMs: Long, extra: Set<Int>): CellSeriesData? {
        if (snapshots.isEmpty()) return null
        val at = snapshots.lastOrNull { it.first <= atMs } ?: snapshots.first()
        val mv = at.second
        val lowest = mv.indices.sortedWith(compareBy({ mv[it] }, { it })).take(PICK)
        val highest = mv.indices.filter { it !in lowest }.sortedWith(compareBy({ -mv[it] }, { it })).take(PICK)
        val lineCells = (lowest + highest + extra.filter { it in mv.indices }).toSet()
        val times = LongArray(snapshots.size) { snapshots[it].first }
        val lines = lineCells.associateWith { c -> IntArray(snapshots.size) { snapshots[it].second.getOrElse(c) { 0 } } }
        val others = mv.indices.filter { it !in lineCells }
        val bandMin = IntArray(snapshots.size) { i -> others.minOfOrNull { snapshots[i].second[it] } ?: 0 }
        val bandMax = IntArray(snapshots.size) { i -> others.maxOfOrNull { snapshots[i].second[it] } ?: 0 }
        return CellSeriesData(times, lowest, highest, lines, bandMin, bandMax)
    }
}
