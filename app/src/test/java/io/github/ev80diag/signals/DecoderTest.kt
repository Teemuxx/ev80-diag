package io.github.ev80diag.signals

import io.github.ev80diag.TestLogs
import io.github.ev80diag.TestLogs.frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderTest {

    private fun decode(id: Int, hex: String, ts: Long = 100_000L) =
        Decoder().decode(frame(id, hex, ts))

    @Test
    fun pack_550() {
        val m = decode(0x550, "010EF6232D020000")
        assertEquals(358.5, m["pack_v"]!!, 0.001)
        assertEquals(-79.4, m["pack_a"]!!, 0.001)
        assertEquals(55.7, m["energy_kwh"]!!, 0.001)
    }

    @Test
    fun soc_322() {
        val d = Decoder()
        d.decode(frame(0x322, "4858590000000000", 0))   // warm-up frame: the wake filter drops the first second
        assertEquals(100.5, d.decode(frame(0x322, "4864C900", 2_000))["soc_bms"]!!, 0.001)
    }

    @Test
    fun soc_322_wakeBurstFiltered() {
        val d = Decoder()
        d.decode(frame(0x322, "485859C900000000", 1_000))           // first frame ever starts a burst
        assertNull(d.decode(frame(0x322, "4858590000000000", 1_500))["soc_bms"])
        assertNull(d.decode(frame(0x322, "4858590000000000", 20_000))["soc_bms"]) // after a > 5 s gap
        assertEquals(44.5, d.decode(frame(0x322, "4858590000000000", 21_100))["soc_bms"]!!, 0.001)
    }

    @Test
    fun soc_322_rejectsWrongMarkerAndRange() {
        assertTrue(decode(0x322, "4758590000000000").isEmpty())
        assertTrue(decode(0x322, "4858FF0000000000").isEmpty())
    }

    @Test
    fun insulation_321() {
        assertEquals(1930.0, decode(0x321, "8A07000000000000")["insulation_kohm"]!!, 0.001)
        assertEquals(10000.0, decode(0x321, "1027000000000000")["insulation_kohm"]!!, 0.001)
        assertEquals(1.0, decode(0x321, "8A07000000010000")["insulation_fault"]!!, 0.001)
        assertEquals(0.0, decode(0x321, "8A07000000000000")["insulation_fault"]!!, 0.001)
    }

    @Test
    fun v12_303() {
        assertEquals(13.875, decode(0x303, "F0006F0070053700")["v12"]!!, 0.001)
    }

    @Test
    fun dcLink_301() {
        assertEquals(344.2, decode(0x301, "720D000000000000")["dc_link_v"]!!, 0.001)
    }

    @Test
    fun hvFlags_580() {
        assertEquals(13.0, decode(0x580, "000D000000000000")["hv_flags"]!!, 0.001)
    }

    @Test
    fun limits_580() {
        // raw 0x04B0 = 1200 -> 1200/10 - 100 = 20 kW
        assertEquals(20.0, decode(0x580, "00050000B0040000")["disch_limit_kw"]!!, 0.001)
    }

    @Test
    fun counter_590() {
        assertEquals(6821.0, decode(0x590, "0000000000A51A00")["charge_counter"]!!, 0.001)
    }

    @Test
    fun charge600() {
        // B2-3 raw 4800 -> 4800*0.1-400 = 80 A ; B4 = 12 minutes
        val m = decode(0x600, "0000C0120C000000")
        assertEquals(80.0, m["charge_current_600"]!!, 0.001)
        assertEquals(12.0, m["charge_minutes_600"]!!, 0.001)
    }

    @Test
    fun odoAndSpeed_380() {
        val m = decode(0x380, "0000978F07000000")
        assertEquals(49551.1, m["odo_km"]!!, 0.001)
        assertEquals(0.0, m["speed_kmh"]!!, 0.001)
        assertEquals(62.3, decode(0x380, "5618978F07000000")["speed_kmh"]!!, 0.001)
    }

    @Test
    fun gear_230_and_bmsState_200_and_range_08A() {
        assertEquals(8.0, decode(0x230, "0800000000000000")["gear"]!!, 0.001)
        assertEquals(22.0, decode(0x200, "1600006404010000")["bms_state"]!!, 0.001)
        assertEquals(127.0, decode(0x08A, "097F000000000000")["range_dash_km"]!!, 0.001)
    }

    @Test
    fun temperatures_323_and_322() {
        val m = decode(0x323, "1233342323000000")
        assertEquals(11.0, m["temp_min"]!!, 0.001)
        assertEquals(12.0, m["temp_max"]!!, 0.001)
    }

    @Test
    fun cellExtremes_530_withCellNumbers() {
        // max 3759 mV (0x0EAF) in bank 1 cell 53 (0x35); min 3414 mV (0x0D56) in bank 1 cell 46 (0x2E)
        val m = decode(0x530, "0135AF0E012E560D")
        assertEquals(3759.0, m["cell_max_mv"]!!, 0.001)
        assertEquals(3414.0, m["cell_min_mv"]!!, 0.001)
        assertEquals(53.0, m["cell_max_no"]!!, 0.001)
        assertEquals(46.0, m["cell_min_no"]!!, 0.001)
        // bank 2 cell 5 = cell 65
        assertEquals(65.0, decode(0x530, "0205AF0E012E560D")["cell_max_no"]!!, 0.001)
    }

    @Test
    fun temps_520_fromRealFixture_returns18Values() {
        val d = Decoder()
        var last: IntArray? = null
        for (f in TestLogs.load("et_terra54_end.log.gz")) {
            d.decode(f)
            d.latestTemps?.let { last = it }
        }
        assertEquals(
            listOf(13, 12, 11, 12, 12, 12, 11, 12, 12, 11, 11, 11, 12, 11, 11, 11, 11, 12),
            last!!.toList()
        )
    }

    @Test
    fun temps_520_incompleteGroupSet_returnsNull() {
        val acc = TemperatureAccumulator()
        assertNull(acc.process(frame(0x520, "0112013332323232")))
        assertNull(acc.process(frame(0x520, "0112023232323232")))
    }

    @Test
    fun cells_510_completeSetFromFixture() {
        val d = Decoder()
        var snap: IntArray? = null
        for (f in TestLogs.load("et_terra54_end.log.gz")) {
            d.decode(f)
            d.latestCells?.let { snap = it.voltages }
        }
        val s = snap!!
        assertEquals(104, s.size)
        assertTrue("max cell is #53 near 3.7 V", s.indices.maxByOrNull { s[it] } == 52 && s[52] > 3700)
    }

    @Test
    fun everyTableEntry_hasStringResources() {
        assertTrue(SignalTable.all.isNotEmpty())
        assertTrue(SignalTable.all.all { it.nameRes != 0 })
        assertEquals(SignalTable.all.size, SignalTable.all.map { it.key }.toSet().size)
    }

    @Test
    fun unknownId_returnsEmpty() {
        assertFalse(decode(0x7FF, "0000000000000000").isNotEmpty())
        assertNotNull(Decoder().cells)
    }

    @Test
    fun motorTemp_302() {
        val m = decode(0x302, "3635364C4C6403FF")
        assertEquals(36.0, m["motor_temp"]!!, 0.001)
        assertEquals(36.0, m["inverter_temp"]!!, 0.001)
    }

    @Test
    fun sourceLabel_formats() {
        fun label(key: String) = SignalTable.all.first { it.key == key }.sourceLabel()
        assertEquals("0x550 B0–1", label("pack_v"))
        assertEquals("0x322 B2", label("soc_bms"))
        assertEquals("0x380 B2–4", label("odo_km"))
    }

    @Test
    fun lastRaw_keepsLatestPayload() {
        val d = Decoder()
        d.decode(frame(0x550, "010EF6232D020000"))
        d.decode(frame(0x550, "020EF6232D020000"))
        d.decode(frame(0x7FF, "01"))
        assertEquals("020EF6232D020000", d.lastRaw[0x550]!!.joinToString("") { "%02X".format(it) })
        assertNull(d.lastRaw[0x7FF])
    }
}
