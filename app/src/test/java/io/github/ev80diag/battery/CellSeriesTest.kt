package io.github.ev80diag.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CellSeriesTest {
    /** Cell i sits at 3300 + i mV; at the second snapshot cell 0 jumps to the top. */
    private val snaps = listOf(
        1_000L to IntArray(104) { 3300 + it },
        2_000L to IntArray(104) { if (it == 0) 3500 else 3300 + it },
    )

    @Test fun picksTenLowestAndHighest() {
        val s = CellSeries.build(snaps, 1_000, emptySet())!!
        assertEquals((0..9).toList(), s.lowest)
        assertEquals((103 downTo 94).toList(), s.highest)
        assertTrue(s.lowest.intersect(s.highest.toSet()).isEmpty())
        assertEquals(20, s.lines.size)
        assertEquals(3310, s.bandMin[0])     // cell 10 is the lowest outside the lines
        assertEquals(3393, s.bandMax[0])     // cell 93 the highest
        assertEquals(listOf(1_000L, 2_000L), s.times.toList())
    }

    @Test fun selectionAtSnapshotTime() {
        val s = CellSeries.build(snaps, 2_500, emptySet())!!
        assertEquals(0, s.highest.first())
        assertEquals(3500, s.lines[0]!![1])
    }

    @Test fun extraCellAdded() {
        val s = CellSeries.build(snaps, 1_000, setOf(50))!!
        assertEquals(21, s.lines.size)
        assertEquals(3350, s.lines[50]!![0])
    }

    @Test fun emptyPeriod_hasNoSeries() {
        assertNull(CellSeries.build(emptyList(), 0, emptySet()))
    }
}
