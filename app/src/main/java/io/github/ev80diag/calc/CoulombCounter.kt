package io.github.ev80diag.calc

/**
 * Integrates battery current over time to track consumed/charged Ah.
 * Uses trapezoidal integration for accuracy.
 *
 * Current sign convention (from 0x550):
 *   positive = discharge (driving)
 *   negative = charge (AC charging, regen braking)
 */
class CoulombCounter(var totalCapacityAh: Float = 168f) {

    var consumedAh = 0f
        private set

    private var lastTimestamp = 0L
    private var lastCurrentA = 0f
    private var initialized = false

    /**
     * Feed a current sample. Integration happens between consecutive samples.
     * Gaps > 36 seconds are ignored (e.g., BT disconnect).
     */
    fun addSample(timestamp: Long, currentA: Float) {
        if (initialized && lastTimestamp > 0) {
            val dtHours = (timestamp - lastTimestamp) / 3_600_000f
            if (dtHours in 0f..0.01f) { // max ~36s gap
                consumedAh += (currentA + lastCurrentA) / 2f * dtHours
            }
        }
        lastTimestamp = timestamp
        lastCurrentA = currentA
        initialized = true
    }

    fun remainingAh(): Float = (totalCapacityAh - consumedAh).coerceAtLeast(0f)

    fun remainingPercent(): Float =
        (remainingAh() / totalCapacityAh * 100f).coerceIn(0f, 100f)

    /**
     * Initialize from BMS-reported SOC (used as starting point on first connection).
     */
    fun initializeFromSoc(socPercent: Float) {
        consumedAh = totalCapacityAh * (1f - socPercent / 100f)
        initialized = true
    }

    /**
     * Resume from a previously saved coulomb count (e.g., from last DB entry).
     */
    fun initializeFromConsumedAh(ah: Float) {
        consumedAh = ah
        initialized = true
    }

    fun reset() {
        consumedAh = 0f
        lastTimestamp = 0
        lastCurrentA = 0f
        initialized = false
    }
}

