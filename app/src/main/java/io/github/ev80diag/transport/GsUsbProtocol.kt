package io.github.ev80diag.transport

import io.github.ev80diag.can.CanFrame

/**
 * gs_usb / candleLight protocol (the Linux gs_usb driver's structures). Everything here only
 * builds or parses bytes. Listen-only is enforced by the mode flag; the source never writes to
 * the bulk OUT endpoint.
 */
object GsUsbProtocol {
    const val BREQ_HOST_FORMAT = 0
    const val BREQ_BITTIMING = 1
    const val BREQ_MODE = 2
    const val BREQ_BT_CONST = 4

    const val MODE_LISTEN_ONLY = 1
    const val MODE_HW_TIMESTAMP = 1 shl 4
    const val FEATURE_LISTEN_ONLY = 1
    const val FEATURE_HW_TIMESTAMP = 1 shl 4

    const val FRAME_SIZE = 20
    const val FRAME_SIZE_TS = 24
    private const val ECHO_RX = 0xFFFFFFFFL
    private const val ERR_FLAG = 0x20000000L
    private const val EXT_FLAG = 0x80000000L
    private const val RTR_FLAG = 0x40000000L

    /** The adapters of res/xml/usb_device_filter.xml: candleLight/CANable (1d50:606f) and pid.codes candleLight (1209:2323). */
    fun isSupported(vendorId: Int, productId: Int) =
        (vendorId == 0x1d50 && productId == 0x606f) || (vendorId == 0x1209 && productId == 0x2323)

    val hostFormat: ByteArray = le32(0x0000BEEFL)

    data class BitTiming(val propSeg: Int, val phaseSeg1: Int, val phaseSeg2: Int, val sjw: Int, val brp: Int) {
        fun toBytes(): ByteArray = le32(propSeg.toLong(), phaseSeg1.toLong(), phaseSeg2.toLong(), sjw.toLong(), brp.toLong())
    }

    data class BtConst(
        val feature: Long, val fclkHz: Int, val tseg1Min: Int, val tseg1Max: Int, val tseg2Min: Int,
        val tseg2Max: Int, val sjwMax: Int, val brpMin: Int, val brpMax: Int, val brpInc: Int,
    ) {
        val hasHwTimestamp: Boolean get() = feature and FEATURE_HW_TIMESTAMP.toLong() != 0L
        /** Firmware without this bit ignores the listen-only flag and would acknowledge frames: such a device is refused. */
        val supportsListenOnly: Boolean get() = feature and FEATURE_LISTEN_ONLY.toLong() != 0L
    }

    fun parseBtConst(b: ByteArray): BtConst? {
        if (b.size < 40) return null
        val v = LongArray(10) { u32(b, it * 4) }
        return BtConst(v[0], v[1].toInt(), v[2].toInt(), v[3].toInt(), v[4].toInt(), v[5].toInt(),
            v[6].toInt(), v[7].toInt(), v[8].toInt(), v[9].toInt())
    }

    /** Prefers 16 time quanta with the sample point at 87.5 %; tries other totals if the clock needs it. */
    fun bitTiming(fclkHz: Int, brpMin: Int, brpMax: Int, tseg1Max: Int, tseg2Max: Int, bitrate: Int = 500_000): BitTiming {
        for (tq in listOf(16, 15, 14, 13, 12, 10, 8, 20, 25)) {
            if (fclkHz % (bitrate * tq) != 0) continue
            val brp = fclkHz / (bitrate * tq)
            if (brp !in brpMin..brpMax) continue
            val tseg2 = maxOf(1, Math.round(tq * 0.125f))
            val tseg1 = tq - 1 - tseg2
            if (tseg1 > tseg1Max || tseg2 > tseg2Max) continue
            return BitTiming(propSeg = 1, phaseSeg1 = tseg1 - 1, phaseSeg2 = tseg2, sjw = 1, brp = brp)
        }
        throw IllegalArgumentException("no bit timing for $bitrate bit/s at $fclkHz Hz")
    }

    fun modeStart(flags: Int): ByteArray = le32(1, flags.toLong())
    fun modeReset(): ByteArray = le32(0, 0)

    /** Returns a received frame, or null for echoes of our own TX, error frames, extended IDs, RTR, or short buffers. */
    fun parseHostFrame(buf: ByteArray, len: Int, hwTimestamp: Boolean, rxTimeMs: Long): CanFrame? {
        if (len < (if (hwTimestamp) FRAME_SIZE_TS else FRAME_SIZE) || buf.size < len) return null
        if (u32(buf, 0) != ECHO_RX) return null
        val canId = u32(buf, 4)
        if (canId and (ERR_FLAG or EXT_FLAG or RTR_FLAG) != 0L) return null
        val dlc = (buf[8].toInt() and 0xFF).coerceAtMost(8)
        return CanFrame(canId.toInt(), buf.copyOfRange(12, 12 + dlc), rxTimeMs)
    }

    fun hwTimestampUs(buf: ByteArray): Long = u32(buf, 20)

    /**
     * Maps the adapter's 32-bit µs counter to phone time: anchored on the first frame, wrap-safe. A silence longer
     * than one counter period (71.6 min) can hide wraps, so it re-anchors whenever it drifts > 1 s from phone time.
     */
    class HwClock {
        private var anchorMs = 0L
        private var anchorUs = 0L
        private var last = 0L
        private var wraps = 0L
        private var started = false

        fun toMs(hwUs: Long, nowMs: Long): Long {
            if (!started) { started = true; anchorMs = nowMs; anchorUs = hwUs; last = hwUs; return nowMs }
            if (hwUs < last && last - hwUs > 0x8000_0000L) wraps++
            last = hwUs
            val elapsedUs = hwUs + wraps * 0x1_0000_0000L - anchorUs
            val ms = anchorMs + elapsedUs / 1000
            if (kotlin.math.abs(ms - nowMs) > REANCHOR_MS) { anchorMs = nowMs; anchorUs = hwUs; wraps = 0; return nowMs }
            return ms
        }

        private companion object { const val REANCHOR_MS = 1_000L }
    }

    private fun u32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    private fun le32(vararg v: Long) = ByteArray(v.size * 4) { i -> ((v[i / 4] shr (8 * (i % 4))) and 0xFF).toByte() }
}
