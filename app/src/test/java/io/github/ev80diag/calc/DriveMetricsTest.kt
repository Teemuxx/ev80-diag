package io.github.ev80diag.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DriveMetricsTest {
    /** 350 V, 50 A (17.5 kW), 36 km/h: one pack and one odometer frame per second. */
    private fun drive(seconds: Int, glitches: Boolean = false): DriveMetrics {
        val m = DriveMetrics()
        val odo0 = 57_509.9
        for (s in 0..seconds) {
            val t = 1_000_000L + s * 1_000L
            m.onPack(t, 350.0, 50.0)
            m.onOdometer(t + 500, odo0 + s * 0.01)
            if (glitches && s == 100) m.onOdometer(t + 600, 0.0)                 // wake frame
            if (glitches && s == 200) m.onOdometer(t + 600, odo0 + s * 0.01 - 1.0) // backwards
        }
        return m
    }

    @Test fun constantDrive() {
        val d = drive(600).snapshot()
        assertEquals(6.0, d.km, 0.01)
        assertEquals(2.917, d.kwh, 0.01)
        assertEquals(48.6, d.kwhPer100Now!!, 0.5)
        assertEquals(48.6, d.kwhPer100Recent!!, 0.5)
    }

    @Test fun odoZeroAndBackwards_ignored() {
        val d = drive(600, glitches = true).snapshot()
        assertEquals(6.0, d.km, 0.01)
        assertEquals(48.6, d.kwhPer100Now!!, 0.5)
    }

    @Test fun recent_nullBelow5km() {
        val d = drive(400).snapshot()   // 4 km
        assertNull(d.kwhPer100Recent)
        assertNotNull(d.kwhPer100Now)
    }

    @Test fun nothing_belowFirst200m() {
        val d = drive(10).snapshot()    // 0.1 km
        assertNull(d.kwhPer100Now)
    }

    @Test fun standingStill_doesNotGrow() {
        val m = DriveMetrics()
        for (s in 0 until 20_000) { m.onPack(s * 200L, 350.0, 5.0); m.onOdometer(s * 200L + 100, 57_509.9) }   // ~1 h in READY at 5 Hz
        assertEquals(true, m.windowPoints <= 2)
    }
}
