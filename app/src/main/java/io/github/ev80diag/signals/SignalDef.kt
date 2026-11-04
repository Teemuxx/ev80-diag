package io.github.ev80diag.signals

enum class SignalStatus { VERIFIED, PROBABLE, UNKNOWN }

enum class SignalGroup { BATTERY, CHARGING, DRIVING, VEHICLE }

/**
 * One decoded CAN signal. value = (raw [& mask]) * [scale] + [offset].
 * [naRaw] is a raw value the BMS sends for "not available" (the signal is skipped).
 */
data class SignalDef(
    val key: String,
    val canId: Int,
    val startByte: Int,
    val length: Int,
    val unit: String,
    val group: SignalGroup,
    val status: SignalStatus,
    val nameRes: Int,
    val explanationRes: Int? = null,
    val littleEndian: Boolean = true,
    val signed: Boolean = false,
    val mask: Int? = null,
    val scale: Double = 1.0,
    val offset: Double = 0.0,
    val naRaw: Int? = null,
) {
    fun decode(data: ByteArray): Double? {
        if (data.size < startByte + length) return null
        var raw = 0L
        for (i in 0 until length) {
            val b = data[startByte + if (littleEndian) i else length - 1 - i].toLong() and 0xFF
            raw = raw or (b shl (8 * i))
        }
        if (signed && length < 8 && (raw and (1L shl (8 * length - 1))) != 0L) raw -= (1L shl (8 * length))
        if (naRaw != null && raw == naRaw.toLong()) return null
        val masked = if (mask != null) raw and mask.toLong() else raw
        return masked * scale + offset
    }

    /** Where the value comes from, e.g. "0x550 B0–1" or "0x322 B2". */
    fun sourceLabel(): String {
        val bytes = if (length == 1) "B$startByte" else "B$startByte–${startByte + length - 1}"
        return "0x%03X %s".format(canId, bytes)
    }
}
