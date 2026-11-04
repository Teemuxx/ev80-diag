package io.github.ev80diag.export

import io.github.ev80diag.data.store.CellBlob
import io.github.ev80diag.data.store.CellSnapshotEntity
import io.github.ev80diag.data.store.SessionEntity
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileExportTest {
    private val dir: File = Files.createTempDirectory("all").toFile()

    private val sessions = listOf(
        SessionEntity(id = 2, profileId = 1, type = "DRIVE", outcome = "ENDED", startMs = 1_000, endMs = 61_000,
            adapterKind = "STN_BT", adapterName = "OBDLink LX", framesTotal = 452_025, distanceKm = 3.6, driveKwh = 1.24),
        SessionEntity(id = 3, profileId = 1, type = "CHARGE_AC", outcome = "ENDED", startMs = 70_000, endMs = null,
            chargerName = "Home, \"wallbox\"", chargedKwh = 0.26, adapterKind = "STN_BT"),
    )

    @Test fun sessionsCsv_oneRowPerSession() {
        val rows = ProfileExport.sessionsCsv(sessions).toList()
        assertEquals(ProfileExport.SESSIONS_HEADER, rows[0])
        assertEquals(3, rows.size)
        assertEquals("2,DRIVE,ENDED,1000,61000,,,3.6,1.24,STN_BT,OBDLink LX,452025,0", rows[1])
        assertEquals("3,CHARGE_AC,ENDED,70000,,\"Home, \"\"wallbox\"\"\",0.26,,,STN_BT,,0,0", rows[2])
    }

    /** Two sessions starting in the same minute get the same bundle name (phone, 2026-11-04: duplicate-entry crash). */
    @Test fun zip_sameBundleNameTwice_bothKept() {
        val a = File(dir, "a").apply { mkdirs() }.resolve("ev80_ET_20260910_1812_charge_ac.ev80").apply { writeBytes(byteArrayOf(1)) }
        val b = File(dir, "b").apply { mkdirs() }.resolve("ev80_ET_20260910_1812_charge_ac.ev80").apply { writeBytes(byteArrayOf(2)) }
        val out = ProfileExport.write(File(dir, "dup.zip"), "{}", ProfileExport.sessionsCsv(emptyList()), listOf(4L to a, 5L to b), emptySequence())
        ZipFile(out).use { z ->
            assertArrayEquals(byteArrayOf(1), z.getInputStream(z.getEntry("sessions/4_ev80_ET_20260910_1812_charge_ac.ev80")).readBytes())
            assertArrayEquals(byteArrayOf(2), z.getInputStream(z.getEntry("sessions/5_ev80_ET_20260910_1812_charge_ac.ev80")).readBytes())
        }
    }

    @Test fun profileJson_quotesName() {
        val j = ProfileExport.profileJson("ET \"blue\"", "0.3.0-dev", 42, 3)
        assertTrue(j, j.contains("\"profileName\": \"ET \\\"blue\\\"\""))
        assertTrue(j, j.contains("\"sessions\": 3"))
    }

    @Test fun cellsCsv_104Columns() {
        val mv = IntArray(104) { 3300 + it }
        val rows = ProfileExport.cellsCsv(listOf(CellSnapshotEntity(1, 5_000, CellBlob.pack(mv), charging = true))).toList()
        val header = rows[0].split(',')
        assertEquals(listOf("ts_ms", "charging", "c1"), header.take(3))
        assertEquals("c104", header.last()); assertEquals(106, header.size)
        val row = rows[1].split(',')
        assertEquals("5000", row[0]); assertEquals("1", row[1]); assertEquals("3300", row[2]); assertEquals("3403", row[105])
    }

    @Test fun zip_holdsEverything() {
        val bundle = File(dir, "ev80_ET_20261103_1950_drive.ev80").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val out = ProfileExport.write(File(dir, "all.zip"), "{\"profileName\": \"ET\"}",
            ProfileExport.sessionsCsv(sessions), listOf(2L to bundle), sequenceOf(ProfileExport.CELLS_HEADER, "5000,0"))
        ZipFile(out).use { z ->
            val names = z.entries().toList().map { it.name }
            assertEquals(listOf("profile.json", "sessions.csv", "sessions/2_ev80_ET_20261103_1950_drive.ev80", "cells.csv.gz"), names)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), z.getInputStream(z.getEntry("sessions/2_ev80_ET_20261103_1950_drive.ev80")).readBytes())
            assertTrue(z.getInputStream(z.getEntry("profile.json")).reader().readText().contains("\"ET\""))
            val cells = GZIPInputStream(z.getInputStream(z.getEntry("cells.csv.gz"))).reader().readLines()
            assertEquals(listOf(ProfileExport.CELLS_HEADER, "5000,0"), cells)
        }
    }
}
