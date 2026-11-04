package io.github.ev80diag.transport

import io.github.ev80diag.can.CanFrame

enum class SourceKind { STN_BT, GS_USB, REPLAY }

/** [fullLogging]: the adapter can deliver every ID without loss (STN, gs_usb, replay). */
data class SourceInfo(
    val kind: SourceKind,
    val name: String,
    val fullLogging: Boolean,
    val timestampResolutionMs: Int,
)

sealed interface SourceEvent {
    data class ErrorLine(val text: String) : SourceEvent
    data class Stats(val framesPerSec: Double) : SourceEvent
}

/** Thrown by [CanSource.run] when the adapter cannot listen without sending (plain ELM327, no silent mode): nothing was monitored. */
class AdapterRefusedException(reason: String) : Exception(reason)

/** Receives frames on the source's own thread; implementations must return quickly. */
interface FrameSink {
    fun onFrame(f: CanFrame)
    fun onEvent(e: SourceEvent) {}
}

/**
 * A listen-only CAN frame source. [run] returns when finished or cancelled; throws IOException if the link drops
 * and [AdapterRefusedException] if the adapter can't be used listen-only.
 */
interface CanSource {
    val info: SourceInfo
    suspend fun run(sink: FrameSink)
}
