package io.github.ev80diag.transport

import io.github.ev80diag.can.CanFrame
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplaySourceTest {
    private class Collect : FrameSink {
        val frames = ArrayList<CanFrame>()
        val events = ArrayList<SourceEvent>()
        override fun onFrame(f: CanFrame) { frames.add(f) }
        override fun onEvent(e: SourceEvent) { events.add(e) }
    }

    private fun fixture(name: String): () -> java.io.InputStream {
        io.github.ev80diag.TestLogs.resource("fixtures/$name").close()   // skips the test here when the data is missing
        return { io.github.ev80diag.TestLogs.resource("fixtures/$name") }
    }

    @Test
    fun replay_gzFixture_deliversAllFramesInOrder() = runBlocking {
        val sink = Collect()
        ReplaySource(fixture("lx_state_changes.log.gz")).run(sink)
        assertEquals(54_202, sink.frames.size)
        assertTrue(sink.frames.zipWithNext().all { (a, b) -> a.timestamp <= b.timestamp })
    }

    @Test
    fun replay_plainTextStream_isDetected() = runBlocking {
        val text = "(1793273355.027116) can0 200#0600\n(1793273355.037116) can0 322#4864C9\n"
        val sink = Collect()
        ReplaySource({ text.byteInputStream() }).run(sink)
        assertEquals(listOf(0x200, 0x322), sink.frames.map { it.id })
    }

    @Test
    fun replay_info_isReplayKind() {
        val info = ReplaySource({ "".byteInputStream() }).info
        assertEquals(SourceKind.REPLAY, info.kind)
        assertTrue(info.fullLogging)
    }

    @Test
    fun replay_reportsLinkUpAndFrameRate_likeAnAdapter() = runBlocking {
        // 13 frames over 1.2 s, replayed in real time: the link is reported up at once, then a rate within the first second
        val text = (0..12).joinToString("") { "(%.6f) can0 200#0600\n".format(java.util.Locale.US, 1793273355.0 + it * 0.1) }
        val sink = Collect()
        ReplaySource({ text.byteInputStream() }, speed = 1.0).run(sink)
        assertTrue(sink.events.first() is SourceEvent.Stats)
        assertTrue(sink.events.filterIsInstance<SourceEvent.Stats>().any { it.framesPerSec > 5 })
    }
}
