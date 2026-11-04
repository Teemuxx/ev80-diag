package io.github.ev80diag.signals

import io.github.ev80diag.R
import io.github.ev80diag.signals.SignalGroup.*
import io.github.ev80diag.signals.SignalStatus.*

/**
 * The decoded EV80 signals. Source of truth: /claude.md "merged signal table".
 * Cell voltages (0x510) and the 18 battery temperatures (0x520) are multi-frame and handled
 * by CellVoltageAccumulator / TemperatureAccumulator in [Decoder]. Cell numbers from 0x530
 * are computed there as well.
 */
object SignalTable {
    val all: List<SignalDef> = listOf(
        SignalDef("pack_v", 0x550, 0, 2, "V", BATTERY, VERIFIED, R.string.sig_pack_v, scale = 0.1),
        SignalDef("pack_a", 0x550, 2, 2, "A", BATTERY, VERIFIED, R.string.sig_pack_a, scale = 0.1, offset = -1000.0),
        SignalDef("energy_kwh", 0x550, 4, 2, "kWh", BATTERY, VERIFIED, R.string.sig_energy_kwh, R.string.sig_energy_kwh_expl, scale = 0.1),
        SignalDef("soc_bms", 0x322, 2, 1, "%", BATTERY, VERIFIED, R.string.sig_soc_bms, R.string.sig_soc_bms_expl, scale = 0.5),
        SignalDef("temp_avg", 0x322, 5, 1, "°C", BATTERY, VERIFIED, R.string.sig_temp_avg, offset = -40.0),
        SignalDef("temp_min", 0x323, 1, 1, "°C", BATTERY, VERIFIED, R.string.sig_temp_min, offset = -40.0),
        SignalDef("temp_max", 0x323, 2, 1, "°C", BATTERY, VERIFIED, R.string.sig_temp_max, offset = -40.0),
        SignalDef("cell_max_mv", 0x530, 2, 2, "mV", BATTERY, VERIFIED, R.string.sig_cell_max_mv),
        SignalDef("cell_min_mv", 0x530, 6, 2, "mV", BATTERY, VERIFIED, R.string.sig_cell_min_mv),
        SignalDef("insulation_kohm", 0x321, 0, 2, "kΩ", CHARGING, VERIFIED, R.string.sig_insulation_kohm, R.string.sig_insulation_kohm_expl),
        SignalDef("insulation_fault", 0x321, 5, 1, "", CHARGING, PROBABLE, R.string.sig_insulation_fault, mask = 0x01),
        SignalDef("bms_state", 0x200, 0, 1, "", CHARGING, VERIFIED, R.string.sig_bms_state, R.string.sig_bms_state_expl),
        SignalDef("hv_flags", 0x580, 1, 1, "", BATTERY, VERIFIED, R.string.sig_hv_flags, R.string.sig_hv_flags_expl),
        SignalDef("charge_counter", 0x590, 5, 2, "", CHARGING, VERIFIED, R.string.sig_charge_counter, R.string.sig_charge_counter_expl),
        SignalDef("disch_limit_kw", 0x580, 4, 2, "kW", BATTERY, PROBABLE, R.string.sig_disch_limit_kw, scale = 0.1, offset = -100.0, naRaw = 0x03FF),
        SignalDef("regen_limit_kw", 0x580, 6, 2, "kW", BATTERY, VERIFIED, R.string.sig_regen_limit_kw, scale = 0.1, offset = -100.0, naRaw = 0x03FF),
        SignalDef("charge_current_600", 0x600, 2, 2, "A", CHARGING, VERIFIED, R.string.sig_charge_current_600, scale = 0.1, offset = -400.0),
        SignalDef("charge_minutes_600", 0x600, 4, 1, "min", CHARGING, VERIFIED, R.string.sig_charge_minutes_600),
        SignalDef("dc_link_v", 0x301, 0, 2, "V", VEHICLE, VERIFIED, R.string.sig_dc_link_v, R.string.sig_dc_link_v_expl, scale = 0.1),
        SignalDef("v12", 0x303, 2, 1, "V", VEHICLE, VERIFIED, R.string.sig_v12, R.string.sig_v12_expl, scale = 0.125),
        SignalDef("speed_kmh", 0x380, 0, 2, "km/h", DRIVING, VERIFIED, R.string.sig_speed_kmh, scale = 0.01),
        SignalDef("odo_km", 0x380, 2, 3, "km", DRIVING, VERIFIED, R.string.sig_odo_km, scale = 0.1),
        SignalDef("gear", 0x230, 0, 1, "", DRIVING, VERIFIED, R.string.sig_gear, R.string.sig_gear_expl),
        SignalDef("motor_temp", 0x302, 3, 1, "°C", DRIVING, PROBABLE, R.string.sig_motor_temp, R.string.sig_motor_temp_expl, offset = -40.0),
        SignalDef("inverter_temp", 0x302, 4, 1, "°C", DRIVING, PROBABLE, R.string.sig_inverter_temp, R.string.sig_inverter_temp_expl, offset = -40.0),
        SignalDef("range_dash_km", 0x08A, 1, 1, "km", DRIVING, VERIFIED, R.string.sig_range_dash_km),
    )

    private val byCanId: Map<Int, List<SignalDef>> = all.groupBy { it.canId }

    fun byId(id: Int): List<SignalDef> = byCanId[id].orEmpty()
}
