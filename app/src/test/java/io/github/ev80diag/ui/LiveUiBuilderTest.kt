package io.github.ev80diag.ui

import io.github.ev80diag.R
import io.github.ev80diag.calc.DriveSnapshot
import io.github.ev80diag.pipeline.LinkStatus
import io.github.ev80diag.pipeline.RecorderState
import io.github.ev80diag.session.PowerState
import io.github.ev80diag.session.VanState
import io.github.ev80diag.ui.screen.LiveThresholds
import io.github.ev80diag.ui.screen.LiveUiBuilder
import io.github.ev80diag.ui.screen.TopBarWarning
import io.github.ev80diag.warnings.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveUiBuilderTest {
    private val t = LiveThresholds(spreadMv = 80, lowCellMv = 3100)

    /** End of the 2026-10-23 12:19 drive (values computed from the log). */
    private val driveEnd = RecorderState(
        link = LinkStatus.CONNECTED,
        van = VanState(PowerState.READY, 'D', 1_000_000),
        decoded = mapOf("cell_min_mv" to 3261.0, "cell_min_no" to 34.0, "cell_max_mv" to 3277.0, "cell_max_no" to 4.0,
            "pack_v" to 350.0, "pack_a" to 20.0, "soc_bms" to 78.5, "range_dash_km" to 143.0, "drive_temp_302" to 36.0,
            "temp_avg" to 14.0, "speed_kmh" to 42.0, "v12" to 13.9),
        socAppPercent = 78.5f,
        drive = DriveSnapshot(5.6, 1.62, 27.0, 28.9),
    )

    private fun build(s: RecorderState = driveEnd, allTime: Double? = null) = LiveUiBuilder.build(s, 168.0, allTime, t)

    @Test fun driveEnd_values() {
        val ui = build()
        assertEquals(3261, ui.minCellMv); assertEquals(34, ui.minCellNo)
        assertEquals(3277, ui.maxCellMv); assertEquals(4, ui.maxCellNo)
        assertEquals(16, ui.spreadMv)
        assertEquals(Level.OK, ui.spreadLevel)
        assertEquals(Level.OK, ui.lowCellLevel)
        assertEquals(7.0, ui.powerKw!!, 0.001)
        assertEquals(159.7, ui.rangeAppKm!!, 0.5)
        assertEquals(143.0, ui.rangeDashKm!!, 0.001)
        assertEquals(27.0, ui.kwhPer100Now!!, 0.001)
        assertFalse(ui.stale)
    }

    @Test fun range_fallsBackToAllTime() {
        val ui = build(driveEnd.copy(drive = DriveSnapshot(1.0, 0.3, 30.0, null)), allTime = 30.0)
        assertEquals(78.5 / 100 * 168 * 350 / 1000 / 30.0 * 100, ui.rangeAppKm!!, 0.5)
    }

    @Test fun range_nullWithoutConsumption() {
        assertNull(build(driveEnd.copy(drive = null)).rangeAppKm)
    }

    @Test fun bar_fractions() {
        fun bar(min: Double, max: Double) = build(driveEnd.copy(decoded = driveEnd.decoded + mapOf("cell_min_mv" to min, "cell_max_mv" to max)))
        assertEquals(0f, bar(2900.0, 3650.0).barMinFraction!!, 0.001f)
        assertEquals(1f, bar(2900.0, 3650.0).barMaxFraction!!, 0.001f)
        assertEquals(1f, bar(2900.0, 4000.0).barMaxFraction!!, 0.001f)
        assertEquals(0f, bar(2500.0, 3300.0).barMinFraction!!, 0.001f)
    }

    @Test fun vanOff_marksStale() {
        val ui = build(driveEnd.copy(van = VanState(PowerState.OFF, null, 777_000)))
        assertTrue(ui.stale); assertEquals(777_000L, ui.valuesFromMs)
    }

    @Test fun disconnected_marksStale() {
        assertTrue(build(driveEnd.copy(link = LinkStatus.DISCONNECTED)).stale)
    }

    @Test fun spread_amberAt100mV_red160() {
        fun level(max: Double) = build(driveEnd.copy(decoded = driveEnd.decoded + ("cell_max_mv" to max))).spreadLevel
        assertEquals(Level.AMBER, level(3361.0))   // 100 mV
        assertEquals(Level.RED, level(3421.0))     // 160 mV
    }

    @Test fun lowCell_levels() {
        fun level(min: Double) = build(driveEnd.copy(decoded = driveEnd.decoded + ("cell_min_mv" to min))).lowCellLevel
        assertEquals(Level.AMBER, level(3050.0))
        assertEquals(Level.RED, level(2990.0))
    }

    @Test fun topBarWarning_worstWins() {
        val amberSpread = build(driveEnd.copy(decoded = driveEnd.decoded + ("cell_max_mv" to 3361.0)))
        assertEquals(Level.OK to null, TopBarWarning.of(build(), Level.OK))
        assertEquals(Level.AMBER to R.string.warn_spread_high, TopBarWarning.of(amberSpread, Level.OK))
        assertEquals(Level.RED to R.string.warn_insulation_low, TopBarWarning.of(amberSpread, Level.RED))
        val redCell = build(driveEnd.copy(decoded = driveEnd.decoded + ("cell_min_mv" to 2990.0)))
        assertEquals(Level.RED to R.string.warn_cell_low, TopBarWarning.of(redCell, Level.AMBER))
    }
}
