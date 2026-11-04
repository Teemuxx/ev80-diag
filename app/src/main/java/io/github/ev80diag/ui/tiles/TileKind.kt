package io.github.ev80diag.ui.tiles

import io.github.ev80diag.R

enum class TileGroup(val labelRes: Int) {
    BATTERY(R.string.tile_group_battery), DRIVING(R.string.tile_group_driving),
    TEMPS_12V(R.string.tile_group_temps), CHARGING(R.string.tile_group_charging)
}

/** Every value a tile can show (spec §5.3), grouped as in the picker. */
enum class TileKind(val group: TileGroup, val labelRes: Int) {
    LOW_CELL(TileGroup.BATTERY, R.string.tile_low_cell),
    HIGH_CELL(TileGroup.BATTERY, R.string.tile_high_cell),
    SPREAD(TileGroup.BATTERY, R.string.tile_spread),
    CELLS_MIN_MAX(TileGroup.BATTERY, R.string.tile_cells_min_max),
    PACK_V(TileGroup.BATTERY, R.string.tile_pack_v),
    CURRENT(TileGroup.BATTERY, R.string.tile_current),
    POWER(TileGroup.BATTERY, R.string.live_power),
    SOC_APP(TileGroup.BATTERY, R.string.live_soc_app),
    SOC_BMS(TileGroup.BATTERY, R.string.tile_soc_bms),
    RANGE_APP(TileGroup.BATTERY, R.string.live_range_app),
    RANGE_DASH(TileGroup.BATTERY, R.string.tile_range_dash),
    SPEED(TileGroup.DRIVING, R.string.live_speed),
    ODOMETER(TileGroup.DRIVING, R.string.tile_odometer),
    GEAR_STATE(TileGroup.DRIVING, R.string.tile_gear_state),
    CONS_NOW(TileGroup.DRIVING, R.string.tile_cons_now),
    CONS_SINCE_CHARGE(TileGroup.DRIVING, R.string.tile_cons_since_charge),
    CONS_ALL_TIME(TileGroup.DRIVING, R.string.tile_cons_all_time),
    CONS_PERIOD(TileGroup.DRIVING, R.string.tile_cons_period),
    TEMP_BATT_AVG(TileGroup.TEMPS_12V, R.string.tile_temp_batt),
    TEMP_BATT_MIN_MAX(TileGroup.TEMPS_12V, R.string.tile_temp_batt_min_max),
    TEMP_MOTOR(TileGroup.TEMPS_12V, R.string.tile_temp_motor),
    V12(TileGroup.TEMPS_12V, R.string.tile_v12),
    CHARGE_POWER(TileGroup.CHARGING, R.string.charge_power),
    CHARGED_KWH(TileGroup.CHARGING, R.string.charge_charged),
    INSULATION(TileGroup.CHARGING, R.string.charge_insulation),
}

/** One tile; [fromMs]/[toMs] only for [TileKind.CONS_PERIOD]. */
data class TileSpec(val kind: TileKind, val fromMs: Long? = null, val toMs: Long? = null)
