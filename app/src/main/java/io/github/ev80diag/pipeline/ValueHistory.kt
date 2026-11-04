package io.github.ev80diag.pipeline

/** The last [windowMs] of every decoded value, at most one point per key per second (Details charts). */
class ValueHistory(private val windowMs: Long = 600_000) {
    private val series = HashMap<String, ArrayDeque<Pair<Long, Double>>>()

    fun add(tsMs: Long, values: Map<String, Double>) {
        for ((k, v) in values) {
            val q = series.getOrPut(k) { ArrayDeque() }
            val last = q.lastOrNull()
            if (last != null && tsMs / 1000 <= last.first / 1000) continue
            q.addLast(tsMs to v)
            while (q.isNotEmpty() && tsMs - q.first().first > windowMs) q.removeFirst()
        }
    }

    fun series(key: String): List<Pair<Long, Double>> = series[key]?.toList().orEmpty()
}
