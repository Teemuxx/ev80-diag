package io.github.ev80diag

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugLogTest {
    @Test
    fun rotates_andTailSpansBothFiles() {
        val dir = Files.createTempDirectory("dbg").toFile()
        DebugLog.init(dir, maxBytes = 200)
        repeat(30) { DebugLog.log("line $it") }
        assertTrue(java.io.File(dir, "debug.log.1").exists())
        val tail = DebugLog.tail(1000)
        assertTrue(tail.last().endsWith("line 29"))
        assertTrue(tail.size in 4..29)
        assertEquals(tail.size, tail.map { it.substringAfter(' ').substringAfter(' ') }.toSet().size)
    }
}
