package io.github.ev80diag.ui

import io.github.ev80diag.pipeline.RecorderState
import io.github.ev80diag.signals.SignalDef
import io.github.ev80diag.signals.SignalGroup
import io.github.ev80diag.signals.SignalStatus
import io.github.ev80diag.signals.SignalTable
import io.github.ev80diag.ui.screen.DetailRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailRowsTest {
    private val name: (SignalDef) -> String = { it.key.replace('_', ' ') }

    @Test fun unknownStatus_hidden() {
        val unknown = SignalDef("mystery", 0x7AA, 0, 1, "", SignalGroup.VEHICLE, SignalStatus.UNKNOWN, 1)
        val rows = DetailRows.build(RecorderState(), "", name, SignalTable.all + unknown)
        assertFalse(rows.values.flatten().any { it.def.key == "mystery" })
        assertTrue(rows.values.flatten().any { it.def.key == "pack_v" })
    }

    @Test fun groupsInOrder() {
        assertEquals(listOf(SignalGroup.BATTERY, SignalGroup.CHARGING, SignalGroup.DRIVING, SignalGroup.VEHICLE),
            DetailRows.build(RecorderState(), "", name).keys.toList())
    }

    @Test fun search_byName_key_id() {
        fun keys(q: String) = DetailRows.build(RecorderState(), q, name).values.flatten().map { it.def.key }.toSet()
        assertEquals(setOf("pack_v", "pack_a"), keys("pack"))
        assertEquals(setOf("pack_v", "pack_a", "energy_kwh"), keys("0x550"))
        assertEquals(setOf("pack_v", "pack_a", "energy_kwh"), keys("550"))
        assertEquals(setOf("pack_v", "pack_a"), keys("PACK"))
    }

    @Test fun rawHex_fromSourceFrame() {
        val st = RecorderState(decoded = mapOf("pack_v" to 358.5), raw = mapOf(0x550 to byteArrayOf(0x01, 0x0E, 0xF6.toByte(), 0x23)))
        val row = DetailRows.build(st, "pack_v", name).values.flatten().single { it.def.key == "pack_v" }
        assertEquals("01 0E F6 23", row.rawHex)
        assertEquals(358.5, row.value!!, 1e-9)
    }
}
