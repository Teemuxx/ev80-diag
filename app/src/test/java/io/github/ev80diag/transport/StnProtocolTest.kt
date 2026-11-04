package io.github.ev80diag.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StnProtocolTest {
    @Test fun isStn_trueForStnReply() {
        assertTrue(StnProtocol.isStn("STN2120 v5.10.3"))
        assertTrue(StnProtocol.isStn("\nSTN1110 v4.2.1\n"))
    }

    @Test fun isStn_falseForPlainElm() {
        assertFalse(StnProtocol.isStn("?"))
        assertFalse(StnProtocol.isStn("ELM327 v2.1"))
        assertFalse(StnProtocol.isStn(""))
    }

    @Test fun parse_frameLine_noSpaces() {
        val f = StnProtocol.parseMonitorLine("5104153D60CD70CD50C", 1234L)!!
        assertEquals(0x510, f.id)
        assertArrayEquals(
            byteArrayOf(0x41, 0x53, 0xD6.toByte(), 0x0C, 0xD7.toByte(), 0x0C, 0xD5.toByte(), 0x0C), f.data)
        assertEquals(1234L, f.timestamp)
    }

    @Test fun parse_frameLine_withSpaces() {
        val f = StnProtocol.parseMonitorLine("550 01 0E F6 23", 0L)!!
        assertEquals(0x550, f.id)
        assertEquals(4, f.data.size)
    }

    @Test fun parse_rejectsNonFrames() {
        assertNull(StnProtocol.parseMonitorLine("CAN ERROR", 0L))
        assertNull(StnProtocol.parseMonitorLine("OK", 0L))
        assertNull(StnProtocol.parseMonitorLine("550", 0L))            // no data bytes
        assertNull(StnProtocol.parseMonitorLine("5500", 0L))           // odd hex count
        assertNull(StnProtocol.parseMonitorLine("550010203040506070809", 0L)) // 9 data bytes
    }

    @Test fun classify_lines() {
        assertEquals(LineKind.CAN_ERROR, StnProtocol.classify("CAN ERROR"))
        assertEquals(LineKind.CAN_ERROR, StnProtocol.classify("CANERROR"))
        assertEquals(LineKind.BUFFER_FULL, StnProtocol.classify("BUFFER FULL"))
        assertEquals(LineKind.STOPPED, StnProtocol.classify("STOPPED"))
        assertEquals(LineKind.FRAME, StnProtocol.classify("2000600"))
        assertEquals(LineKind.OTHER, StnProtocol.classify("UNABLE TO CONNECT"))
    }

    @Test fun initCommands_neverContainObdRequests() {
        assertTrue(StnProtocol.initCommands.all { it.startsWith("AT") || it.startsWith("ST") })
        assertFalse(StnProtocol.initCommands.any { Regex("^[0-9A-F]{4}").containsMatchIn(it) })
        assertEquals("STI", StnProtocol.initCommands.last())
        assertEquals("STMA", StnProtocol.MONITOR_COMMAND)
    }

    @Test fun canError_doesNotRestart() {
        assertEquals(MonitorAction.CONTINUE, MonitorPolicy.onLine(LineKind.CAN_ERROR))
        assertEquals(MonitorAction.CONTINUE, MonitorPolicy.onLine(LineKind.FRAME))
    }

    @Test fun stoppedAndBufferFull_restart() {
        assertEquals(MonitorAction.RESTART, MonitorPolicy.onLine(LineKind.STOPPED))
        assertEquals(MonitorAction.RESTART, MonitorPolicy.onLine(LineKind.BUFFER_FULL))
    }

    @Test fun plainElm_isRefused_noMonitorStarted() {
        assertEquals(MonitorAction.REFUSE, MonitorPolicy.afterSti("?"))
        assertEquals(MonitorAction.REFUSE, MonitorPolicy.afterSti("ELM327 v2.1"))
        assertEquals(MonitorAction.CONTINUE, MonitorPolicy.afterSti("STN2120 v5.10.3"))
    }

    @Test fun silentMonitoringNotConfirmed_isRefused() {
        assertEquals(MonitorAction.CONTINUE, MonitorPolicy.afterCsm("OK"))
        assertEquals(MonitorAction.REFUSE, MonitorPolicy.afterCsm("?"))
        assertEquals(MonitorAction.REFUSE, MonitorPolicy.afterCsm(""))          // no answer within the timeout
    }
}
