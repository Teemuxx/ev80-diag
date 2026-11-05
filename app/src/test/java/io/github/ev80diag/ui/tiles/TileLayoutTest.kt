package io.github.ev80diag.ui.tiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileLayoutTest {
    @Test fun defaultLayout_whenNothingStored() {
        assertEquals(TileLayout.DEFAULT, TileLayout.decode(null))
        assertEquals(8, TileLayout.DEFAULT.size)
        assertEquals(TileKind.SOC_APP, TileLayout.DEFAULT[0].kind)
        assertEquals(TileKind.ODOMETER, TileLayout.DEFAULT[7].kind)
    }

    @Test fun roundTrip_withPeriod() {
        val layout = TileLayout.DEFAULT.toMutableList().also { it[5] = TileSpec(TileKind.CONS_PERIOD, 1000, 2000) }
        assertEquals(layout, TileLayout.decode(TileLayout.encode(layout)))
    }

    @Test fun corruptLayout_fallsBackPerSlot() {
        val l = TileLayout.decode("SOC_APP;NO_SUCH_TILE;SPEED")
        assertEquals(8, l.size)
        assertEquals(TileKind.SOC_APP, l[0].kind)
        assertEquals(TileLayout.DEFAULT[1], l[1])
        assertEquals(TileKind.SPEED, l[2].kind)
        for (i in 3 until 8) assertEquals(TileLayout.DEFAULT[i], l[i])
    }

    /** TEMP_MOTOR was removed (0x302 is not a motor temperature): a stored layout with it falls back in that slot. */
    @Test fun decode_removedKind_fallsBack() {
        val l = TileLayout.decode("TEMP_MOTOR;SPEED")
        assertEquals(TileLayout.DEFAULT[0], l[0])
        assertEquals(TileKind.SPEED, l[1].kind)
        assertTrue(TileKind.entries.none { it.name == "TEMP_MOTOR" })
    }

    @Test fun periodWithoutTimes_fallsBack() {
        assertEquals(TileLayout.DEFAULT[0], TileLayout.decode("CONS_PERIOD:x:y")[0])
    }

    /** 0.3 kept one layout per van (tiles_<id>); 0.4 shares one: the first van's old layout carries over. */
    @Test fun store_sharedFallsBackToOldPerVanKey() {
        val kv = io.github.ev80diag.data.MapKeyValue()
        kv.putString("tiles_3", TileLayout.encode(TileLayout.DEFAULT.reversed()))
        kv.putString("tiles_1", TileLayout.encode(TileLayout.DEFAULT.drop(1) + TileLayout.DEFAULT.first()))
        val store = TileLayoutStore(kv)
        assertEquals(TileLayout.DEFAULT.drop(1) + TileLayout.DEFAULT.first(), store.load())
        store.save(TileLayout.DEFAULT)
        assertEquals(TileLayout.DEFAULT, store.load())
    }
}
