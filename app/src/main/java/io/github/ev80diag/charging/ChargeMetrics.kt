package io.github.ev80diag.charging

/**
 * Live numbers of one charge session. Current is negative while charging (0x550), so
 * power = -V*A. Time to full is a provisional model fitted to the 2026-10-29 ET charge at the ABB
 * Terra 54 (one session): the naive missing-energy/power runs 10 % to 70 % short at constant power,
 * the shortfall growing toward the top (the BMS energy counter is coarse), and long during the taper.
 * Refine it with more logged sessions.
 */
class ChargeMetrics(private val fullEnergyKwh: Double = 55.7) {
    var powerKw = 0.0; private set
    var chargedKwh = 0.0; private set
    var elapsedMs = 0L; private set
    private var firstTs = -1L
    private var lastTs = -1L
    private var maxPowerKw = 0.0
    private var energyKwh: Double? = null
    private var socBms: Double? = null

    fun onSample(tsMs: Long, packV: Double, packA: Double, energyKwh: Double?, socBms: Double?) {
        val p = if (packA < 0) -packV * packA / 1000.0 else 0.0
        if (firstTs < 0) firstTs = tsMs
        if (lastTs >= 0) {
            val dt = tsMs - lastTs
            if (dt in 1..MAX_GAP_MS) chargedKwh += (powerKw + p) / 2.0 * dt / 3_600_000.0
        }
        lastTs = tsMs
        elapsedMs = tsMs - firstTs
        powerKw = p
        if (p > maxPowerKw) maxPowerKw = p
        if (energyKwh != null) this.energyKwh = energyKwh
        if (socBms != null) this.socBms = socBms
    }

    /** Minutes until the BMS reports full, or null if it can't be estimated. */
    fun timeToFullMin(): Double? {
        val e = energyKwh ?: return null
        if (powerKw < MIN_POWER_KW) return null
        val naive = maxOf(0.0, fullEnergyKwh - e) / powerKw * 60.0
        if (powerKw < TAPER_FRACTION * maxPowerKw) return minOf(naive, TAPER_CAP_MIN)
        val soc = socBms ?: return naive
        val x = ((soc - 85.0) / 12.5).coerceIn(0.0, 1.0)
        return naive * (1.1 + 0.6 * x * x)
    }

    private companion object {
        const val MAX_GAP_MS = 10_000L
        const val MIN_POWER_KW = 1.0
        const val TAPER_FRACTION = 0.85
        const val TAPER_CAP_MIN = 4.0
    }
}
