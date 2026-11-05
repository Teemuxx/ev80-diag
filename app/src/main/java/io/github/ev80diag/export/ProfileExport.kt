package io.github.ev80diag.export

import io.github.ev80diag.data.store.CellBlob
import io.github.ev80diag.data.store.CellSnapshotEntity
import io.github.ev80diag.data.store.MarkEntity
import io.github.ev80diag.data.store.SessionEntity
import java.io.File
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Everything recorded for one van profile as one zip: profile.json, sessions.csv (one row per session), marks.csv,
 * sessions/<id>_<name>.ev80 (each session's normal share bundle; the id keeps two sessions of the same minute apart) and cells.csv.gz (all cell snapshots).
 */
object ProfileExport {
    const val SESSIONS_HEADER = "id,type,outcome,start_ms,end_ms,charger,charged_kwh,distance_km,drive_kwh,adapter,adapter_name,frames_total,dropped_frames"
    val CELLS_HEADER = "ts_ms,charging," + (1..104).joinToString(",") { "c$it" }

    fun sessionsCsv(sessions: List<SessionEntity>): Sequence<String> = sequenceOf(SESSIONS_HEADER) + sessions.asSequence().map { s ->
        listOf(s.id, s.type, s.outcome, s.startMs, s.endMs, s.chargerName, s.chargedKwh, s.distanceKm, s.driveKwh,
            s.adapterKind, s.adapterName, s.framesTotal, s.droppedFrames).joinToString(",") { csv(it) }
    }

    fun marksCsv(marks: List<MarkEntity>): Sequence<String> =
        sequenceOf("ts_ms,session_id,text") + marks.asSequence().map { m -> listOf(m.tsMs, m.sessionId, m.text).joinToString(",") { csv(it) } }

    /** Header first, then one row per snapshot; feed it page by page for long histories. */
    fun cellsCsv(snapshots: List<CellSnapshotEntity>, header: Boolean = true): Sequence<String> =
        (if (header) sequenceOf(CELLS_HEADER) else emptySequence()) + snapshots.asSequence().map { c ->
            "${c.tsMs},${if (c.charging) 1 else 0}," + CellBlob.unpack(c.mv).joinToString(",")
        }

    fun write(out: File, profileJson: String, sessionsCsv: Sequence<String>, marksCsv: Sequence<String>,
              bundles: List<Pair<Long, File>>, cellsCsv: Sequence<String>): File {
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("profile.json")); zip.write(profileJson.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("sessions.csv"))
            sessionsCsv.forEach { zip.write(it.toByteArray(Charsets.UTF_8)); zip.write('\n'.code) }
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("marks.csv"))
            marksCsv.forEach { zip.write(it.toByteArray(Charsets.UTF_8)); zip.write('\n'.code) }
            zip.closeEntry()
            zip.setLevel(Deflater.NO_COMPRESSION)   // bundles are zips already
            for ((id, b) in bundles) { zip.putNextEntry(ZipEntry("sessions/${id}_${b.name}")); b.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            zip.putNextEntry(ZipEntry("cells.csv.gz"))
            GZIPOutputStream(zip).let { gz ->
                cellsCsv.forEach { gz.write(it.toByteArray(Charsets.UTF_8)); gz.write('\n'.code) }
                gz.finish()
            }
            zip.closeEntry()
        }
        return out
    }

    fun profileJson(name: String, appVersion: String, exportedMs: Long, sessions: Int): String = buildString {
        append("{\n  \"schema\": 1,\n  \"appVersion\": "); with(BundleWriter) { quote(appVersion) }
        append(",\n  \"profileName\": "); with(BundleWriter) { quote(name) }
        append(",\n  \"exportedMs\": ").append(exportedMs).append(",\n  \"sessions\": ").append(sessions).append("\n}\n")
    }

    private fun csv(v: Any?): String {
        val s = v?.toString() ?: return ""
        return if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}
