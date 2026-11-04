package io.github.ev80diag

import io.github.ev80diag.can.CanFrame
import java.io.InputStream
import java.util.zip.GZIPInputStream
import org.junit.Assume.assumeTrue

/**
 * Test helper: reads a gzip candump fixture from `fixtures/` on the test classpath. The fixtures are real van logs
 * kept outside the repo (`../EV80-diag-testdata`, or `-Pev80.testdata=<dir>`); without them the test is skipped.
 */
object TestLogs {
    private val LINE = Regex("""^\((\d+)\.(\d{6})\) \S+ ([0-9A-F]{3})#([0-9A-F]*)$""")

    fun frame(id: Int, hex: String, ts: Long = 0L) =
        CanFrame(id, hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), ts)

    /** A file from the test-data folder (e.g. "fixtures/drive_1023.log.gz"); skips the test when it is missing. */
    fun resource(path: String): InputStream {
        val stream = javaClass.classLoader!!.getResourceAsStream(path)
        assumeTrue("test data $path not found (see EV80-diag-testdata)", stream != null)
        return stream!!
    }

    fun load(name: String): List<CanFrame> {
        return GZIPInputStream(resource("fixtures/$name")).bufferedReader().useLines { lines ->
            lines.mapNotNull { l ->
                LINE.matchEntire(l)?.let { m ->
                    frame(m.groupValues[3].toInt(16), m.groupValues[4],
                        m.groupValues[1].toLong() * 1000 + m.groupValues[2].toLong() / 1000)
                }
            }.toList()
        }
    }
}
