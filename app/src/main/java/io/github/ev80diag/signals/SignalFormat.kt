package io.github.ev80diag.signals

import android.content.Context
import io.github.ev80diag.R
import java.util.Locale

/** Plain text for a decoded value: gear letters, BMS state names, numbers with sensible decimals and unit. */
object SignalFormat {
    fun text(ctx: Context, key: String, value: Double): String = when (key) {
        "gear" -> gearLetter(value) ?: "%.0f".format(value)
        "bms_state" -> bmsStateRes(value.toInt())?.let { ctx.getString(it) } ?: "0x%02X".format(value.toInt())
        "hv_flags" -> "0x%02X".format(value.toInt())
        "insulation_fault" -> ctx.getString(if (value != 0.0) R.string.value_yes else R.string.value_no)
        else -> {
            val unit = SignalTable.all.firstOrNull { it.key == key }?.unit.orEmpty()
            val number = String.format(Locale.getDefault(), "%.${decimals(unit)}f", value)
            if (unit.isEmpty()) number else "$number $unit"
        }
    }

    fun gearLetter(v: Double): String? = when (v.toInt()) { 5 -> "D"; 6 -> "N"; 7 -> "R"; 8 -> "P"; else -> null }

    fun decimals(unit: String): Int = when (unit) {
        "V", "A", "kW", "kWh", "%", "km" -> 1
        else -> 0
    }

    private fun bmsStateRes(v: Int): Int? = when (v) {
        0x06 -> R.string.bms_state_plugged
        0x0D -> R.string.bms_state_vehicle_on
        0x12 -> R.string.bms_state_bms_stop
        0x15 -> R.string.bms_state_ac
        0x16 -> R.string.bms_state_dc
        0x1A -> R.string.bms_state_dc_complete
        else -> null
    }
}
