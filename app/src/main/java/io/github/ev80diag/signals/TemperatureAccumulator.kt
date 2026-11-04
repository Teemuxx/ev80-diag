package io.github.ev80diag.signals

import io.github.ev80diag.can.CanFrame

/**
 * 0x520: the 18 battery temperature sensors in 4 frames. B0 = 1, B1 = 0x12 (18 sensors),
 * B2 = group 1..4, B3..B7 = five sensors per group (raw - 40 = °C, 0xFF = unused;
 * group 4 carries only three).
 * Returns the 18 values once all four groups have arrived since the last result.
 */
class TemperatureAccumulator {
    private val temps = IntArray(SENSORS)
    private val seen = BooleanArray(GROUPS)

    fun process(frame: CanFrame): IntArray? {
        if (frame.id != 0x520 || frame.data.size < 4) return null
        val group = frame.data[2].toInt() and 0xFF
        if (group !in 1..GROUPS) return null
        for (i in 3 until minOf(8, frame.data.size)) {
            val slot = (group - 1) * 5 + (i - 3)
            val raw = frame.data[i].toInt() and 0xFF
            if (slot < SENSORS && raw != 0xFF) temps[slot] = raw - 40
        }
        seen[group - 1] = true
        if (seen.all { it }) {
            seen.fill(false)
            return temps.copyOf()
        }
        return null
    }

    fun reset() { temps.fill(0); seen.fill(false) }

    companion object {
        const val SENSORS = 18
        const val GROUPS = 4
    }
}
