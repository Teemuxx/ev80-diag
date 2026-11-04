package io.github.ev80diag.can

/**
 * Accumulates 0x510 cell voltage groups into complete 104-cell snapshots.
 *
 * Groups cycle through indices 7, 11, 15, ..., 143 (step 4, 35 groups total).
 * Group ordinal = (groupIndex - 7) / 4, cell base = ordinal * 3.
 * Emits a snapshot only when all 35 groups have been received.
 * If a packet is lost, we wait for the next cycle of that group.
 */
class CellVoltageAccumulator {

    private val voltages = IntArray(104)
    private val received = BooleanArray(35)

    fun processGroup(group: CellVoltageGroup): CellVoltageSnapshot? {
        val groupOrdinal = (group.groupIndex - 7) / 4
        if (groupOrdinal < 0 || groupOrdinal >= 35) return null

        val cellStart = groupOrdinal * 3
        for (i in group.voltages.indices) {
            if (cellStart + i < 104) {
                voltages[cellStart + i] = group.voltages[i]
            }
        }
        received[groupOrdinal] = true

        if (received.all { it }) {
            val snapshot = CellVoltageSnapshot(voltages.copyOf())
            received.fill(false)
            return snapshot
        }
        return null
    }

    fun reset() {
        voltages.fill(0)
        received.fill(false)
    }
}

