package io.github.ev80diag.calc

/** The app's range (spec §9): energy left from the app's SOC, divided by recent consumption. */
object RangeEstimator {
    /** Below this a consumption is not a drive (parked with the heater on, long downhill): no range. */
    private const val MIN_KWH_PER_100 = 5.0

    fun rangeKm(socPct: Double?, capacityAh: Double, packV: Double?, kwhPer100: Double?): Double? {
        if (socPct == null || packV == null || kwhPer100 == null || kwhPer100 < MIN_KWH_PER_100) return null
        val kwhLeft = socPct / 100.0 * capacityAh * packV / 1000.0
        return kwhLeft / kwhPer100 * 100.0
    }
}
