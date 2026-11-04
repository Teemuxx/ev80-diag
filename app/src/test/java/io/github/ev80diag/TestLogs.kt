package io.github.ev80diag

import io.github.ev80diag.can.CanFrame
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** Test helper: reads a gzip candump fixture from src/test/resources/fixtures (cut from real van logs by tools/make_fixtures.sh). */
object TestLogs {
    private val LINE = Regex("""^\((\d+)\.(\d{6})\) \S+ ([0-9A-F]{3})#([0-9A-F]*)$""")

    fun frame(id: Int, hex: String, ts: Long = 0L) =
        CanFrame(id, hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), ts)

    /** A test resource, e.g. "fixtures/drive_1023.log.gz". */
    fun resource(path: String): InputStream = checkNotNull(javaClass.classLoader!!.getResourceAsStream(path)) { path }

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
