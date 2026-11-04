package io.github.ev80diag.session

import io.github.ev80diag.TestLogs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VanStateTrackerTest {
    @Test
    fun lxStateChanges_phases() {
        val frames = TestLogs.load("lx_state_changes.log.gz")
        val t0 = frames.first().timestamp
        val r = Run().also { it.feedAll(frames) }
        assertEquals(PowerState.ON, r.stateAt(t0 + 10_000).power)
        assertEquals(PowerState.ON, r.stateAt(t0 + 20_000).power)
        assertEquals(PowerState.READY, r.stateAt(t0 + 28_000).power)
        assertEquals(PowerState.READY, r.stateAt(t0 + 37_000).power)
        assertEquals(PowerState.ON, r.stateAt(t0 + 45_000).power)
        assertEquals(PowerState.ON, r.stateAt(t0 + 85_000).power)
        val gears = r.states.filter { it.first in t0 + 26_000..t0 + 38_000 }.mapNotNull { it.second.gear }.distinct()
        assertTrue("gears $gears", gears.containsAll(listOf('R', 'N', 'D', 'P')))
    }

    @Test
    fun etStart_plugged_then_dcCharging() {
        val frames = TestLogs.load("et_terra54_start.log.gz")
        val r = Run().also { it.feedAll(frames) }
        assertEquals(PowerState.PLUGGED, r.stateAt(frames.first().timestamp + 10_000).power)
        val chargeAt = r.states.first { it.second.power == PowerState.CHARGING_DC }.first
        assertTrue("charging at $chargeAt", chargeAt in 1793273488_000L - 3_000..1793273488_000L + 3_000) // 12:31:28
    }

    @Test
    fun noFramesFor10s_isOff() {
        val r = Run.fixture("et_terra54_end.log.gz")
        r.tick(r.lastTs + 11_000)
        assertEquals(PowerState.OFF, r.states.last().second.power)
    }

    @Test
    fun shortGapInsideDcSession_keepsChargingState() {
        val r = Run()
        for (i in 0 until 100) r.feed(Run.bms("16", 1_000_000L + i * 200L))
        r.tick(1_000_000L + 100 * 200L + 4_000)
        assertEquals(PowerState.CHARGING_DC, r.states.last().second.power)
    }
}
