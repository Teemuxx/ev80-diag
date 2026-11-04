package io.github.ev80diag.calc

/**
 * Computes average energy consumption over a sliding distance window.
 * Default window = 5 km. Result in kWh/100km.
 *
 * Uses trapezoidal integration of power (V × I) over time.
 * Net energy = discharge - regen, so the result reflects real-world consumption
 * including regenerative braking recovery.
 */
class ConsumptionCalculator(private val windowKm: Float = 5f) {

    private data class Sample(
        val timestamp: Long,
        val odometerKm: Float,
        val cumulativeEnergyKwh: Float
    )

    private val samples = ArrayDeque<Sample>()
    private var cumulativeEnergyKwh = 0f
    private var lastTimestamp = 0L
    private var lastPowerKw = 0f
    private var initialized = false

    /**
     * Feed a power + odometer sample. Call for every 0x550 pack data update.
     */
    fun addSample(timestamp: Long, voltageV: Float, currentA: Float, odometerKm: Float) {
        val powerKw = voltageV * currentA / 1000f

        if (initialized && lastTimestamp > 0) {
            val dtHours = (timestamp - lastTimestamp) / 3_600_000f
            if (dtHours in 0f..0.01f) {
                cumulativeEnergyKwh += (powerKw + lastPowerKw) / 2f * dtHours
            }
        }
        lastTimestamp = timestamp
        lastPowerKw = powerKw
        initialized = true

        samples.addLast(Sample(timestamp, odometerKm, cumulativeEnergyKwh))

        // Trim: keep only the sliding window
        while (samples.size > 1 && odometerKm - samples.first().odometerKm > windowKm) {
            samples.removeFirst()
        }
    }

    /**
     * Returns average consumption in kWh/100km over the window,
     * or null if insufficient distance has been covered (< 200m).
     */
    fun getConsumptionKwhPer100km(): Float? {
        if (samples.size < 2) return null
        val first = samples.first()
        val last = samples.last()
        val distanceKm = last.odometerKm - first.odometerKm
        if (distanceKm < 0.2f) return null
        val energyKwh = last.cumulativeEnergyKwh - first.cumulativeEnergyKwh
        return energyKwh / distanceKm * 100f
    }

    /** Distance currently in the sliding window buffer, in km. 0 if no data yet. */
    fun getActualWindowKm(): Float {
        if (samples.size < 2) return 0f
        return samples.last().odometerKm - samples.first().odometerKm
    }

    fun reset() {
        samples.clear()
        cumulativeEnergyKwh = 0f
        lastTimestamp = 0
        lastPowerKw = 0f
        initialized = false
    }
}
