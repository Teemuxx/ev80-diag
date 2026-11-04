package io.github.ev80diag.calc

/**
 * Time since the pack current last changed by a lot: |I − I(5 s earlier)| ≥ 50 A. The cell spread
 * widens for a while after such a step, so its warning waits (spec §11).
 */
class LoadChangeTracker {
    private val recent = ArrayDeque<Pair<Long, Double>>()
    private var lastChangeMs: Long? = null

    fun onCurrent(tsMs: Long, a: Double) {
        recent.addLast(tsMs to a)
        // keep the newest sample that is at least 5 s old as the reference
        while (recent.size > 2 && tsMs - recent[1].first >= LOOKBACK_MS) recent.removeFirst()
        val ref = recent.first()
        if (tsMs - ref.first >= LOOKBACK_MS && kotlin.math.abs(a - ref.second) >= STEP_A) lastChangeMs = tsMs
    }

    fun msSinceChange(nowMs: Long): Long? = lastChangeMs?.let { nowMs - it }

    private companion object {
        const val LOOKBACK_MS = 5_000L
        const val STEP_A = 50.0
    }
}
