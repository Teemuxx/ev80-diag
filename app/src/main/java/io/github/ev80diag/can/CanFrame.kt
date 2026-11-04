package io.github.ev80diag.can

data class CanFrame(
    val id: Int,
    val data: ByteArray,
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CanFrame) return false
        return id == other.id && data.contentEquals(other.data) && timestamp == other.timestamp
    }

    override fun hashCode(): Int = 31 * (31 * id + data.contentHashCode()) + timestamp.hashCode()
}

data class PackData(
    val voltageV: Float,
    val currentA: Float,
    val timestamp: Long = System.currentTimeMillis()
)

data class CellVoltageGroup(
    val groupIndex: Int,
    val voltages: List<Int> // mV, up to 3 values (0xFFFF filtered out)
)

data class TemperatureData(
    val groupIndex: Int,
    val temperatures: List<Int> // °C (raw - 40 offset applied)
)

/**
 * 0x322 State of Charge. [b2Raw] / 2 is the BMS SOC (matches the EVCC's `ress soc`).
 * [b1Raw] is NOT the SOC — its meaning is unknown, kept for diagnostics only.
 */
data class BmsSoc(
    val b1Raw: Int,
    val b2Raw: Int
) {
    val socPercent: Float get() = b2Raw / 2f
}

/** 0x530 Cell extremes: voltages in mV and 1-based cell indices (0 = unknown). */
data class CellExtremes(
    val maxMv: Int,
    val minMv: Int,
    val maxCell: Int,
    val minCell: Int
)

data class OdometerData(
    val odometerKm: Float,
    val speedRaw: Int
)

data class CellVoltageSnapshot(
    val voltages: IntArray, // 104 cells in mV
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CellVoltageSnapshot) return false
        return voltages.contentEquals(other.voltages) && timestamp == other.timestamp
    }

    override fun hashCode(): Int = 31 * voltages.contentHashCode() + timestamp.hashCode()
}

