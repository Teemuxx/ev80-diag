package io.github.ev80diag.recording

import io.github.ev80diag.TestLogs.frame
import io.github.ev80diag.can.CanFrame
import io.github.ev80diag.transport.CandumpLine
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawLogWriterTest {
    private val base = 1_793_273_355_000L
    private fun tmp(): File = Files.createTempDirectory("raw").toFile()
    private fun f(i: Int) = frame(0x200 + (i % 8), "06000064040100" + "%02X".format(i % 256), base + i * 1000L)

    /** Decompresses as far as the file allows; a truncated last member just ends the text early. */
    private fun readText(file: File): String {
        val out = StringBuilder()
        GZIPInputStream(file.inputStream()).use { gz ->
            val buf = ByteArray(8192)
            try {
                while (true) { val n = gz.read(buf); if (n < 0) break; out.append(String(buf, 0, n, Charsets.US_ASCII)) }
            } catch (_: EOFException) { /* truncated member */ }
        }
        return out.toString()
    }

    private fun frames(text: String): List<CanFrame> = text.lines().mapNotNull { CandumpLine.parse(it) }

    @Test
    fun roundTrip_multiMember() {
        val w = RawLogWriter(tmp(), "s1", flushEveryMs = 5_000)
        val written = (0 until 10_000).map { f(it) }
        written.forEach { w.write(it) }
        w.close()
        assertEquals(written, frames(readText(w.logFile)))
    }

    @Test
    fun truncatedLastMember_earlierLinesReadable() {
        val w = RawLogWriter(tmp(), "s2", flushEveryMs = 5_000)
        (0 until 60).forEach { w.write(f(it)) }
        w.close()
        RandomAccessFile(w.logFile, "rw").use { it.setLength(it.length() - 30) }   // cut into the last member
        val got = frames(readText(w.logFile))
        assertTrue("recovered ${got.size}", got.size >= 50)
        assertEquals((0 until got.size).map { f(it) }, got)
    }

    @Test
    fun crashWithoutClose_keepsFlushedMembers() {
        val w = RawLogWriter(tmp(), "s3", flushEveryMs = 5_000)
        (0 until 60).forEach { w.write(f(it)) }                // no close(): the process was killed
        val got = frames(readText(w.logFile))
        assertTrue("recovered ${got.size}", got.size >= 50)
        assertEquals((0 until got.size).map { f(it) }, got)
    }

    @Test
    fun explicitFlush_makesEverythingReadable() {
        val w = RawLogWriter(tmp(), "s4", flushEveryMs = 3_600_000)
        (0 until 20).forEach { w.write(f(it)) }
        w.flush()
        assertEquals(20, frames(readText(w.logFile)).size)
        w.close()
    }

    @Test
    fun preRoll_keepsOnly120s() {
        val p = PreRollBuffer(windowMs = 120_000)
        (0..300).forEach { p.add(frame(0x200, "00", base + it * 1000L)) }
        val kept = p.drain()
        assertEquals(base + 180_000L, kept.first().timestamp)
        assertEquals(base + 300_000L, kept.last().timestamp)
        assertTrue(p.drain().isEmpty())
    }

    @Test
    fun marks_format() {
        val w = RawLogWriter(tmp(), "s5")
        w.mark(1_793_273_400_000L, "door open")
        w.mark(1_793_273_401_500L, "lock")
        w.close()
        assertEquals(listOf("(1793273400.000000) MARK door open", "(1793273401.500000) MARK lock"),
            w.marksFile.readLines())
    }

    @Test
    fun lineFormat_matchesLaptopTools() {
        val w = RawLogWriter(tmp(), "s6")
        w.write(f(1)); w.close()
        val re = Regex("""^\(\d+\.\d{6}\) can0 [0-9A-F]{3}#[0-9A-F]*$""")
        assertTrue(readText(w.logFile).lines().filter { it.isNotEmpty() }.all { re.matches(it) })
    }
}
