package io.github.ev80diag.recording

import io.github.ev80diag.can.CanFrame
import io.github.ev80diag.transport.CandumpLine
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Writes candump text into one gzip file made of many short members, so a killed process loses at
 * most the member being written ([flushEveryMs] of frame time). `zcat` and Java's GZIPInputStream
 * read the concatenated members as one stream. Marks go to a sibling `.marks` file (can_live.py format).
 */
class RawLogWriter(dir: File, baseName: String, private val flushEveryMs: Long = 5_000) : Closeable {
    val logFile = File(dir, "$baseName.log.gz")
    val marksFile = File(dir, "$baseName.marks")
    private val raw: FileOutputStream
    private var gz: MemberStream? = null
    private var memberStartTs = 0L
    private var closed = false

    init {
        dir.mkdirs()
        raw = FileOutputStream(logFile, true)
    }

    @Synchronized
    fun write(f: CanFrame) {
        if (closed) return
        val out = gz ?: MemberStream(raw).also { gz = it; memberStartTs = f.timestamp }
        out.write(CandumpLine.format(f).toByteArray(Charsets.US_ASCII))
        out.write('\n'.code)
        if (f.timestamp - memberStartTs >= flushEveryMs) finishMember()
    }

    /** Ends the current member now, so everything written so far is on disk. */
    @Synchronized
    fun flush() { if (!closed) finishMember() }

    @Synchronized
    fun mark(tsMs: Long, text: String) {
        marksFile.appendText(markLine(tsMs, text) + "\n")
    }

    @Synchronized
    override fun close() {
        if (closed) return
        finishMember()
        raw.close()
        closed = true
    }

    private fun finishMember() {
        gz?.let { it.finish(); it.release(); raw.flush() }
        gz = null
    }

    /** finish() alone keeps the native Deflater until GC; at one member per 5 s that adds up over a long charge. */
    private class MemberStream(out: FileOutputStream) : GZIPOutputStream(out) {
        fun release() = def.end()
    }

    companion object {
        /** `(seconds.micros) MARK <text>`, the format of can_live.py's .marks files. */
        fun markLine(tsMs: Long, text: String) = "(${tsMs / 1000}.${(tsMs % 1000).toString().padStart(3, '0')}000) MARK $text"
    }
}
