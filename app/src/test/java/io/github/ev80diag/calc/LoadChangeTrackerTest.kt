package io.github.ev80diag.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoadChangeTrackerTest {
    @Test fun steadyCurrent_noChange() {
        val t = LoadChangeTracker()
        for (s in 0..30) t.onCurrent(s * 1_000L, 40.0 + (s % 3))
        assertNull(t.msSinceChange(31_000))
    }

    @Test fun stepOf60A_isAChange() {
        val t = LoadChangeTracker()
        for (s in 0..10) t.onCurrent(s * 1_000L, 20.0)
        t.onCurrent(11_000, 80.0)
        assertEquals(9_000L, t.msSinceChange(20_000))
    }
}
