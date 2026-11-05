package io.github.ev80diag.calc

import io.github.ev80diag.data.MapKeyValue
import org.junit.Assert.assertEquals
import org.junit.Test

class SocTrackerTest {
    /** Review M4: the 0.3 state must reach the first van even when a connection activates it before app start-up migrated it. */
    @Test fun activate_migratesLegacyFirst() {
        val kv = MapKeyValue()
        kv.putFloat("consumedAh", 33f); kv.putFloat("capacityAh", 160f); kv.putBoolean("calibrated", true)
        SocTracker.initForTest(kv)
        SocTracker.activate(1)
        assertEquals(160f, SocTracker.capacityOf(1))
        assertEquals(33f, SocStore(kv).load(1).consumedAh)
    }

    /** Review I3: Undo of a wrong switch puts the other van's SOC back as it was before the switch. */
    @Test fun activate_revertCurrent_dropsChangesSinceSwitch() {
        val kv = MapKeyValue()
        SocStore(kv).save(2, SocState(calibrated = true, consumedAh = 50f, lastUpdateTs = 1_000))
        SocTracker.initForTest(kv)
        SocTracker.activate(1)
        SocTracker.activate(2)
        for (i in 1..20) SocTracker.onCurrent(1_000L + i * 1_000, 200f)      // the wrong van's current
        SocTracker.activate(1, revertCurrent = true)
        assertEquals(50f, SocStore(kv).load(2).consumedAh)
    }
}
