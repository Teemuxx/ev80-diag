package io.github.ev80diag.ui.screen

import io.github.ev80diag.data.store.SampleEntity
import io.github.ev80diag.data.store.SessionEntity
import io.github.ev80diag.pipeline.RecorderState
import io.github.ev80diag.warnings.Level
import io.github.ev80diag.warnings.WarningRules

data class ChargingThresholds(val insulationLowKOhm: Double = 180.0, val coldC: Double = 3.0, val spreadMv: Int = 80)

/** Everything the Charging screen shows, already decided (values, warning levels, tapering). */
data class ChargingUi(
    val powerKw: Double?, val packV: Double?, val packA: Double?, val tapering: Boolean,
    val socAppPercent: Double?, val socBmsPercent: Double?,
    val chargedKwh: Double?, val elapsedMs: Long, val timeToFullMin: Double?,
    val tempAvg: Double?, val tempMin: Double?, val tempMax: Double?, val temps: IntArray?, val coldLevel: Level,
    val insulationKOhm: Double?, val insulationLevel: Level,
    val cellMinMv: Int?, val cellMaxMv: Int?, val spreadMv: Int?, val spreadLevel: Level,
    val finished: Boolean,
)

object ChargingUiBuilder {
    private const val INSULATION_NO_READING = 10_000.0     // the BMS clamps at 10 MΩ until it has a measurement
    private const val TAPER_FRACTION = 0.80   // tapering: 20 % below the session maximum (plan, Task 12)
    private const val TAPER_MIN_SOC = 90.0

    /** [active]: a charge is running (values from [state]); otherwise [session] and its last sample are shown. */
    fun build(state: RecorderState, active: Boolean, session: SessionEntity?, samples: List<SampleEntity>, t: ChargingThresholds): ChargingUi {
        val last = samples.lastOrNull()
        val d = state.decoded
        val packV = if (active) d["pack_v"] else last?.packV
        val packA = if (active) d["pack_a"] else last?.packA
        val power = if (active) state.charge?.powerKw else last?.let { s -> if (s.packV != null && s.packA != null && s.packA < 0) -s.packV * s.packA / 1000 else 0.0 }
        val socBms = if (active) d["soc_bms"] else last?.socBms
        val socApp = if (active) state.socAppPercent?.toDouble() else last?.socApp
        val tMin = if (active) d["temp_min"] else last?.tempMin
        val tMax = if (active) d["temp_max"] else last?.tempMax
        val tAvg = if (active) d["temp_avg"] else last?.tempAvg
        val insRaw = if (active) d["insulation_kohm"] else last?.insulationKOhm
        val ins = insRaw?.takeIf { it < INSULATION_NO_READING }
        val cMin = (if (active) d["cell_min_mv"] else last?.cellMinMv)?.toInt()
        val cMax = (if (active) d["cell_max_mv"] else last?.cellMaxMv)?.toInt()
        val spread = if (cMin != null && cMax != null) cMax - cMin else null

        val maxPower = samples.maxOfOrNull { s -> if (s.packV != null && s.packA != null && s.packA < 0) -s.packV * s.packA / 1000 else 0.0 } ?: 0.0
        val tapering = active && power != null && maxPower > 0 && power < TAPER_FRACTION * maxPower && (socBms ?: 0.0) > TAPER_MIN_SOC

        return ChargingUi(
            powerKw = power, packV = packV, packA = packA, tapering = tapering,
            socAppPercent = socApp, socBmsPercent = socBms,
            chargedKwh = if (active) state.charge?.chargedKwh else session?.chargedKwh,
            elapsedMs = if (active) state.charge?.elapsedMs ?: 0L
                else session?.let { (it.endMs ?: last?.tsMs ?: it.startMs) - it.startMs } ?: 0L,
            timeToFullMin = if (active) state.charge?.timeToFullMin else null,
            tempAvg = tAvg, tempMin = tMin, tempMax = tMax, temps = if (active) state.temps else null,
            coldLevel = tMin?.let { WarningRules.coldBattery(it, charging = active, cold = t.coldC) } ?: Level.OK,
            insulationKOhm = ins, insulationLevel = ins?.let { WarningRules.insulation(it, low = t.insulationLowKOhm) } ?: Level.OK,
            cellMinMv = cMin, cellMaxMv = cMax, spreadMv = spread,
            spreadLevel = if (spread != null && cMax != null) WarningRules.spread(spread, cMax, socBms, charging = true, thresholdMv = t.spreadMv) else Level.OK,
            finished = !active,
        )
    }
}
