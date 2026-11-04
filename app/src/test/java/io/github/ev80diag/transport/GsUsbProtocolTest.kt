package io.github.ev80diag.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GsUsbProtocolTest {
    private fun le32(vararg v: Long) = ByteArray(v.size * 4) { i -> ((v[i / 4] shr (8 * (i % 4))) and 0xFF).toByte() }

    @Test fun bitTiming_48MHz_500k() {
        val t = GsUsbProtocol.bitTiming(48_000_000, 1, 1024, 16, 8)
        assertEquals(6, t.brp)
        assertEquals(13, t.propSeg + t.phaseSeg1)
        assertEquals(2, t.phaseSeg2)
        assertEquals(1, t.sjw)
    }

    @Test fun bitTiming_80MHz_500k() {
        val t = GsUsbProtocol.bitTiming(80_000_000, 1, 1024, 16, 8)
        assertEquals(10, t.brp)
        assertEquals(16, 1 + t.propSeg + t.phaseSeg1 + t.phaseSeg2)
    }

    @Test fun bitTiming_toBytes_isFiveLittleEndianU32() {
        val t = GsUsbProtocol.bitTiming(48_000_000, 1, 1024, 16, 8)
        assertArrayEquals(le32(t.propSeg.toLong(), t.phaseSeg1.toLong(), t.phaseSeg2.toLong(), t.sjw.toLong(), t.brp.toLong()), t.toBytes())
    }

    @Test fun modeStart_listenOnly() {
        assertArrayEquals(byteArrayOf(1, 0, 0, 0, 1, 0, 0, 0), GsUsbProtocol.modeStart(GsUsbProtocol.MODE_LISTEN_ONLY))
        assertArrayEquals(byteArrayOf(1, 0, 0, 0, 0x11, 0, 0, 0),
            GsUsbProtocol.modeStart(GsUsbProtocol.MODE_LISTEN_ONLY or GsUsbProtocol.MODE_HW_TIMESTAMP))
    }

    @Test fun modeReset_isZero() {
        assertArrayEquals(ByteArray(8), GsUsbProtocol.modeReset())
    }

    @Test fun parseBtConst() {
        val c = GsUsbProtocol.parseBtConst(le32(0x13, 48_000_000, 1, 16, 1, 8, 4, 1, 1024, 1))!!
        assertEquals(48_000_000, c.fclkHz)
        assertEquals(true, c.hasHwTimestamp)       // feature bit 4 (0x10) is set in 0x13
        assertEquals(true, c.supportsListenOnly)   // feature bit 0
        assertEquals(8, c.tseg2Max)
        assertEquals(1024, c.brpMax)
        assertNull(GsUsbProtocol.parseBtConst(ByteArray(10)))
    }

    private fun hostFrame(echo: Long, canId: Long, dlc: Int, data: String, ts: Long? = null): ByteArray {
        val head = le32(echo, canId) + byteArrayOf(dlc.toByte(), 0, 0, 0)
        val d = data.chunked(2).map { it.toInt(16).toByte() }.toByteArray().copyOf(8)
        return head + d + (ts?.let { le32(it) } ?: ByteArray(0))
    }

    @Test fun parse_rxFrame() {
        val f = GsUsbProtocol.parseHostFrame(hostFrame(0xFFFFFFFFL, 0x550, 8, "010EF6232D020000"), 20, false, 777L)!!
        assertEquals(0x550, f.id)
        assertEquals(8, f.data.size)
        assertEquals(0x01.toByte(), f.data[0])
        assertEquals(777L, f.timestamp)
    }

    @Test fun parse_shortDlc() {
        val f = GsUsbProtocol.parseHostFrame(hostFrame(0xFFFFFFFFL, 0x322, 3, "4864C9"), 20, false, 0L)!!
        assertEquals(3, f.data.size)
    }

    @Test fun parse_skipsEchoErrorExtendedRtrAndTruncated() {
        assertNull(GsUsbProtocol.parseHostFrame(hostFrame(5, 0x550, 8, "00"), 20, false, 0L))                  // echo of own TX
        assertNull(GsUsbProtocol.parseHostFrame(hostFrame(0xFFFFFFFFL, 0x20000004L, 8, "00"), 20, false, 0L))   // error frame
        assertNull(GsUsbProtocol.parseHostFrame(hostFrame(0xFFFFFFFFL, 0x80000550L, 8, "00"), 20, false, 0L))   // extended ID
        assertNull(GsUsbProtocol.parseHostFrame(hostFrame(0xFFFFFFFFL, 0x40000550L, 0, ""), 20, false, 0L))     // RTR
        assertNull(GsUsbProtocol.parseHostFrame(ByteArray(12), 12, false, 0L))                                  // truncated
    }

    @Test fun parse_hwTimestampFrame_is24Bytes() {
        val buf = hostFrame(0xFFFFFFFFL, 0x200, 8, "0600006404010000", ts = 1_234_567)
        assertEquals(24, buf.size)
        assertEquals(0x200, GsUsbProtocol.parseHostFrame(buf, 24, true, 0L)!!.id)
        assertNull(GsUsbProtocol.parseHostFrame(buf, 20, true, 0L))    // too short for a timestamped frame
        assertEquals(1_234_567L, GsUsbProtocol.hwTimestampUs(buf))
    }

    @Test fun hwClock_mapsToPhoneTimeAndSurvivesWrap() {
        val c = GsUsbProtocol.HwClock()
        assertEquals(1_000_000L, c.toMs(0xFFFF_FF00L, 1_000_000L))              // anchor
        assertEquals(1_000_000L + 1L, c.toMs(0xFFFF_FF00L + 1_000, 1_000_001L)) // +1000 µs = +1 ms
        // counter wrapped: 0x100 µs after 0x1_0000_0000 is 0x200 µs past the anchor
        val after = c.toMs(0x100L, 1_000_050L)
        assertEquals(1_000_000L, after)                                          // 0x200 µs < 1 ms
    }

    @Test fun noListenOnlyFeature_isRefused() {
        val c = GsUsbProtocol.parseBtConst(le32(0x12, 48_000_000, 1, 16, 1, 8, 4, 1, 1024, 1))!!
        assertFalse(c.supportsListenOnly)
    }

    @Test fun hwClock_reanchorsAfterLongSilence() {
        val c = GsUsbProtocol.HwClock()
        c.toMs(1_000L, 1_000_000L)
        // van asleep 50 min: the 32-bit µs counter wrapped once without any frame in between
        val now = 1_000_000L + 50 * 60_000L
        val hw = (1_000L + 50 * 60_000_000L) and 0xFFFF_FFFFL
        assertEquals(now, c.toMs(hw, now))
        assertEquals(now + 2, c.toMs(hw + 2_000, now + 3))                     // follows the counter again
    }
}
