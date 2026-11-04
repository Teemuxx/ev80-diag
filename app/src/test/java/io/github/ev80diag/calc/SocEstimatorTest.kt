package io.github.ev80diag.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocEstimatorTest {

    private val t0 = 1_700_000_000_000L

    private fun calibrated(soc: Float, cap: Float = 160f) =
        SocEstimator(SocState(capacityAh = cap)).also { it.setManual(t0, soc) }

    @Test
    fun uncalibrated_reportsNoSoc() {
        val e = SocEstimator()
        e.onCurrent(t0, 50f)
        val s = e.snapshot()
        assertNull(s.socPercent)
        assertEquals(SocConfidence.UNCALIBRATED, s.confidence)
    }

    @Test
    fun coulombCounting_discharge_and_charge() {
        val e = calibrated(80f)
        // 100 A for 1 h (sampled each second) = 100 Ah = 62.5 % of 160 Ah
        for (i in 0..3600) e.onCurrent(t0 + i * 1000L, 100f)
        assertEquals(80f - 62.5f, e.snapshot().socPercent!!, 0.1f)
        // charge it back: -50 A for 1 h = 50 Ah = 31.25 %
        for (i in 3601..7200) e.onCurrent(t0 + i * 1000L, -50f)
        assertEquals(80f - 62.5f + 31.25f, e.snapshot().socPercent!!, 0.2f)
        assertEquals(SocConfidence.OK, e.snapshot().confidence)
    }

    @Test
    fun manualSet_doesNotMarkStale() {
        val e = calibrated(50f)
        e.onCurrent(t0 + 500, 10f)
        e.onCurrent(t0 + 1000, 10f)
        assertEquals(SocConfidence.OK, e.snapshot().confidence)
    }

    @Test
    fun gap_marksStale_andDoesNotIntegrateAcrossIt() {
        val e = calibrated(50f)
        e.onCurrent(t0 + 1_000, 100f)
        e.onCurrent(t0 + 2_000, 100f)
        val before = e.snapshot().socPercent!!
        e.onCurrent(t0 + 2_000 + 3_600_000L, 100f) // 1 h offline
        val after = e.snapshot()
        assertEquals(SocConfidence.STALE, after.confidence)
        assertEquals(before, after.socPercent!!, 0.001f)
    }

    @Test
    fun restoredState_isStaleAfterLongOffline_andNotIntegrated() {
        val saved = SocState(calibrated = true, consumedAh = 40f, lastUpdateTs = t0)
        val e = SocEstimator(saved)
        e.restore(saved)
        e.onCurrent(t0 + 6 * 3_600_000L, 20f) // app restarted 6 h later
        val s = e.snapshot()
        assertEquals(SocConfidence.STALE, s.confidence)
        assertEquals((168f - 40f) / 168f * 100f, s.socPercent!!, 0.01f) // default capacity 168 Ah
        assertTrue(s.remainingAh!! > 0)
    }

    @Test
    fun fullAnchor_requiresHoldAndLowCurrent_andLatches() {
        val e = calibrated(40f)
        // high current: no anchor even with a high cell (DC charge overpotential)
        assertEquals(false, e.onCellExtremes(t0 + 1_000, 3600, 3400, -78f))
        // low current but not yet held
        assertEquals(false, e.onCellExtremes(t0 + 2_000, 3560, 3400, -4f))
        assertEquals(false, e.onCellExtremes(t0 + 5_000, 3560, 3400, -4f))
        // held >= 5 s
        assertEquals(true, e.onCellExtremes(t0 + 7_000, 3560, 3400, -4f))
        assertEquals(100f, e.snapshot().socPercent!!, 0.001f)
        assertEquals(AnchorKind.FULL, e.snapshot().anchorKind)
        // latched: no repeated anchoring while the condition persists
        assertEquals(false, e.onCellExtremes(t0 + 9_000, 3560, 3400, -4f))
        // condition released then re-armed
        assertEquals(false, e.onCellExtremes(t0 + 20_000, 3450, 3400, 0f))
        assertEquals(false, e.onCellExtremes(t0 + 21_000, 3560, 3400, 0f))
        assertEquals(true, e.onCellExtremes(t0 + 27_000, 3560, 3400, 0f))
    }

    @Test
    fun lowVoltageAnchor_pinsSocAndIsRateLimited() {
        val e = calibrated(60f)
        // 100 A load: min cell 3000 mV -> corrected 3060 mV -> (3060-2900)/300*20 = 10.67 %
        assertEquals(true, e.onCellExtremes(t0 + 100_000, 3300, 3000, 100f))
        assertEquals(10.67f, e.snapshot().socPercent!!, 0.05f)
        assertEquals(AnchorKind.LOW_VOLTAGE, e.snapshot().anchorKind)
        assertEquals(false, e.onCellExtremes(t0 + 110_000, 3300, 2990, 100f)) // < 60 s later
        assertEquals(true, e.onCellExtremes(t0 + 170_000, 3300, 2990, 100f))
    }

    @Test
    fun lowVoltageAnchor_notTriggeredAtRestMidSoc() {
        val e = calibrated(60f)
        assertEquals(false, e.onCellExtremes(t0 + 100_000, 3300, 3270, 0f))
    }

    @Test
    fun capacityLearning_fromContinuousFullToLowDischarge() {
        val e = SocEstimator(SocState(capacityAh = 160f))
        e.setManual(t0, 50f)
        // pin FULL
        e.onCellExtremes(t0 + 1_000, 3560, 3450, 0f)
        e.onCellExtremes(t0 + 7_000, 3560, 3450, 0f)
        assertEquals(AnchorKind.FULL, e.state.anchorKind)
        // discharge 140 Ah continuously: 100 A for 1.4 h, 1 s samples
        var ts = t0 + 8_000
        val samples = (1.4 * 3600).toInt()
        for (i in 0 until samples) { e.onCurrent(ts, 100f); ts += 1_000 }
        assertTrue(e.state.sinceFullContinuous)
        // min cell 3000 mV at 100 A -> 10.67 % => implied capacity = 140/(1-0.1067) = 156.7 Ah
        e.onCellExtremes(ts, 3300, 3000, 100f)
        val expected = 0.7f * 160f + 0.3f * (140f / (1f - 0.1067f))
        assertEquals(expected, e.state.capacityAh, 1.0f)
    }

    @Test
    fun capacityLearning_skippedAfterGap() {
        val e = SocEstimator(SocState(capacityAh = 160f))
        e.setManual(t0, 50f)
        e.onCellExtremes(t0 + 1_000, 3560, 3450, 0f)
        e.onCellExtremes(t0 + 7_000, 3560, 3450, 0f)
        e.onCurrent(t0 + 8_000, 100f)
        e.onCurrent(t0 + 8_000 + 3_600_000L, 100f) // gap
        e.onCurrent(t0 + 8_000 + 3_601_000L, 100f)
        e.onCellExtremes(t0 + 8_000 + 3_700_000L, 3300, 3000, 100f)
        assertEquals(160f, e.state.capacityAh, 0.001f)
    }

    @Test
    fun setCapacity_keepsSocPercent() {
        val e = calibrated(50f, cap = 160f)
        e.setCapacity(140f)
        assertEquals(50f, e.snapshot().socPercent!!, 0.01f)
        assertEquals(70f, e.snapshot().remainingAh!!, 0.01f)
    }

    @Test
    fun replay_realFullCharge_ET_2026_07_03() {
        // Real app log of a full-pack top-up: cell max sits at 3.57 V at rest, rises to 3.80 V at 4.4 A taper.
        val lines = io.github.ev80diag.TestLogs.resource("et_2026-07-03_full_charge_excerpt.csv")
            .bufferedReader().readLines().drop(1)
        assertTrue(lines.size > 400)
        val e = SocEstimator(SocState(capacityAh = 160f))
        e.setManual(lines[0].split(",")[0].toLong() - 1000, 40f) // wrongly thought 40 % before the app saw the pack
        var anchored = 0
        for (l in lines) {
            val c = l.split(",")
            val ts = c[0].toLong(); val i = c[3].toFloat()
            e.onCurrent(ts, i)
            if (e.onCellExtremes(ts, c[1].toInt(), c[2].toInt(), i)) anchored++
        }
        val s = e.snapshot()
        assertNotNull(s.socPercent)
        assertEquals(100f, s.socPercent!!, 0.5f)
        assertEquals(AnchorKind.FULL, s.anchorKind)
        assertTrue("expected at least one FULL anchor", anchored >= 1)
    }
}
