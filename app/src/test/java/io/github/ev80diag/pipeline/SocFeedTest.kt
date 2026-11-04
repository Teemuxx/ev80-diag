package io.github.ev80diag.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SocFeedTest {
    /** Cells at the top with the charger idle for 6 s: the estimator anchors at 100 %. */
    private fun SocFeed.anchorFull(t0: Long) {
        for (i in 0..6) { onCurrent(t0 + i * 1_000L, 0f); onCellExtremes(t0 + i * 1_000L, 3_600, 3_400, 0f) }
    }

    @Test
    fun freshFeed_startsUnknown_thenAnchors() {
        val f = SocFeed.Fresh()
        assertNull(f.socPercent())
        f.anchorFull(1_000_000)
        assertEquals(100f, f.socPercent()!!, 0.01f)
    }

    @Test
    fun staleEstimate_isNotShown() {
        // app offline for an hour (driven without the phone): the old value would be a guess, so show none
        val f = SocFeed.Fresh()
        f.anchorFull(1_000_000)
        assertNotNull(f.socPercent())
        f.onCurrent(1_000_000 + 3_600_000, 80f)
        assertNull(f.socPercent())
    }

    @Test
    fun freshFeeds_areIndependent() {
        val a = SocFeed.Fresh(); val b = SocFeed.Fresh()
        a.anchorFull(1_000_000)
        assertNull("a replay must not change another estimate", b.socPercent())
    }
}
