package io.github.ev80diag.ui

import io.github.ev80diag.ui.charts.ChartMath
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartMathTest {
    @Test fun linearFraction() {
        assertEquals(0.0, ChartMath.fraction(0.0, 0.0, 35.0, log = false), 1e-9)
        assertEquals(0.5, ChartMath.fraction(17.5, 0.0, 35.0, log = false), 1e-9)
    }

    @Test fun logFraction_mapsDecades() {
        assertEquals(0.0, ChartMath.fraction(10.0, 10.0, 10_000.0, log = true), 1e-9)       // 10 kΩ
        assertEquals(1.0 / 3, ChartMath.fraction(100.0, 10.0, 10_000.0, log = true), 1e-9)  // 100 kΩ
        assertEquals(1.0, ChartMath.fraction(10_000.0, 10.0, 10_000.0, log = true), 1e-9)   // 10 MΩ
    }

    @Test fun logFraction_clampsOutOfRange() {
        assertEquals(0.0, ChartMath.fraction(0.0, 10.0, 10_000.0, log = true), 1e-9)
        assertEquals(1.0, ChartMath.fraction(99_999.0, 10.0, 10_000.0, log = true), 1e-9)
    }

    @Test fun decimate_keepsExtremesAndEnds() {
        val pts = (0 until 10_000).map { it.toLong() to sin(it * 2 * PI / 1000) * (1 + it / 10_000.0) }
        val out = ChartMath.decimate(pts, 400)
        assertTrue("size ${out.size}", out.size <= 400)
        assertEquals(pts.first(), out.first()); assertEquals(pts.last(), out.last())
        assertEquals(pts.maxOf { it.second }, out.maxOf { it.second }, 1e-9)
        assertEquals(pts.minOf { it.second }, out.minOf { it.second }, 1e-9)
        assertTrue(out.zipWithNext().all { (a, b) -> a.first <= b.first })
    }

    @Test fun decimate_shortListUnchanged() {
        val pts = listOf(1L to 1.0, 2L to 2.0)
        assertEquals(pts, ChartMath.decimate(pts, 400))
    }
}
