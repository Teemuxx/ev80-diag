package io.github.ev80diag.warnings

enum class Level { OK, AMBER, RED }

/** State-aware warning levels (spec §11). Status is always shown with icon + text, never colour alone. */
object WarningRules {
    /** Amber below [low], red below [critical] (the BMS stops charging near 36 kΩ). */
    fun insulation(kOhm: Double, low: Double = 180.0, critical: Double = 36.0): Level = when {
        kOhm < critical -> Level.RED
        kOhm < low -> Level.AMBER
        else -> Level.OK
    }

    /** The van won't AC charge below about 3 °C; red below 0 °C while charging. */
    fun coldBattery(tMinC: Double, charging: Boolean, cold: Double = 3.0): Level = when {
        charging && tMinC < 0.0 -> Level.RED
        tMinC < cold -> Level.AMBER
        else -> Level.OK
    }

    /**
     * A wide spread at the top of a charge is normal for LFP, and so is a wider spread for a minute after a
     * load change; elsewhere amber at the threshold, red at twice.
     */
    fun spread(spreadMv: Int, maxCellMv: Int, socBms: Double?, charging: Boolean, thresholdMv: Int = 80,
               msSinceLoadChange: Long? = null): Level = when {
        charging && (maxCellMv >= TOP_CELL_MV || (socBms ?: 0.0) >= TOP_SOC) -> Level.OK
        !charging && msSinceLoadChange != null && msSinceLoadChange < LOAD_SETTLE_MS -> Level.OK
        spreadMv >= 2 * thresholdMv -> Level.RED
        spreadMv >= thresholdMv -> Level.AMBER
        else -> Level.OK
    }

    /** Amber below [thresholdMv], red 0.1 V lower. */
    fun lowCell(minMv: Int, thresholdMv: Int = 3100): Level = when {
        minMv < thresholdMv - 100 -> Level.RED
        minMv < thresholdMv -> Level.AMBER
        else -> Level.OK
    }

    private const val LOAD_SETTLE_MS = 60_000L
    private const val TOP_CELL_MV = 3450
    private const val TOP_SOC = 95.0
}
