package io.github.ev80diag.calc

/** Distance, net energy and consumption of one drive. */
data class DriveSnapshot(val km: Double, val kwh: Double, val kwhPer100Now: Double?, val kwhPer100Recent: Double?)

/**
 * One instance per DRIVE session. Energy is net (discharge minus regen), integrated from pack V × A
 * (trapezoid, gaps > 2 s skipped). Consumption is energy per distance over a sliding odometer window:
 * "now" over 2 km, "recent" (for the range estimate) over 20 km once at least 5 km are in it.
 * Odometer frames reading 0 (seen at wake) or going backwards are ignored.
 */
class DriveMetrics {
    private data class Point(val km: Double, val kwh: Double)

    private var kwh = 0.0
    private var lastPackTs = -1L
    private var lastPowerKw = 0.0
    private var firstKm = -1.0
    private var lastKm = -1.0
    private val points = ArrayDeque<Point>()

    fun onPack(tsMs: Long, packV: Double, packA: Double) {
        val p = packV * packA / 1000.0
        if (lastPackTs >= 0) {
            val dt = tsMs - lastPackTs
            if (dt in 1..MAX_GAP_MS) kwh += (p + lastPowerKw) / 2.0 * dt / 3_600_000.0
        }
        lastPackTs = tsMs; lastPowerKw = p
    }

    fun onOdometer(tsMs: Long, km: Double) {
        if (km <= 0.0 || (lastKm >= 0 && km < lastKm)) return
        if (firstKm < 0) firstKm = km
        val moved = km != lastKm
        lastKm = km
        // the odometer moves in 0.1 km steps: standing still (READY with the heater on) adds no point
        if (moved) points.addLast(Point(km, kwh)) else if (points.isNotEmpty()) points[points.size - 1] = Point(km, kwh)
        while (points.size > 2 && km - points[1].km >= RECENT_WINDOW_KM) points.removeFirst()
    }

    internal val windowPoints: Int get() = points.size

    fun snapshot(): DriveSnapshot {
        val km = if (firstKm < 0) 0.0 else lastKm - firstKm
        return DriveSnapshot(km, kwh, per100(NOW_WINDOW_KM, MIN_KM), per100(RECENT_WINDOW_KM, RECENT_MIN_KM))
    }

    /** kWh/100 km over the last [windowKm], null while less than [minKm] are in it. */
    private fun per100(windowKm: Double, minKm: Double): Double? {
        if (points.size < 2) return null
        val last = points.last()
        val first = points.firstOrNull { last.km - it.km <= windowKm } ?: return null
        val dist = last.km - first.km
        if (dist < minKm) return null
        return (last.kwh - first.kwh) / dist * 100.0
    }

    private companion object {
        const val MAX_GAP_MS = 2_000L
        const val NOW_WINDOW_KM = 2.0
        const val RECENT_WINDOW_KM = 20.0
        const val MIN_KM = 0.2
        const val RECENT_MIN_KM = 5.0
    }
}
