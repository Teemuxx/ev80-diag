package io.github.ev80diag.ui.tiles

import io.github.ev80diag.calc.DriveSnapshot
import io.github.ev80diag.data.store.DriveTotals
import io.github.ev80diag.pipeline.RecorderState
import io.github.ev80diag.ui.screen.LiveThresholds
import io.github.ev80diag.ui.screen.LiveUiBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileValuesTest {
    private val none = DriveTotals(0.0, 0.0)
    private fun ctx(state: RecorderState = RecorderState(), sinceCharge: DriveTotals = none, allTime: DriveTotals = none,
                    periods: Map<Int, DriveTotals> = emptyMap()) =
        TileContext(LiveUiBuilder.build(state, 168.0, null, LiveThresholds()), state, sinceCharge, allTime, periods, java.util.Locale.US)

    @Test fun consSinceCharge_includesOpenDrive() {
        val st = RecorderState(drive = DriveSnapshot(2.0, 0.6, null, null))
        val t = TileValues.of(TileSpec(TileKind.CONS_SINCE_CHARGE), 0, ctx(st, sinceCharge = DriveTotals(10.0, 3.0)))
        assertEquals("30.0", t.value)
        assertEquals("12", t.sub!!.arg)
    }

    @Test fun cons_nullBelow1km() {
        assertEquals("–", TileValues.of(TileSpec(TileKind.CONS_ALL_TIME), 0, ctx(allTime = DriveTotals(0.5, 0.2))).value)
    }

    @Test fun consPeriod_usesItsSlot() {
        val t = TileValues.of(TileSpec(TileKind.CONS_PERIOD, 0, 1), 3, ctx(periods = mapOf(3 to DriveTotals(20.0, 5.0))))
        assertEquals("25.0", t.value)
    }

    @Test fun cellsMinMax() {
        val st = RecorderState(decoded = mapOf("cell_min_mv" to 3271.0, "cell_max_mv" to 3302.0))
        val t = TileValues.of(TileSpec(TileKind.CELLS_MIN_MAX), 0, ctx(st))
        assertEquals("3.271 / 3.302", t.value)
        assertEquals("31", t.sub!!.arg)
    }

    @Test fun everyKind_hasText() {
        for (k in TileKind.entries) {
            val t = TileValues.of(TileSpec(k, 0, 1), 0, ctx())
            assertTrue("$k", t.value.isNotEmpty())
        }
    }
}
