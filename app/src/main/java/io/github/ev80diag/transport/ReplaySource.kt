package io.github.ev80diag.transport

import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Replays a candump -L log (plain or gzip, detected by magic bytes) as if it came from an adapter.
 * [speed] 0 = as fast as possible, 1.0 = real time with the original timestamps, 2.0 = twice as fast.
 */
class ReplaySource(private val open: () -> InputStream, private val speed: Double = 0.0) : CanSource {
    override val info = SourceInfo(SourceKind.REPLAY, "Replay", fullLogging = true, timestampResolutionMs = 1)

    override suspend fun run(sink: FrameSink) = withContext(Dispatchers.IO) {
        val buffered = BufferedInputStream(open())
        buffered.mark(2)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()
        val stream = if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(buffered) else buffered
        var prev = -1L
        // Like a live adapter: report the link up at once, then the frame rate about once a second
        sink.onEvent(SourceEvent.Stats(0.0))
        var windowStart = System.currentTimeMillis()
        var windowFrames = 0
        stream.bufferedReader().use { reader ->
            var n = 0
            for (line in reader.lineSequence()) {
                val f = CandumpLine.parse(line) ?: continue
                if (speed > 0 && prev >= 0 && f.timestamp > prev) delay(((f.timestamp - prev) / speed).toLong())
                prev = f.timestamp
                sink.onFrame(f)
                windowFrames++
                if (++n % 1024 == 0) coroutineContext.ensureActive()
                val now = System.currentTimeMillis()
                if (now - windowStart >= 1000) {
                    sink.onEvent(SourceEvent.Stats(windowFrames * 1000.0 / (now - windowStart)))
                    windowFrames = 0; windowStart = now
                }
            }
        }
    }
}
