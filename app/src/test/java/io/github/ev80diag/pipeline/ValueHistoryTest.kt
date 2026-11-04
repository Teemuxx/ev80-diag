package io.github.ev80diag.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class ValueHistoryTest {
    @Test fun dropsOlderThanWindow() {
        val h = ValueHistory(windowMs = 10_000)
        for (s in 0..30) h.add(s * 1_000L, mapOf("v12" to s.toDouble()))
        val pts = h.series("v12")
        assertEquals(20_000L, pts.first().first)
        assertEquals(30_000L, pts.last().first)
    }

    @Test fun onePointPerSecond() {
        val h = ValueHistory()
        for (i in 0 until 40) h.add(1_000_000L + i * 250L, mapOf("v12" to 13.0))   // 4 per second for 10 s
        assertEquals(10, h.series("v12").size)
    }

    @Test fun unknownKey_isEmpty() {
        assertEquals(0, ValueHistory().series("nope").size)
    }
}
