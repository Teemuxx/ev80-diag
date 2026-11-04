package io.github.ev80diag.ui.screen

import io.github.ev80diag.R
import io.github.ev80diag.calc.RangeEstimator
import io.github.ev80diag.pipeline.LinkStatus
import io.github.ev80diag.pipeline.RecorderState
import io.github.ev80diag.session.PowerState
import io.github.ev80diag.warnings.Level
import io.github.ev80diag.warnings.WarningRules

data class LiveThresholds(val spreadMv: Int = 80, val lowCellMv: Int = 3100)

/** Everything the Live screen (and the Tiles) show, already decided. */
data class LiveUi(
    val minCellMv: Int?, val minCellNo: Int?, val maxCellMv: Int?, val maxCellNo: Int?,
    val spreadMv: Int?, val spreadLevel: Level, val lowCellLevel: Level,
    /** Positions of the lowest/highest cell on the 2.90 V (empty) … 3.65 V (full) bar, 0..1. */
    val barMinFraction: Float?, val barMaxFraction: Float?,
    /** Positive while driving (discharge), negative for regen or charging. */
    val powerKw: Double?, val packV: Double?, val packA: Double?,
    val socApp: Double?, val socBms: Double?, val rangeAppKm: Double?, val rangeDashKm: Double?,
    val speedKmh: Double?, val kwhPer100Now: Double?, val v12: Double?, val tempBatt: Double?, val tempMotor: Double?,
    /** The van is asleep or not connected: the values are the last ones seen, from [valuesFromMs]. */
    val stale: Boolean, val valuesFromMs: Long?,
)

object LiveUiBuilder {
    const val BAR_EMPTY_MV = 2900
    const val BAR_FULL_MV = 3650
    private val CHARGING = setOf(PowerState.CHARGING_AC, PowerState.CHARGING_DC, PowerState.CHARGE_COMPLETE)

    /** [allTimeKwhPer100] is the stored drive average, used for the range until 5 km of this drive are known. */
    fun build(state: RecorderState, capacityAh: Double, allTimeKwhPer100: Double?, t: LiveThresholds): LiveUi {
        val d = state.decoded
        val minMv = d["cell_min_mv"]?.toInt()
        val maxMv = d["cell_max_mv"]?.toInt()
        val spread = if (minMv != null && maxMv != null) maxMv - minMv else null
        val charging = state.van.power in CHARGING
        val packV = d["pack_v"]
        val packA = d["pack_a"]
        val socApp = state.socAppPercent?.toDouble()
        val stale = state.link != LinkStatus.CONNECTED || state.van.power == PowerState.OFF
        return LiveUi(
            minCellMv = minMv, minCellNo = d["cell_min_no"]?.toInt(), maxCellMv = maxMv, maxCellNo = d["cell_max_no"]?.toInt(),
            spreadMv = spread,
            spreadLevel = if (spread != null && maxMv != null)
                WarningRules.spread(spread, maxMv, d["soc_bms"], charging, t.spreadMv, state.msSinceLoadChange) else Level.OK,
            lowCellLevel = minMv?.let { WarningRules.lowCell(it, t.lowCellMv) } ?: Level.OK,
            barMinFraction = minMv?.let(::barFraction), barMaxFraction = maxMv?.let(::barFraction),
            powerKw = if (packV != null && packA != null) packV * packA / 1000.0 else null, packV = packV, packA = packA,
            socApp = socApp, socBms = d["soc_bms"],
            rangeAppKm = RangeEstimator.rangeKm(socApp, capacityAh, packV, state.drive?.kwhPer100Recent ?: allTimeKwhPer100),
            rangeDashKm = d["range_dash_km"],
            speedKmh = d["speed_kmh"], kwhPer100Now = state.drive?.kwhPer100Now, v12 = d["v12"],
            tempBatt = d["temp_avg"], tempMotor = d["motor_temp"],
            stale = stale, valuesFromMs = state.van.lastFrameMs.takeIf { stale && it > 0 },
        )
    }

    private fun barFraction(mv: Int): Float =
        ((mv - BAR_EMPTY_MV).toFloat() / (BAR_FULL_MV - BAR_EMPTY_MV)).coerceIn(0f, 1f)
}

/** The top bar's warning: the worst of lowest cell, spread and insulation, with its text (never colour alone). */
object TopBarWarning {
    fun of(live: LiveUi, insulationLevel: Level): Pair<Level, Int?> {
        val candidates = listOf(
            live.lowCellLevel to R.string.warn_cell_low,
            insulationLevel to R.string.warn_insulation_low,
            live.spreadLevel to R.string.warn_spread_high,
        )
        val worst = candidates.maxByOrNull { it.first.ordinal } ?: return Level.OK to null
        return if (worst.first == Level.OK) Level.OK to null else worst
    }
}
