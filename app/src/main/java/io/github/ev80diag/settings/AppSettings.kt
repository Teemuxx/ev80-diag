package io.github.ev80diag.settings

import android.content.Context
import android.content.SharedPreferences

enum class RecordingMode { AUTOMATIC, WHILE_OPEN }
enum class FullCanLogging { ALWAYS, CHARGING_ONLY }
enum class LanguageChoice { SYSTEM, EN, DE }
enum class LastSource { NONE, BLUETOOTH, USB }
enum class ThemeChoice { DARK, LIGHT, SYSTEM }

/** A number the user types in Settings: its default and the values accepted. */
data class NumberSpec(val default: Int, val range: IntRange) {
    /** The typed value, or null while it is not (yet) a whole number in [range]. */
    fun parse(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in range }
}

/** User settings (defaults from the spec §11). */
class AppSettings(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("ev80_settings", Context.MODE_PRIVATE))

    private inline fun <reified E : Enum<E>> enum(key: String, default: E): E =
        prefs.getString(key, null)?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    var recordingMode: RecordingMode
        get() = enum("recording_mode", RecordingMode.WHILE_OPEN)
        set(v) = prefs.edit().putString("recording_mode", v.name).apply()
    var fullCanLogging: FullCanLogging
        get() = enum("full_can_logging", FullCanLogging.CHARGING_ONLY)
        set(v) = prefs.edit().putString("full_can_logging", v.name).apply()
    var language: LanguageChoice
        get() = enum("language", LanguageChoice.SYSTEM)
        set(v) = prefs.edit().putString("language", v.name).apply()
    var theme: ThemeChoice
        get() = enum("theme", ThemeChoice.DARK)
        set(v) = prefs.edit().putString("theme", v.name).apply()
    var lastSource: LastSource
        get() = enum("last_source", LastSource.NONE)
        set(v) = prefs.edit().putString("last_source", v.name).apply()
    var lastBluetoothAddress: String?
        get() = prefs.getString("last_bt_address", null)
        set(v) = prefs.edit().putString("last_bt_address", v).apply()
    var driveRawRetentionDays: Int
        get() = prefs.getInt("drive_raw_retention_days", DRIVE_RETENTION_DAYS.default)
        set(v) = prefs.edit().putInt("drive_raw_retention_days", v).apply()
    var insulationLowKOhm: Int
        get() = prefs.getInt("insulation_low_kohm", INSULATION_LOW_KOHM.default)
        set(v) = prefs.edit().putInt("insulation_low_kohm", v).apply()
    var coldBatteryC: Int
        get() = prefs.getInt("cold_battery_c", COLD_BATTERY_C.default)
        set(v) = prefs.edit().putInt("cold_battery_c", v).apply()
    var spreadWarnMv: Int
        get() = prefs.getInt("spread_warn_mv", SPREAD_WARN_MV.default)
        set(v) = prefs.edit().putInt("spread_warn_mv", v).apply()
    var lowCellMv: Int
        get() = prefs.getInt("low_cell_mv", LOW_CELL_MV.default)
        set(v) = prefs.edit().putInt("low_cell_mv", v).apply()
    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_screen_on", true)
        set(v) = prefs.edit().putBoolean("keep_screen_on", v).apply()
    var openChargingOnStart: Boolean
        get() = prefs.getBoolean("open_charging_on_start", true)
        set(v) = prefs.edit().putBoolean("open_charging_on_start", v).apply()

    companion object {
        val DRIVE_RETENTION_DAYS = NumberSpec(90, 7..365)
        val SPREAD_WARN_MV = NumberSpec(80, 30..300)
        /** Above the 36 kΩ at which the BMS stops a charge, so the amber zone never vanishes. */
        val INSULATION_LOW_KOHM = NumberSpec(180, 50..1000)
        val COLD_BATTERY_C = NumberSpec(3, -10..15)
        /** Lowest cell: amber below this, red 0.1 V lower (spec §10/§11). */
        val LOW_CELL_MV = NumberSpec(3100, 2800..3400)
    }
}
