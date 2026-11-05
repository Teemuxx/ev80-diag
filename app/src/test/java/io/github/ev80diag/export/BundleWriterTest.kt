package io.github.ev80diag.export

import io.github.ev80diag.TestLogs.frame
import io.github.ev80diag.recording.RawLogWriter
import io.github.ev80diag.transport.CandumpLine
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BundleWriterTest {
    private fun tmp() = Files.createTempDirectory("bundle").toFile()
    private val meta = SessionMeta(appVersion = "0.1.0", adapter = "OBDLink LX", timestampResolutionMs = 200, type = "CHARGE_DC",
        outcome = "COMPLETE", startMs = 1_793_273_355_000, endMs = 1_793_274_712_000, chargerName = "ABB Terra 54",
        chargedKwh = 9.4, framesTotal = 223_615, droppedFrames = 5, profileName = "ET")

    private fun rawLog(dir: File): RawLogWriter {
        val w = RawLogWriter(dir, "s")
        (0 until 50).forEach { w.write(frame(0x200, "1600006404010000", 1_793_273_355_000 + it * 100L)) }
        w.mark(1_793_273_356_000, "door open"); w.close(); return w
    }

    private fun entries(zip: File) = ZipFile(zip).use { z -> z.entries().asSequence().map { it.name }.toSet() }
    private fun text(zip: File, name: String, gz: Boolean = false) = ZipFile(zip).use { z ->
        val s = z.getInputStream(z.getEntry(name)); (if (gz) GZIPInputStream(s) else s).bufferedReader().readText()
    }

    @Test fun bundle_containsEntries() {
        val dir = tmp(); val w = rawLog(dir)
        val out = BundleWriter.write(File(dir, "x.ev80"), meta, w.logFile, w.marksFile, sequenceOf("ts_ms,pack_v", "1,358.5"))
        assertEquals(setOf("session.json", "can.log.gz", "session.marks", "samples.csv.gz"), entries(out))
    }

    @Test fun sessionJson_hasSchemaAndDrops() {
        val dir = tmp(); val w = rawLog(dir)
        val j = JSONObject(text(BundleWriter.write(File(dir, "x.ev80"), meta, w.logFile, w.marksFile, emptySequence()), "session.json"))
        assertEquals(1, j.getInt("schema"))
        assertEquals(5L, j.getLong("droppedFrames"))
        assertEquals(223_615L, j.getLong("framesTotal"))
        assertEquals("CHARGE_DC", j.getString("type"))
        assertEquals("0.1.0", j.getString("appVersion"))
        assertEquals(200, j.getInt("timestampResolutionMs"))
        assertEquals(9.4, j.getDouble("chargedKwh"), 1e-9)
    }

    @Test fun sessionJson_escapesSpecialCharacters() {
        val dir = tmp()
        val m = meta.copy(chargerName = "Alpitronic \"HYC150\" \\ ✎\nLüchow")
        val j = JSONObject(text(BundleWriter.write(File(dir, "x.ev80"), m, null, null, emptySequence()), "session.json"))
        assertEquals(m.chargerName, j.getString("chargerName"))
    }

    @Test fun canLog_readableByCandumpParser() {
        val dir = tmp(); val w = rawLog(dir)
        val out = BundleWriter.write(File(dir, "x.ev80"), meta, w.logFile, w.marksFile, emptySequence())
        val frames = text(out, "can.log.gz", gz = true).lines().mapNotNull(CandumpLine::parse)
        assertEquals(50, frames.size)
        assertEquals(0x200, frames.first().id)
        assertTrue(text(out, "session.marks").contains("MARK door open"))
    }

    @Test fun bundle_withoutRawLog_isStillValid() {
        val dir = tmp()
        val out = BundleWriter.write(File(dir, "x.ev80"), meta.copy(type = "DRIVE"), null, null, sequenceOf("ts_ms", "1"))
        assertEquals(setOf("session.json", "samples.csv.gz"), entries(out))
        assertFalse(text(out, "session.json").contains("null,"))
        assertEquals(listOf("ts_ms", "1"), text(out, "samples.csv.gz", gz = true).lines().filter { it.isNotEmpty() })
    }

    @Test fun nullOptionalFields_areJsonNull() {
        val j = JSONObject(text(BundleWriter.write(File(tmp(), "x.ev80"), meta.copy(endMs = null, outcome = null, chargerName = null, chargedKwh = null), null, null, emptySequence()), "session.json"))
        assertTrue(j.isNull("endMs")); assertTrue(j.isNull("outcome")); assertTrue(j.isNull("chargerName")); assertTrue(j.isNull("chargedKwh"))
    }

    @Test fun marksLines_format() {
        val m = io.github.ev80diag.data.store.MarkEntity(1, 1, 3, 1_793_273_400_123, "range dropped")
        assertEquals(listOf("(1793273400.123000) MARK range dropped"), BundleWriter.marksLines(listOf(m)))
    }

    /** A drive without a raw log has no marks file: its marks come from the database. */
    @Test fun bundle_marksFromDatabase_whenNoFile() {
        val dir = tmp()
        val m = io.github.ev80diag.data.store.MarkEntity(1, 1, 3, 1_793_273_400_000, "limp mode")
        val out = BundleWriter.write(File(dir, "x.ev80"), meta, null, null, emptySequence(), listOf(m))
        assertEquals("(1793273400.000000) MARK limp mode\n", text(out, "session.marks"))
    }
}
