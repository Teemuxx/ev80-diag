package io.github.ev80diag.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberSpecTest {
    @Test
    fun typedValue_isTakenOnlyInRange() {
        val days = AppSettings.DRIVE_RETENTION_DAYS
        assertEquals(37, days.parse("37"))
        assertEquals(120, days.parse(" 120 "))
        assertNull("below the minimum", days.parse("3"))
        assertNull(days.parse(""))
        assertNull(days.parse("12a"))
    }

    @Test
    fun coldLimit_acceptsNegativeValues() {
        assertEquals(-2, AppSettings.COLD_BATTERY_C.parse("-2"))
        assertNull("a lone minus sign is not a value yet", AppSettings.COLD_BATTERY_C.parse("-"))
    }

    @Test
    fun defaults_areTheSpecValues_andInRange() {
        // spec §11: drive raw logs 90 days, insulation low 180 kΩ, cold battery 3 °C; spread warning 80 mV
        assertEquals(90, AppSettings.DRIVE_RETENTION_DAYS.default)
        assertEquals(180, AppSettings.INSULATION_LOW_KOHM.default)
        assertEquals(3, AppSettings.COLD_BATTERY_C.default)
        assertEquals(80, AppSettings.SPREAD_WARN_MV.default)
        listOf(AppSettings.DRIVE_RETENTION_DAYS, AppSettings.INSULATION_LOW_KOHM, AppSettings.COLD_BATTERY_C, AppSettings.SPREAD_WARN_MV)
            .forEach { assertTrue(it.default in it.range) }
    }

    @Test fun lowCell_default3100() {
        assertEquals(3100, AppSettings.LOW_CELL_MV.default)
        assertEquals(null, AppSettings.LOW_CELL_MV.parse("2700"))
        assertEquals(3000, AppSettings.LOW_CELL_MV.parse("3000"))
    }
}
