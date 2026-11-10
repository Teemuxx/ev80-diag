package io.github.ev80diag.export

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

/** Phone, 2026-11-10: Signal and Element refused the two-file text share ("could not load share data"); now one text file. */
class DebugReportTest {
    private val dir: File = Files.createTempDirectory("logs").toFile()

    @Test fun oneFile_headerThenOlderThenNewer() {
        File(dir, "debug.log.1").writeText("old 1\nold 2\n")
        File(dir, "debug.log").writeText("new 1\n")
        val out = DebugReport.write(File(dir, "share/ev80_debug.txt"), dir, listOf("EV80-diag 0.4.0-dev", "Android 17"))
        assertEquals(listOf("EV80-diag 0.4.0-dev", "Android 17", "", "old 1", "old 2", "new 1"), out.readLines())
    }

    @Test fun sharedAsPlainText() = assertEquals("text/plain", SessionExporter.mimeOf(File("ev80_debug_20261110_1600.txt")))

    @Test fun noRotatedFile_noLogAtAll() {
        assertEquals(listOf("h", "", "(debug log is empty)"), DebugReport.write(File(dir, "a.txt"), dir, listOf("h")).readLines())
        File(dir, "debug.log").writeText("only\n")
        assertEquals(listOf("h", "", "only"), DebugReport.write(File(dir, "b.txt"), dir, listOf("h")).readLines())
    }
}
