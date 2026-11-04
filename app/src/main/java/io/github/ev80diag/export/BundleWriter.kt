package io.github.ev80diag.export

import java.io.File
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** What travels with a shared session; the bundle's `session.json`. [schema] changes when fields change meaning. */
data class SessionMeta(
    val schema: Int = 1,
    val appVersion: String,
    val adapter: String,
    val timestampResolutionMs: Int,
    val type: String,
    val outcome: String?,
    val startMs: Long,
    val endMs: Long?,
    val chargerName: String?,
    val chargedKwh: Double?,
    val framesTotal: Long,
    val droppedFrames: Long,
    val profileName: String,
)

/**
 * The `.ev80` share bundle: a zip with session.json, can.log.gz (the raw candump text, stored as is
 * because it is already gzip), session.marks and samples.csv.gz. Entries without data are left out.
 */
object BundleWriter {
    fun write(out: File, meta: SessionMeta, rawLog: File?, marks: File?, samplesCsv: Sequence<String>): File {
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("session.json")); zip.write(json(meta).toByteArray(Charsets.UTF_8)); zip.closeEntry()
            if (rawLog != null && rawLog.exists()) {
                zip.setLevel(Deflater.NO_COMPRESSION)
                zip.putNextEntry(ZipEntry("can.log.gz")); rawLog.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            }
            if (marks != null && marks.exists() && marks.length() > 0) {
                zip.putNextEntry(ZipEntry("session.marks")); marks.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("samples.csv.gz"))
            GZIPOutputStream(zip).let { gz ->
                samplesCsv.forEach { gz.write(it.toByteArray(Charsets.UTF_8)); gz.write('\n'.code) }
                gz.finish()
            }
            zip.closeEntry()
        }
        return out
    }

    fun json(m: SessionMeta): String = buildString {
        append("{\n")
        field("schema", m.schema); field("appVersion", m.appVersion); field("adapter", m.adapter)
        field("timestampResolutionMs", m.timestampResolutionMs); field("type", m.type); field("outcome", m.outcome)
        field("startMs", m.startMs); field("endMs", m.endMs); field("chargerName", m.chargerName)
        field("chargedKwh", m.chargedKwh); field("framesTotal", m.framesTotal); field("droppedFrames", m.droppedFrames)
        field("profileName", m.profileName, last = true)
        append("}\n")
    }

    private fun StringBuilder.field(name: String, value: Any?, last: Boolean = false) {
        append("  \"").append(name).append("\": ")
        when (value) {
            null -> append("null")
            is String -> quote(value)
            else -> append(value.toString())
        }
        append(if (last) "\n" else ",\n")
    }

    private fun StringBuilder.quote(s: String) {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}
