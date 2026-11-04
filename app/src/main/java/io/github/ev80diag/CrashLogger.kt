package io.github.ev80diag

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes uncaught exceptions to `<external files>/crash-<ts>.txt` before delegating to the
 * previous handler, so a crash that happens away from a debugger can still be diagnosed
 * (shown on the Debug screen, retrievable via file manager / adb pull).
 */
object CrashLogger {

    private const val PREFIX = "crash-"
    private const val KEEP_FILES = 5

    fun install(context: Context) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                val rt = Runtime.getRuntime()
                val header = "Thread: ${thread.name}\n" +
                        "Heap: used=${(rt.totalMemory() - rt.freeMemory()) shr 20} MB, max=${rt.maxMemory() shr 20} MB\n\n"
                File(dir, "$PREFIX$ts.txt").writeText(header + throwable.stackTraceToString())
                files(dir).drop(KEEP_FILES).forEach { it.delete() }
            } catch (_: Throwable) {
                // never mask the original crash
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Text of the most recent crash report, or null if there is none. */
    fun lastCrash(context: Context): String? {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val f = files(dir).firstOrNull() ?: return null
        return "${f.name}\n\n${f.readText()}"
    }

    fun clear(context: Context) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        files(dir).forEach { it.delete() }
    }

    private fun files(dir: File): List<File> =
        dir.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.name } ?: emptyList()
}
