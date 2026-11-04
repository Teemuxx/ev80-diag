package io.github.ev80diag.pipeline

import io.github.ev80diag.can.CanFrame
import io.github.ev80diag.transport.FrameSink
import io.github.ev80diag.transport.SourceEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class Subscription internal constructor(
    val name: String,
    internal val channel: Channel<CanFrame>,
) {
    val frames: ReceiveChannel<CanFrame> get() = channel
    /** Frames this consumer missed because its buffer was full. */
    val dropped = AtomicLong()
}

/**
 * Fans frames out to independent consumers. Live, the source thread never blocks: when one consumer's
 * buffer is full, only that consumer loses frames (counted in [Subscription.dropped]).
 * With [blocking] (replay of a log file, whose producer is far faster than real time) the source waits
 * for a full consumer instead, so nothing is dropped and consumers stay roughly in step.
 */
class FrameBus(private val capacity: Int = 16_384, private val blocking: Boolean = false) : FrameSink {
    private val subs = CopyOnWriteArrayList<Subscription>()
    private val _events = MutableSharedFlow<SourceEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<SourceEvent> = _events

    fun subscribe(name: String, capacity: Int = this.capacity): Subscription =
        Subscription(name, Channel(capacity)).also { subs.add(it) }

    override fun onFrame(f: CanFrame) {
        for (s in subs) {
            if (blocking) s.channel.trySendBlocking(f) else if (s.channel.trySend(f).isFailure) s.dropped.incrementAndGet()
        }
    }

    override fun onEvent(e: SourceEvent) { _events.tryEmit(e) }

    fun close() { for (s in subs) s.channel.close() }
}
