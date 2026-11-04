package io.github.ev80diag.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import io.github.ev80diag.DebugLog
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * gs_usb / candleLight (CANable) adapter over USB OTG, 500 kbit/s, **listen-only**.
 * Never writes to the bulk OUT endpoint, so the adapter cannot transmit or even acknowledge.
 */
class GsUsbSource(private val usb: UsbManager, private val device: UsbDevice) : CanSource {
    override val info = SourceInfo(SourceKind.GS_USB, device.productName ?: "gs_usb ${device.deviceName}",
        fullLogging = true, timestampResolutionMs = 10)

    override suspend fun run(sink: FrameSink) = withContext(Dispatchers.IO) {
        if (!usb.hasPermission(device)) throw IOException("no USB permission for ${device.deviceName}")
        val conn = usb.openDevice(device) ?: throw IOException("cannot open ${device.deviceName}")
        val iface = device.getInterface(0)
        var started = false
        try {
            if (!conn.claimInterface(iface, true)) throw IOException("cannot claim interface")
            val bulkIn = (0 until iface.endpointCount).map { iface.getEndpoint(it) }
                .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN }
                ?: throw IOException("no bulk IN endpoint")
            val index = iface.id

            ctrlOut(conn, GsUsbProtocol.BREQ_HOST_FORMAT, index, GsUsbProtocol.hostFormat, value = 1)   // as the Linux driver sends it
            val btBuf = ByteArray(40)
            val got = conn.controlTransfer(CTRL_IN, GsUsbProtocol.BREQ_BT_CONST, 0, index, btBuf, btBuf.size, TIMEOUT_MS)
            val bt = (if (got >= 40) GsUsbProtocol.parseBtConst(btBuf) else null) ?: throw IOException("BT_CONST read failed ($got)")
            if (!bt.supportsListenOnly) {
                DebugLog.log("USB: refused, firmware has no listen-only mode (feature=0x${bt.feature.toString(16)})")
                throw AdapterRefusedException("gs_usb firmware without listen-only mode")
            }
            val timing = GsUsbProtocol.bitTiming(bt.fclkHz, bt.brpMin, bt.brpMax, bt.tseg1Max, bt.tseg2Max)
            ctrlOut(conn, GsUsbProtocol.BREQ_BITTIMING, index, timing.toBytes())
            var flags = GsUsbProtocol.MODE_LISTEN_ONLY
            if (bt.hasHwTimestamp) flags = flags or GsUsbProtocol.MODE_HW_TIMESTAMP
            ctrlOut(conn, GsUsbProtocol.BREQ_MODE, index, GsUsbProtocol.modeStart(flags))
            started = true
            DebugLog.log("USB: ${device.deviceName} fclk=${bt.fclkHz} $timing flags=$flags")
            readLoop(conn, bulkIn, bt.hasHwTimestamp, sink)
        } finally {
            if (started) runCatching { ctrlOut(conn, GsUsbProtocol.BREQ_MODE, iface.id, GsUsbProtocol.modeReset()) }
            runCatching { conn.releaseInterface(iface) }
            conn.close()
        }
    }

    private suspend fun readLoop(conn: UsbDeviceConnection, ep: UsbEndpoint, hwTs: Boolean, sink: FrameSink) {
        val buf = ByteArray(64)
        val clock = GsUsbProtocol.HwClock()
        var frames = 0L
        var windowStart = System.currentTimeMillis()
        var fastFailures = 0
        while (coroutineContext.isActive) {
            val before = System.currentTimeMillis()
            val n = conn.bulkTransfer(ep, buf, buf.size, READ_TIMEOUT_MS)
            val now = System.currentTimeMillis()
            // A timeout returns -1 after READ_TIMEOUT_MS; an unplugged adapter returns -1 at once, forever.
            if (n < 0 && now - before < READ_TIMEOUT_MS / 4) {
                if (device !in usb.deviceList.values || ++fastFailures >= 50) throw IOException("USB adapter gone (${device.deviceName})")
                delay(20)
                continue
            }
            fastFailures = 0
            if (n > 0) {
                val ts = if (hwTs && n >= GsUsbProtocol.FRAME_SIZE_TS) clock.toMs(GsUsbProtocol.hwTimestampUs(buf), now) else now
                GsUsbProtocol.parseHostFrame(buf, n, hwTs, ts)?.let { sink.onFrame(it); frames++ }
            }
            if (now - windowStart >= 1000) {
                sink.onEvent(SourceEvent.Stats(frames * 1000.0 / (now - windowStart)))
                frames = 0; windowStart = now
            }
        }
    }

    private fun ctrlOut(conn: UsbDeviceConnection, request: Int, index: Int, data: ByteArray, value: Int = 0) {
        val r = conn.controlTransfer(CTRL_OUT, request, value, index, data, data.size, TIMEOUT_MS)
        if (r < 0) throw IOException("control request $request failed ($r)")
    }

    private companion object {
        const val CTRL_OUT = 0x41   // host to device, vendor, interface
        const val CTRL_IN = 0xC1    // device to host, vendor, interface
        const val TIMEOUT_MS = 1000
        const val READ_TIMEOUT_MS = 200
    }
}
