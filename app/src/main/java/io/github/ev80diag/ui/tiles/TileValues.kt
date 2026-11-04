package io.github.ev80diag.ui.tiles

import io.github.ev80diag.R
import io.github.ev80diag.data.store.DriveTotals
import io.github.ev80diag.pipeline.RecorderState
import io.github.ev80diag.signals.SignalFormat
import io.github.ev80diag.ui.components.labelRes
import io.github.ev80diag.ui.screen.LiveUi
import java.util.Locale

/** What a tile needs beyond the live state: stored drive totals (the open drive is added from [state]). */
data class TileContext(
    val live: LiveUi,
    val state: RecorderState,
    val sinceCharge: DriveTotals,
    val allTime: DriveTotals,
    /** Totals per slot for CONS_PERIOD tiles. */
    val periods: Map<Int, DriveTotals>,
    val locale: Locale = Locale.getDefault(),
)

/** A small second line: a string resource with one argument (or none). */
data class TileSub(val res: Int, val arg: String? = null)

data class TileText(val value: String, val sub: TileSub?)

object TileValues {
    private const val DASH = "–"
    private const val MIN_KM = 1.0

    fun of(spec: TileSpec, slot: Int, c: TileContext): TileText {
        val l = c.live
        val d = c.state.decoded
        fun f(v: Double?, digits: Int, unit: String = ""): String =
            v?.let { String.format(c.locale, "%.${digits}f", it) + if (unit.isEmpty()) "" else " $unit" } ?: DASH
        fun volts(mv: Int?) = mv?.let { String.format(c.locale, "%.3f", it / 1000.0) }
        fun cons(t: DriveTotals): TileText {
            val v = if (t.km >= MIN_KM) t.kwh / t.km * 100.0 else null
            return TileText(f(v, 1), TileSub(R.string.tile_cons_km, String.format(c.locale, "%.0f", t.km)))
        }
        val open = c.state.drive?.let { DriveTotals(it.km, it.kwh) } ?: DriveTotals(0.0, 0.0)
        fun plusOpen(t: DriveTotals) = DriveTotals(t.km + open.km, t.kwh + open.kwh)
        return when (spec.kind) {
            TileKind.LOW_CELL -> TileText(volts(l.minCellMv)?.let { "$it V" } ?: DASH, l.minCellNo?.let { TileSub(R.string.tile_cell_no, "$it") })
            TileKind.HIGH_CELL -> TileText(volts(l.maxCellMv)?.let { "$it V" } ?: DASH, l.maxCellNo?.let { TileSub(R.string.tile_cell_no, "$it") })
            TileKind.SPREAD -> TileText(l.spreadMv?.let { "$it mV" } ?: DASH, null)
            TileKind.CELLS_MIN_MAX -> TileText(
                if (l.minCellMv != null && l.maxCellMv != null) "${volts(l.minCellMv)} / ${volts(l.maxCellMv)}" else DASH,
                l.spreadMv?.let { TileSub(R.string.tile_spread_mv, "$it") })
            TileKind.PACK_V -> TileText(f(l.packV, 1, "V"), null)
            TileKind.CURRENT -> TileText(f(l.packA, 0, "A"), null)
            TileKind.POWER -> TileText(f(l.powerKw, 1, "kW"), if ((l.powerKw ?: 0.0) < -0.05) TileSub(R.string.live_regen) else null)
            TileKind.SOC_APP -> TileText(f(l.socApp, 0, "%"), l.socBms?.let { TileSub(R.string.live_soc_bms, String.format(c.locale, "%.1f", it)) })
            TileKind.SOC_BMS -> TileText(f(l.socBms, 1, "%"), null)
            TileKind.RANGE_APP -> TileText(f(l.rangeAppKm, 0, "km"), l.rangeDashKm?.let { TileSub(R.string.live_range_dash, String.format(c.locale, "%.0f", it)) })
            TileKind.RANGE_DASH -> TileText(f(l.rangeDashKm, 0, "km"), null)
            TileKind.SPEED -> TileText(f(l.speedKmh, 0, "km/h"), null)
            TileKind.ODOMETER -> TileText(f(d["odo_km"], 0, "km"), null)
            TileKind.GEAR_STATE -> TileText(d["gear"]?.let { SignalFormat.gearLetter(it) } ?: DASH, TileSub(c.state.van.power.labelRes()))
            TileKind.CONS_NOW -> TileText(f(l.kwhPer100Now, 1), TileSub(R.string.live_consumption_sub))
            TileKind.CONS_SINCE_CHARGE -> cons(plusOpen(c.sinceCharge))
            TileKind.CONS_ALL_TIME -> cons(plusOpen(c.allTime))
            TileKind.CONS_PERIOD -> cons(c.periods[slot] ?: DriveTotals(0.0, 0.0))
            TileKind.TEMP_BATT_AVG -> TileText(f(l.tempBatt, 0, "°C"), null)
            TileKind.TEMP_BATT_MIN_MAX -> TileText(
                if (d["temp_min"] != null && d["temp_max"] != null) "${f(d["temp_min"], 0)} / ${f(d["temp_max"], 0)} °C" else DASH, null)
            TileKind.V12 -> TileText(f(l.v12, 1, "V"), null)
            TileKind.CHARGE_POWER -> TileText(f(c.state.charge?.powerKw, 1, "kW"), null)
            TileKind.CHARGED_KWH -> TileText(f(c.state.charge?.chargedKwh, 1, "kWh"), null)
            TileKind.INSULATION -> TileText(d["insulation_kohm"]?.takeIf { it < 10_000.0 }?.let {
                if (it >= 1000) String.format(c.locale, "%.2f MΩ", it / 1000) else String.format(c.locale, "%.0f kΩ", it) } ?: DASH, null)
        }
    }
}
