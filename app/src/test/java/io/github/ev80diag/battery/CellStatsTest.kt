package io.github.ev80diag.battery

import io.github.ev80diag.TestLogs
import io.github.ev80diag.signals.Decoder
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class CellStatsTest {
    @Test fun etEnd_1251() {
        val at = LocalDateTime.of(2026, 10, 29, 12, 51, 30).atZone(ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli()
        val d = Decoder()
        var last: IntArray? = null
        var seen: Any? = null
        for (f in TestLogs.load("et_terra54_end.log.gz")) {
            if (f.timestamp > at) break
            d.decode(f)
            val c = d.latestCells
            if (c != null && c !== seen) { seen = c; last = c.voltages }
        }
        val v = CellStats.of(last!!)
        assertEquals(53, v.maxIdx + 1)
        assertEquals(3759.0, v.mv[v.maxIdx].toDouble(), 5.0)
        assertEquals(46, v.minIdx + 1)
        assertEquals(3414.0, v.mv[v.minIdx].toDouble(), 5.0)
        assertEquals(3433.0, v.median.toDouble(), 5.0)
        assertEquals(v.mv[v.maxIdx] - v.mv[v.minIdx], v.spreadMv)
    }

    @Test fun colourFraction() {
        assertEquals(0f, CellStats.colourFraction(3400, 3400), 1e-6f)
        assertEquals(1f, CellStats.colourFraction(3460, 3400), 1e-6f)
        assertEquals(-1f, CellStats.colourFraction(3310, 3400), 1e-6f)
        assertEquals(0.5f, CellStats.colourFraction(3430, 3400), 1e-6f)
    }

    @Test fun median_evenCount() {
        assertEquals(3401, CellStats.of(IntArray(104) { if (it < 52) 3400 else 3402 }).median)
    }
}
