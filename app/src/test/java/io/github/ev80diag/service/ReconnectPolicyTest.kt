package io.github.ev80diag.service

import io.github.ev80diag.pipeline.SourceResult
import io.github.ev80diag.settings.RecordingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {
    private val minute = 60_000L

    @Test fun firstConnect_isImmediate() {
        assertEquals(ReconnectPolicy.Decision.Connect(0), ReconnectPolicy.decide(SourceResult.NONE, 0))
    }

    @Test fun refused_stops() {
        assertEquals(ReconnectPolicy.Decision.Stop, ReconnectPolicy.decide(SourceResult.REFUSED, 0))
    }

    @Test fun failure_retriesWithBackoff_cappedAt60s() {
        val d = { attempt: Int -> (ReconnectPolicy.decide(SourceResult.FAILED, attempt) as ReconnectPolicy.Decision.Connect).delayMs }
        assertEquals(5_000L, d(0)); assertEquals(10_000L, d(1)); assertEquals(20_000L, d(2)); assertEquals(60_000L, d(6)); assertEquals(60_000L, d(30))
    }

    @Test fun automatic_neverStopsWhenVanIsOff() {
        assertFalse(ReconnectPolicy.stopBecauseVanAsleep(RecordingMode.AUTOMATIC, 600 * minute))
    }

    @Test fun whileOpen_stopsAfterVanOffTenMinutes() {
        assertFalse(ReconnectPolicy.stopBecauseVanAsleep(RecordingMode.WHILE_OPEN, 5 * minute))
        assertTrue(ReconnectPolicy.stopBecauseVanAsleep(RecordingMode.WHILE_OPEN, 10 * minute))
    }
}
