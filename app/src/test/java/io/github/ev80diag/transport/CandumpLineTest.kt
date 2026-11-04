package io.github.ev80diag.transport

import io.github.ev80diag.TestLogs.frame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CandumpLineTest {
    @Test
    fun parse_realLine() {
        val f = CandumpLine.parse("(1793273355.027116) can0 200#0600006404010000")!!
        assertEquals(0x200, f.id)
        assertArrayEquals(byteArrayOf(6, 0, 0, 0x64, 4, 1, 0, 0), f.data)
        assertEquals(1793273355027L, f.timestamp)
    }

    @Test
    fun format_roundTrip() {
        val f = frame(0x510, "4153D60CD70CD50C", 1793273355027L)
        assertEquals("(1793273355.027000) can0 510#4153D60CD70CD50C", CandumpLine.format(f))
        assertEquals(f, CandumpLine.parse(CandumpLine.format(f)))
    }

    @Test
    fun format_matchesLaptopToolRegex() {
        val line = CandumpLine.format(frame(0x08A, "0987", 1793273355001L))
        assertEquals(true, Regex("""^\(\d+\.\d{6}\) can0 [0-9A-F]{3}#[0-9A-F]*$""").matches(line))
    }

    @Test
    fun parse_rejectsGarbage() {
        assertNull(CandumpLine.parse("(1793273355.000000) # CANERROR"))
        assertNull(CandumpLine.parse(""))
        assertNull(CandumpLine.parse("(1.0) can0 200#0"))      // odd number of hex digits
        assertNull(CandumpLine.parse("(1793273355.027116) can0 2000#00")) // 4-digit ID (29-bit) not supported
    }
}
