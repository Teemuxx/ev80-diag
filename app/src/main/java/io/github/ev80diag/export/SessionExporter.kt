package io.github.ev80diag.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.github.ev80diag.data.store.Ev80Database
import io.github.ev80diag.data.store.SampleEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ExportFormat { BUNDLE, CSV, CANDUMP }

object SessionExporter {
    const val CSV_HEADER = "ts_ms,pack_v,pack_a,soc_bms,soc_app,insulation_kohm,temp_avg,temp_min,temp_max,cell_min_mv,cell_max_mv,energy_kwh"

    fun samplesCsv(samples: List<SampleEntity>): Sequence<String> = sequenceOf(CSV_HEADER) + samples.asSequence().map { s ->
        listOf(s.tsMs, s.packV, s.packA, s.socBms, s.socApp, s.insulationKOhm, s.tempAvg, s.tempMin, s.tempMax, s.cellMinMv, s.cellMaxMv, s.energyKwh)
            .joinToString(",") { it?.toString() ?: "" }
    }

    /** Builds the file for [sessionId] in the cache's share folder (shares older than an hour are removed), or null if there is nothing to export. */
    suspend fun build(ctx: Context, sessionId: Long, format: ExportFormat): File? = withContext(Dispatchers.IO) {
        val db = Ev80Database.get(ctx)
        val s = db.sessions().byId(sessionId) ?: return@withContext null
        // older shares go after an hour; a receiving app may still be uploading the last one
        val dir = File(ctx.cacheDir, "share").apply { mkdirs(); listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() } }
        val profile = db.profiles().first()?.name ?: "EV80"
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(s.startMs))
        val base = "ev80_${profile.replace(Regex("[^A-Za-z0-9_-]"), "_")}_${stamp}_${s.type.lowercase(Locale.US)}"
        val samples = db.samples().listForSession(sessionId)
        val raw = s.rawLogPath?.let(::File)?.takeIf { it.exists() }
        when (format) {
            ExportFormat.BUNDLE -> {
                val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
                val meta = SessionMeta(appVersion = version, adapter = s.adapterName ?: s.adapterKind,
                    timestampResolutionMs = when (s.adapterKind) { "GS_USB" -> 10; "REPLAY" -> 1; else -> 200 },
                    type = s.type, outcome = s.outcome, startMs = s.startMs, endMs = s.endMs, chargerName = s.chargerName,
                    chargedKwh = s.chargedKwh, framesTotal = s.framesTotal, droppedFrames = s.droppedFrames, profileName = profile)
                BundleWriter.write(File(dir, "$base.ev80"), meta, raw, s.marksPath?.let(::File), samplesCsv(samples))
            }
            ExportFormat.CSV -> File(dir, "$base.csv").also { f -> f.bufferedWriter().use { w -> samplesCsv(samples).forEach { w.appendLine(it) } } }
            ExportFormat.CANDUMP -> raw?.copyTo(File(dir, "$base.log.gz"), overwrite = true)
        }
    }

    fun mimeOf(f: File) = when { f.name.endsWith(".csv") -> "text/csv"; f.name.endsWith(".gz") -> "application/gzip"; else -> "application/zip" }

    fun share(ctx: Context, file: File, titleRes: Int) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).setType(mimeOf(file)).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, ctx.getString(titleRes)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Debug files (debug.log and its rotated copy) as one share. */
    fun shareDebugLogs(ctx: Context, files: List<File>, titleRes: Int) {
        val uris = ArrayList(files.filter { it.exists() }.map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", it) })
        if (uris.isEmpty()) return
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).setType("text/plain").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, ctx.getString(titleRes)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
