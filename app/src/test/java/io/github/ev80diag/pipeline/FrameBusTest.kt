package io.github.ev80diag.pipeline

import io.github.ev80diag.TestLogs.frame
import io.github.ev80diag.transport.SourceEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameBusTest {
    @Test
    fun allConsumersReceiveInOrder() = runBlocking {
        val bus = FrameBus(capacity = 64)
        val a = bus.subscribe("a")
        val b = bus.subscribe("b")
        repeat(50) { bus.onFrame(frame(0x200, "00", it.toLong())) }
        for (sub in listOf(a, b)) {
            val got = (0 until 50).map { sub.frames.receive().timestamp }
            assertEquals((0L until 50L).toList(), got)
            assertEquals(0, sub.dropped.get())
        }
    }

    @Test
    fun slowConsumer_dropsCounted_otherConsumerComplete() = runBlocking {
        val bus = FrameBus(capacity = 100)
        val slow = bus.subscribe("db")                    // never reads
        val fast = bus.subscribe("raw", capacity = 2_000)
        repeat(1_000) { bus.onFrame(frame(0x510, "00", it.toLong())) }
        assertEquals(1_000, (0 until 1_000).map { fast.frames.receive() }.size)
        assertEquals(0, fast.dropped.get())
        assertEquals(900, slow.dropped.get())
        // what the slow consumer kept are the first 100 frames, still in order
        assertEquals(0L, slow.frames.receive().timestamp)
    }

    @Test
    fun blockingMode_waitsInsteadOfDropping() = runBlocking {
        val bus = FrameBus(capacity = 10, blocking = true)
        val sub = bus.subscribe("x")
        val producer = launch(kotlinx.coroutines.Dispatchers.IO) {
            repeat(1_000) { bus.onFrame(frame(0x200, "00", it.toLong())) }
            bus.close()
        }
        var n = 0
        for (f in sub.frames) { assertEquals(n.toLong(), f.timestamp); n++ }
        producer.join()
        assertEquals(1_000, n)
        assertEquals(0, sub.dropped.get())
    }

    @Test
    fun events_areDelivered() = runBlocking {
        val bus = FrameBus()
        val job = launch { assertTrue(bus.events.first() is SourceEvent.ErrorLine) }
        kotlinx.coroutines.yield()
        bus.onEvent(SourceEvent.ErrorLine("CAN ERROR"))
        job.join()
    }

    @Test
    fun close_endsConsumers() = runBlocking {
        val bus = FrameBus()
        val s = bus.subscribe("x")
        bus.onFrame(frame(0x200, "00"))
        bus.close()
        assertEquals(0x200, s.frames.receive().id)
        assertTrue(s.frames.receiveCatching().isClosed)
    }
}
