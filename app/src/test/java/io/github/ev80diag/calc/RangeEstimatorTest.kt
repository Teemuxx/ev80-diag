package io.github.ev80diag.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RangeEstimatorTest {
    @Test fun drive1023_end() {
        assertEquals(159.7, RangeEstimator.rangeKm(78.5, 168.0, 350.0, 28.9)!!, 0.5)
    }

    @Test fun range_nullForImplausibleConsumption() {
        assertNull(RangeEstimator.rangeKm(78.5, 168.0, 350.0, 2.0))
        assertNull(RangeEstimator.rangeKm(78.5, 168.0, 350.0, -10.0))
    }

    @Test fun nullInputs() {
        assertNull(RangeEstimator.rangeKm(null, 168.0, 350.0, 28.9))
        assertNull(RangeEstimator.rangeKm(78.5, 168.0, null, 28.9))
        assertNull(RangeEstimator.rangeKm(78.5, 168.0, 350.0, null))
    }
}
