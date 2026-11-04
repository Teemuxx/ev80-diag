package io.github.ev80diag.recording

import io.github.ev80diag.can.CanFrame

/** Keeps the most recent [windowMs] of frames (by frame timestamp) so a session can start with its lead-in. */
class PreRollBuffer(private val windowMs: Long = 120_000) {
    private val frames = ArrayDeque<CanFrame>()

    @Synchronized
    fun add(f: CanFrame) {
        frames.addLast(f)
        while (frames.isNotEmpty() && frames.first().timestamp < f.timestamp - windowMs) frames.removeFirst()
    }

    /** Returns the buffered frames oldest first and empties the buffer. */
    @Synchronized
    fun drain(): List<CanFrame> = frames.toList().also { frames.clear() }
}
