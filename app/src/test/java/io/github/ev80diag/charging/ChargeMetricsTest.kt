package io.github.ev80diag.charging

import io.github.ev80diag.TestLogs
import io.github.ev80diag.signals.Decoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeMetricsTest {
    private val t1233 = 1793273580_000L     // 2026-10-29 12:33:00 CET
    private val t1249 = 1793274540_000L     // 12:49:00
    private val done1251 = 1793274700_280L  // charger/BMS reports "complete"

    /** Replays a fixture into ChargeMetrics; returns the metrics when the clock reaches [until]. */
    private fun replayUntil(fixture: String, until: Long): ChargeMetrics {
        val m = ChargeMetrics()
        val d = Decoder()
        var v = 0.0; var a = 0.0; var e: Double? = null; var soc: Double? = null
        for (f in TestLogs.load(fixture)) {
            if (f.timestamp > until) break
            val dec = d.decode(f)
            dec["pack_v"]?.let { v = it }; dec["energy_kwh"]?.let { e = it }; dec["soc_bms"]?.let { soc = it }
            dec["pack_a"]?.let { a = it; m.onSample(f.timestamp, v, a, e, soc) }
        }
        return m
    }

    @Test
    fun constantCharge_chargedKwh() {
        val m = ChargeMetrics()
        for (i in 0..3000) m.onSample(i * 200L, 358.5, -80.0, null, null)    // 600 s
        assertEquals(28.68, m.powerKw, 0.01)
        assertEquals(4.78, m.chargedKwh, 0.05)
        assertEquals(600_000L, m.elapsedMs)
    }

    @Test
    fun largeGap_isNotIntegrated() {
        val m = ChargeMetrics()
        m.onSample(0, 358.5, -80.0, null, null)
        m.onSample(1_000, 358.5, -80.0, null, null)
        m.onSample(601_000, 358.5, -80.0, null, null)       // phone/adapter dropped for 10 min
        assertTrue("charged ${m.chargedKwh}", m.chargedKwh < 0.05)
    }

    @Test
    fun dischargeSamples_giveZeroPower() {
        val m = ChargeMetrics()
        m.onSample(0, 350.0, 20.0, null, null)               // positive current = discharge
        assertEquals(0.0, m.powerKw, 0.0001)
        assertEquals(0.0, m.chargedKwh, 0.0001)
    }

    @Test
    fun timeToFull_atConstantPower_isCloseToActual() {
        val m = replayUntil("et_terra54_start.log.gz", t1233)
        val eta = m.timeToFullMin()
        assertNotNull(eta)
        val actual = (done1251 - t1233) / 60_000.0              // 18.7 min
        assertTrue("eta $eta vs actual $actual", eta!! in actual * 0.85..actual * 1.15)
    }

    @Test
    fun timeToFull_whileTapering_isShort() {
        val m = replayUntil("et_terra54_end.log.gz", t1249)
        val eta = m.timeToFullMin()
        assertNotNull(eta)
        assertTrue("eta $eta (actual 2.7)", eta!! in 1.5..5.0)
    }

    @Test
    fun timeToFull_unknownWithoutEnergyOrPower() {
        assertNull(ChargeMetrics().timeToFullMin())
        val m = ChargeMetrics()
        m.onSample(0, 358.5, -0.2, 50.0, 90.0)               // current ~0: nothing meaningful to divide by
        assertNull(m.timeToFullMin())
    }
}
