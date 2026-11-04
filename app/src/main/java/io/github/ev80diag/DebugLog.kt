package io.github.ev80diag

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Rotating debug log in a file (survives restarts): debug.log and debug.log.1, [maxBytes] each. */
object DebugLog {
    private var dir: File? = null
    private var maxBytes = 1_000_000L
    private val lock = Any()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) = init(File(context.filesDir, "logs"))

    fun init(logDir: File, maxBytes: Long = 1_000_000L) {
        logDir.mkdirs()
        dir = logDir
        this.maxBytes = maxBytes
    }

    fun file(): File = File(requireNotNull(dir) { "DebugLog.init not called" }, "debug.log")

    fun log(msg: String) {
        android.util.Log.d("EV80", msg)
        val d = dir ?: return
        synchronized(lock) {
            val f = File(d, "debug.log")
            if (f.length() > maxBytes) {
                val old = File(d, "debug.log.1")
                old.delete()
                f.renameTo(old)
            }
            f.appendText("${fmt.format(Date())} $msg\n")
        }
    }

    /** The last [n] lines across both files, oldest first. */
    fun tail(n: Int = 200): List<String> {
        val d = dir ?: return emptyList()
        synchronized(lock) {
            val lines = listOf(File(d, "debug.log.1"), File(d, "debug.log"))
                .filter { it.exists() }.flatMap { it.readLines() }
            return lines.takeLast(n)
        }
    }
}
