package io.github.ev80diag.signals

import io.github.ev80diag.can.CanFrame
import io.github.ev80diag.can.CellVoltageAccumulator
import io.github.ev80diag.can.CellVoltageGroup
import io.github.ev80diag.can.CellVoltageSnapshot
import io.github.ev80diag.can.SocFrameFilter

/**
 * Turns frames into named values using [SignalTable]. One instance per recording
 * (it holds the 0x322 wake filter and the multi-frame accumulators).
 */
class Decoder {
    val cells = CellVoltageAccumulator()
    val temps = TemperatureAccumulator()
    private val socFilter = SocFrameFilter()

    /** Latest complete 104-cell snapshot / 18 temperatures, null until the first set completes. */
    var latestCells: CellVoltageSnapshot? = null
        private set
    var latestTemps: IntArray? = null
        private set

    private val _lastRaw = HashMap<Int, ByteArray>()

    /** Latest payload of every ID that has rows in [SignalTable] (copies; for the Details "hex" view). */
    val lastRaw: Map<Int, ByteArray> get() = _lastRaw

    private val _lastSeen = HashMap<Int, Long>()

    /** Frame time each signal ID was last seen (FreshValues drops values of IDs gone quiet). */
    val lastSeen: Map<Int, Long> get() = _lastSeen

    fun decode(frame: CanFrame): Map<String, Double> {
        if (SignalTable.byId(frame.id).isNotEmpty()) {
            _lastRaw[frame.id] = frame.data.copyOf()
            _lastSeen[frame.id] = frame.timestamp
        }
        when (frame.id) {
            0x510 -> { onCellGroup(frame); return emptyMap() }
            0x520 -> { temps.process(frame)?.let { latestTemps = it }; return emptyMap() }
            0x322 -> if (!acceptSoc(frame)) return emptyMap()
        }
        val out = HashMap<String, Double>()
        for (def in SignalTable.byId(frame.id)) def.decode(frame.data)?.let { out[def.key] = it }
        if (frame.id == 0x530 && frame.data.size >= 8) {
            cellNumber(frame.data[0].toInt() and 0xFF, frame.data[1].toInt() and 0xFF)?.let { out["cell_max_no"] = it.toDouble() }
            cellNumber(frame.data[4].toInt() and 0xFF, frame.data[5].toInt() and 0xFF)?.let { out["cell_min_no"] = it.toDouble() }
        }
        return out
    }

    private fun acceptSoc(frame: CanFrame): Boolean {
        if (frame.data.size < 3 || (frame.data[0].toInt() and 0xFF) != 0x48) return false
        if ((frame.data[2].toInt() and 0xFF) > MAX_SOC_B2_RAW) return false
        return socFilter.accept(frame.timestamp)
    }

    private fun onCellGroup(frame: CanFrame) {
        val d = frame.data
        if (d.size < 4 || (d[0].toInt() and 0xFF) != 0x41) return
        val volts = ArrayList<Int>(3)
        for (i in 0 until 3) {
            val off = 2 + i * 2
            if (off + 1 >= d.size) break
            val v = (d[off].toInt() and 0xFF) or ((d[off + 1].toInt() and 0xFF) shl 8)
            if (v in 2000..5000) volts.add(v)
        }
        if (volts.isEmpty()) return
        cells.processGroup(CellVoltageGroup(d[1].toInt() and 0xFF, volts))?.let { latestCells = it }
    }

    /** Bank 1 = cells 1-60, bank 2 = cells 61-104: cell = (bank - 1) * 60 + n. */
    private fun cellNumber(bank: Int, n: Int): Int? =
        if (bank in 1..2 && n in 1..60) (bank - 1) * 60 + n else null

    private companion object { const val MAX_SOC_B2_RAW = 201 }
}
