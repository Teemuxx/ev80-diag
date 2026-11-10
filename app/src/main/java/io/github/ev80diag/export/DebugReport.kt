package io.github.ev80diag.export

import java.io.File

/**
 * The debug report as one text file: [header] (app, Android, device), a blank line, then debug.log.1 and debug.log
 * from [logDir], oldest first. One plain file, because messengers refuse a multi-file text share.
 */
object DebugReport {
    fun write(out: File, logDir: File, header: List<String>): File {
        out.parentFile?.mkdirs()
        val logs = listOf(File(logDir, "debug.log.1"), File(logDir, "debug.log")).filter { it.exists() && it.length() > 0 }
        out.bufferedWriter().use { w ->
            header.forEach { w.write(it); w.write("\n") }
            w.write("\n")
            if (logs.isEmpty()) w.write("(debug log is empty)\n")
            logs.forEach { f -> f.reader().use { it.copyTo(w) } }
        }
        return out
    }
}
