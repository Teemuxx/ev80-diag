package io.github.ev80diag.signals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignalFormatTest {
    @Test
    fun gearLetters() {
        assertEquals("D", SignalFormat.gearLetter(5.0))
        assertEquals("N", SignalFormat.gearLetter(6.0))
        assertEquals("R", SignalFormat.gearLetter(7.0))
        assertEquals("P", SignalFormat.gearLetter(8.0))
        assertNull(SignalFormat.gearLetter(3.0))
    }

    @Test
    fun decimalsByUnit() {
        assertEquals(0, SignalFormat.decimals("°C"))
        assertEquals(1, SignalFormat.decimals("V"))
        assertEquals(0, SignalFormat.decimals("mV"))
        assertEquals(1, SignalFormat.decimals("kWh"))
        assertEquals(0, SignalFormat.decimals("km/h"))
    }
}
