package io.github.ev80diag.warnings

import org.junit.Assert.assertEquals
import org.junit.Test

class WarningRulesTest {
    @Test fun insulation_levels() {
        assertEquals(Level.OK, WarningRules.insulation(1930.0))
        assertEquals(Level.OK, WarningRules.insulation(10000.0))
        assertEquals(Level.AMBER, WarningRules.insulation(150.0))
        assertEquals(Level.RED, WarningRules.insulation(34.0))
        assertEquals(Level.RED, WarningRules.insulation(0.0))
    }

    @Test fun cold_levels() {
        assertEquals(Level.OK, WarningRules.coldBattery(11.0, charging = true))
        assertEquals(Level.AMBER, WarningRules.coldBattery(2.0, charging = true))
        assertEquals(Level.RED, WarningRules.coldBattery(-1.0, charging = true))
        assertEquals(Level.AMBER, WarningRules.coldBattery(-1.0, charging = false))
    }

    @Test fun spread_topOfCharge_isOk() {
        assertEquals(Level.OK, WarningRules.spread(345, 3759, 100.5, charging = true))
        assertEquals(Level.OK, WarningRules.spread(150, 3460, 90.0, charging = true))   // high cell near full
        assertEquals(Level.OK, WarningRules.spread(150, 3300, 96.0, charging = true))   // SOC near full
    }

    @Test fun spread_levels_whenNotAtTop() {
        assertEquals(Level.OK, WarningRules.spread(31, 3302, 74.5, charging = false))
        assertEquals(Level.AMBER, WarningRules.spread(90, 3302, 74.5, charging = false))
        assertEquals(Level.RED, WarningRules.spread(170, 3302, 74.5, charging = false))
        assertEquals(Level.AMBER, WarningRules.spread(100, 3300, 60.0, charging = true)) // mid-charge: unusual
    }

    @Test fun lowCell() {
        assertEquals(Level.OK, WarningRules.lowCell(3200))
        assertEquals(Level.AMBER, WarningRules.lowCell(3050))
        assertEquals(Level.RED, WarningRules.lowCell(2990))
    }

    @Test fun spread_suppressedAfterLoadChange() {
        assertEquals(Level.OK, WarningRules.spread(120, 3302, 74.5, charging = false, msSinceLoadChange = 20_000))
        assertEquals(Level.AMBER, WarningRules.spread(120, 3302, 74.5, charging = false, msSinceLoadChange = 90_000))
    }
}
