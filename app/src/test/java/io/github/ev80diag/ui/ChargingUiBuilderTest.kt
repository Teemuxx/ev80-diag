package io.github.ev80diag.ui

import io.github.ev80diag.data.store.SampleEntity
import io.github.ev80diag.data.store.SessionEntity
import io.github.ev80diag.pipeline.ChargeSnapshot
import io.github.ev80diag.pipeline.RecorderState
import io.github.ev80diag.ui.screen.ChargingThresholds
import io.github.ev80diag.ui.screen.ChargingUiBuilder
import io.github.ev80diag.warnings.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargingUiBuilderTest {
    private val t = ChargingThresholds()
    private fun decoded(vararg kv: Pair<String, Double>) = mapOf(*kv)
    private fun sample(ts: Long, v: Double, a: Double) = SampleEntity(1, ts, packV = v, packA = a)

    private val live = RecorderState(
        decoded = decoded("pack_v" to 361.0, "pack_a" to -38.0, "soc_bms" to 98.5, "insulation_kohm" to 1930.0,
            "temp_avg" to 11.0, "temp_min" to 11.0, "temp_max" to 13.0, "cell_min_mv" to 3392.0, "cell_max_mv" to 3580.0),
        charge = ChargeSnapshot(13.7, 8.9, 1_100_000, 2.7), socAppPercent = 98f, temps = IntArray(18) { 12 },
    )

    @Test fun active_valuesFromState() {
        val ui = ChargingUiBuilder.build(live, active = true, session = null, samples = emptyList(), t = t)
        assertEquals(13.7, ui.powerKw!!, 0.01)
        assertEquals(8.9, ui.chargedKwh!!, 0.01)
        assertEquals(2.7, ui.timeToFullMin!!, 0.01)
        assertEquals(98.0, ui.socAppPercent!!, 0.01)
        assertEquals(98.5, ui.socBmsPercent!!, 0.01)
        assertEquals(1930.0, ui.insulationKOhm!!, 0.01)
        assertEquals(Level.OK, ui.insulationLevel)
        assertEquals(188, ui.spreadMv)
        assertFalse(ui.finished)
        assertEquals(18, ui.temps!!.size)
    }

    @Test fun insulationClamp10000_isNoReadingYet() {
        val ui = ChargingUiBuilder.build(live.copy(decoded = live.decoded + ("insulation_kohm" to 10000.0)), true, null, emptyList(), t)
        assertNull(ui.insulationKOhm)
        assertEquals(Level.OK, ui.insulationLevel)
    }

    @Test fun insulationLevels_followThreshold() {
        fun level(k: Double, low: Double = 180.0) = ChargingUiBuilder.build(
            live.copy(decoded = live.decoded + ("insulation_kohm" to k)), true, null, emptyList(), ChargingThresholds(insulationLowKOhm = low)).insulationLevel
        assertEquals(Level.AMBER, level(150.0))
        assertEquals(Level.RED, level(34.0))
        assertEquals(Level.OK, level(150.0, low = 100.0))
    }

    @Test fun tapering_whenPowerBelow80PercentOfMax_andSocHigh() {
        val samples = (0 until 100).map { sample(it * 1000L, 358.5, -80.0) }      // 28.7 kW
        assertTrue(ChargingUiBuilder.build(live, true, null, samples, t).tapering)    // 13.7 kW now, SOC 98.5
        val lowSoc = live.copy(decoded = live.decoded + ("soc_bms" to 70.0))
        assertFalse(ChargingUiBuilder.build(lowSoc, true, null, samples, t).tapering)
        val full = live.copy(charge = ChargeSnapshot(28.0, 1.0, 1000, 20.0))
        assertFalse(ChargingUiBuilder.build(full, true, null, samples, t).tapering)
    }

    @Test fun cold_levels() {
        fun cold(min: Double) = ChargingUiBuilder.build(live.copy(decoded = live.decoded + ("temp_min" to min)), true, null, emptyList(), t).coldLevel
        assertEquals(Level.OK, cold(11.0)); assertEquals(Level.AMBER, cold(2.0)); assertEquals(Level.RED, cold(-1.0))
    }

    @Test fun spreadAtTopOfCharge_isOk() {
        val ui = ChargingUiBuilder.build(live, true, null, emptyList(), t)    // 188 mV but SOC 98.5 and a cell at 3.58 V
        assertEquals(Level.OK, ui.spreadLevel)
    }

    @Test fun finishedSession_usesLastSampleAndSessionTotals() {
        val session = SessionEntity(id = 1, profileId = 1, type = "CHARGE_DC", outcome = "COMPLETE", startMs = 1_000, endMs = 1_261_000,
            chargedKwh = 9.4, adapterKind = "STN_BT")
        val samples = listOf(sample(500_000, 358.5, -80.0), SampleEntity(1, 1_260_000, packV = 361.0, packA = -0.7, socBms = 100.5, insulationKOhm = 1900.0))
        val ui = ChargingUiBuilder.build(RecorderState(), active = false, session = session, samples = samples, t = t)
        assertTrue(ui.finished)
        assertEquals(9.4, ui.chargedKwh!!, 0.001)
        assertEquals(1_260_000L, ui.elapsedMs)
        assertEquals(100.5, ui.socBmsPercent!!, 0.001)
        assertEquals(1900.0, ui.insulationKOhm!!, 0.001)
        assertNull(ui.timeToFullMin)
    }
}
