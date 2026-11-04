package io.github.ev80diag.pipeline

import io.github.ev80diag.signals.SignalTable

/**
 * Drops values whose CAN ID has gone quiet while other IDs still arrive (e.g. the drive side during a charge,
 * when only the BMS set is on the bus). Ages count against the newest frame, not the wall clock, so when the
 * whole van sleeps nothing is dropped: the screens dim the last values and say when they are from.
 */
object FreshValues {
    const val MAX_AGE_MS = 5_000L

    /** Keys computed from a frame that has no table row of their own. */
    private val DERIVED = mapOf("cell_max_no" to 0x530, "cell_min_no" to 0x530)
    private val SOURCE: Map<String, Int> = SignalTable.all.associate { it.key to it.canId } + DERIVED

    fun filter(latest: Map<String, Double>, lastSeen: Map<Int, Long>, newestTs: Long, maxAgeMs: Long = MAX_AGE_MS): Map<String, Double> =
        latest.filter { (k, _) ->
            val id = SOURCE[k] ?: return@filter true
            val seen = lastSeen[id] ?: return@filter false
            newestTs - seen <= maxAgeMs
        }
}
