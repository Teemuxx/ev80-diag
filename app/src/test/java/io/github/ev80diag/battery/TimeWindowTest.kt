package io.github.ev80diag.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeWindowTest {
    private val now = 1_800_000_000_000L

    @Test fun quick_15min() {
        val w = TimeWindow.last(900_000, now)
        assertEquals(now - 900_000, w.fromMs); assertEquals(now, w.toMs); assertTrue(w.followNow)
    }

    @Test fun earlier_thenLater_returnsToNow() {
        val e = TimeWindow.last(3_600_000, now).earlier()
        assertEquals(now - 7_200_000, e.fromMs); assertFalse(e.followNow)
        val back = e.later(now)
        assertEquals(now, back.toMs); assertTrue(back.followNow)
    }

    @Test fun later_clampsAtNow() {
        val w = TimeWindow(now - 5_000_000, now - 1_000_000, false).later(now)
        assertEquals(now, w.toMs); assertEquals(4_000_000, w.toMs - w.fromMs); assertTrue(w.followNow)
    }

    @Test fun zoomOut_clampsAtNow() {
        val w = TimeWindow.last(3_600_000, now).zoomOut(now)
        assertEquals(now, w.toMs); assertEquals(7_200_000, w.toMs - w.fromMs)
        val mid = TimeWindow(now - 10_000_000, now - 6_000_000, false).zoomOut(now)
        assertEquals(now - 12_000_000, mid.fromMs); assertEquals(now - 4_000_000, mid.toMs)
    }

    @Test fun zoom_keepsFocusPoint() {
        val w = TimeWindow(now - 4_000_000, now - 0, false)
        val focus = w.fromMs + 1_000_000   // 25 % in
        val z = w.zoom(2.0, focus, now)
        assertEquals(2_000_000, z.toMs - z.fromMs)
        assertEquals(0.25, (focus - z.fromMs).toDouble() / (z.toMs - z.fromMs), 1e-9)
    }

    @Test fun zoom_minimum60s() {
        val z = TimeWindow(now - 100_000, now, false).zoom(10.0, now - 50_000, now)
        assertEquals(60_000, z.toMs - z.fromMs)
    }

    @Test fun pan_clampsAtNow() {
        val p = TimeWindow(now - 2_000_000, now - 1_000_000, false).pan(5_000_000, now)
        assertEquals(now, p.toMs); assertEquals(1_000_000, p.toMs - p.fromMs)
    }

    @Test fun bucket_30days() {
        assertEquals(6_480_000, TimeWindow.last(30 * 86_400_000L, now).bucketMs)
        assertEquals(2_250, TimeWindow.last(900_000, now).bucketMs)
        assertEquals(1_000, TimeWindow.last(300_000, now).bucketMs)   // never below 1 s
    }
}
