package io.github.ev80diag.calc

import io.github.ev80diag.data.MapKeyValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SocStoreTest {
    @Test fun twoVans_independent() {
        val store = SocStore(MapKeyValue())
        store.save(1, SocState(calibrated = true, consumedAh = 10f, capacityAh = 160f))
        store.save(2, SocState(calibrated = true, consumedAh = 50f, capacityAh = 150f))
        assertEquals(10f, store.load(1).consumedAh); assertEquals(160f, store.load(1).capacityAh)
        assertEquals(50f, store.load(2).consumedAh); assertEquals(150f, store.load(2).capacityAh)
    }

    @Test fun legacyState_becomesFirstVans() {
        val kv = MapKeyValue()
        kv.putFloat("consumedAh", 12f); kv.putFloat("capacityAh", 160f); kv.putBoolean("calibrated", true)
        val store = SocStore(kv)
        store.migrateLegacy(1)
        assertEquals(12f, store.load(1).consumedAh); assertEquals(160f, store.load(1).capacityAh)
        assertFalse(kv.contains("consumedAh"))
        store.save(1, store.load(1).copy(consumedAh = 20f))
        store.migrateLegacy(1)            // second call: nothing left to copy
        assertEquals(20f, store.load(1).consumedAh)
    }

    @Test fun unknownVan_defaults() {
        val s = SocStore(MapKeyValue()).load(7)
        assertEquals(168f, s.capacityAh); assertFalse(s.calibrated)
    }

    @Test fun delete_removesOnlyThatVan() {
        val store = SocStore(MapKeyValue())
        store.save(1, SocState(consumedAh = 10f)); store.save(2, SocState(consumedAh = 50f))
        store.delete(2)
        assertEquals(10f, store.load(1).consumedAh); assertEquals(0f, store.load(2).consumedAh)
    }
}
