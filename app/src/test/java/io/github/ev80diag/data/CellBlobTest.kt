package io.github.ev80diag.data

import io.github.ev80diag.data.store.CellBlob
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CellBlobTest {
    @Test fun roundTrip() {
        val mv = IntArray(104) { 2000 + it * 28 }   // 2000..4884
        val packed = CellBlob.pack(mv)
        assertEquals(208, packed.size)
        assertArrayEquals(mv, CellBlob.unpack(packed))
    }
}
