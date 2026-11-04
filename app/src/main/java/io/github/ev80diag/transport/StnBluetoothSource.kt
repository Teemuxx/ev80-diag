package io.github.ev80diag.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import io.github.ev80diag.DebugLog
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * OBDLink / STN adapter over classic Bluetooth (SPP). Listen-only: configuration commands, then STMA.
 * The read loop only splits lines and hands frames to the sink; nothing else runs on this thread.
 */
@SuppressLint("MissingPermission")
class StnBluetoothSource(private val device: BluetoothDevice) : CanSource {
    override val info = SourceInfo(SourceKind.STN_BT, device.name ?: device.address,
        fullLogging = true, timestampResolutionMs = 200)

    override suspend fun run(sink: FrameSink) = withContext(Dispatchers.IO) {
        val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
        // connect() and read() block and ignore cancellation: closing the socket from here is what unblocks them.
        val closer = launch {
            try { awaitCancellation() } finally {
                runCatching { socket.outputStream.write("\r".toByteArray()) }   // any character ends STMA
                runCatching { socket.close() }
            }
        }
        try {
            socket.connect()
            val input = socket.inputStream
            val output = socket.outputStream
            DebugLog.log("STN: connected to ${device.address}")
            val replies = HashMap<String, String>()
            for (cmd in StnProtocol.initCommands) {
                val reply = command(input, output, cmd, if (cmd == "ATZ") 4000 else 2000)
                DebugLog.log("STN: $cmd -> ${reply.replace('\r', ' ').trim()}")
                replies[cmd] = reply
            }
            if (MonitorPolicy.afterSti(replies["STI"].orEmpty()) == MonitorAction.REFUSE) {
                DebugLog.log("STN: refused, adapter is not an STN chip")
                throw AdapterRefusedException("not an STN adapter")
            }
            if (MonitorPolicy.afterCsm(replies["ATCSM1"].orEmpty()) == MonitorAction.REFUSE) {
                DebugLog.log("STN: refused, silent monitoring (ATCSM1) not confirmed")
                throw AdapterRefusedException("silent monitoring not confirmed")
            }
            monitor(input, output, sink)
        } catch (e: IOException) {
            if (isActive) throw e   // link lost; when cancelled, the closer shut the socket on purpose
        } finally {
            closer.cancel()
        }
    }

    private suspend fun monitor(input: InputStream, output: OutputStream, sink: FrameSink) {
        output.write("${StnProtocol.MONITOR_COMMAND}\r".toByteArray()); output.flush()
        val buf = ByteArray(4096)
        val line = StringBuilder()
        var restartPending = false
        var frames = 0L
        var windowStart = System.currentTimeMillis()
        while (coroutineContext.isActive) {
            val n = input.read(buf)
            if (n < 0) throw IOException("stream closed")
            val now = System.currentTimeMillis()
            for (i in 0 until n) {
                val c = buf[i].toInt().toChar()
                if (c == '>') {
                    // back at the prompt after STOPPED / BUFFER FULL: only now does the chip take a new command
                    line.setLength(0)
                    if (restartPending) { output.write("${StnProtocol.MONITOR_COMMAND}\r".toByteArray()); output.flush(); restartPending = false }
                    continue
                }
                if (c != '\r' && c != '\n') { line.append(c); continue }
                if (line.isEmpty()) continue
                val text = line.toString(); line.setLength(0)
                val kind = StnProtocol.classify(text)
                if (kind == LineKind.FRAME) {
                    StnProtocol.parseMonitorLine(text, now)?.let { sink.onFrame(it); frames++ }
                } else if (kind != LineKind.PROMPT) {
                    if (kind != LineKind.OTHER) sink.onEvent(SourceEvent.ErrorLine(text.trim()))
                    DebugLog.log("STN: $text")
                    if (MonitorPolicy.onLine(kind) == MonitorAction.RESTART) restartPending = true
                }
            }
            if (now - windowStart >= 1000) {
                sink.onEvent(SourceEvent.Stats(frames * 1000.0 / (now - windowStart)))
                frames = 0; windowStart = now
            }
        }
    }

    private suspend fun command(input: InputStream, output: OutputStream, cmd: String, timeoutMs: Long): String {
        while (input.available() > 0) input.read()                 // drop leftovers
        output.write("$cmd\r".toByteArray()); output.flush()
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                sb.append(input.read().toChar())
                if (sb.endsWith(">")) break
            } else delay(5)
        }
        return sb.toString().replace(">", "").trim()
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
