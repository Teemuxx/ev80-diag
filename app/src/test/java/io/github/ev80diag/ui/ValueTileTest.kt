package io.github.ev80diag.ui

import io.github.ev80diag.ui.components.fittedValueSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ValueTileTest {
    @Test fun shortValues_keepTheirSize() {
        assertEquals(30f, fittedValueSize(30f, "57516 km".length), 0.01f)
    }

    @Test fun longValues_shrink_notBelow16() {
        assertEquals(30f * 9 / 13, fittedValueSize(30f, "3,261 / 3,277".length), 0.01f)
        assertEquals(16f, fittedValueSize(30f, 40), 0.01f)
    }
}
