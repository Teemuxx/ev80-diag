package io.github.ev80diag

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ev80diag.data.store.Ev80Database
import io.github.ev80diag.data.store.RoomSessionRepository
import io.github.ev80diag.data.store.SampleEntity
import io.github.ev80diag.session.SessionOutcome
import io.github.ev80diag.session.SessionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Run on a device: ./gradlew :app:connectedDebugAndroidTest */
@RunWith(AndroidJUnit4::class)
class SessionRepositoryTest {
    private lateinit var db: Ev80Database
    private lateinit var repo: RoomSessionRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), Ev80Database::class.java).build()
        repo = RoomSessionRepository(db)
    }

    @After fun tearDown() = db.close()

    @Test fun insertAndListSessions_newestFirst() = runBlocking {
        val p = repo.ensureDefaultProfile("My EV80")
        val a = repo.startSession(p.id, SessionType.CHARGE_ATTEMPT, 1_000, "STN_BT", "OBDLink LX")
        val b = repo.startSession(p.id, SessionType.DRIVE, 5_000, "STN_BT", "OBDLink LX")
        repo.updateType(a, SessionType.CHARGE_DC)
        repo.setRawPaths(a, "/x/a.log.gz", "/x/a.marks")
        repo.endSession(a, 4_000, SessionOutcome.COMPLETE, 9.4, 1000, 0)
        val list = db.sessions().all().first()
        assertEquals(listOf(b, a), list.map { it.id })
        val ended = list.last()
        assertEquals("CHARGE_DC", ended.type)
        assertEquals("COMPLETE", ended.outcome)
        assertEquals(9.4, ended.chargedKwh!!, 0.0001)
        assertEquals("/x/a.log.gz", ended.rawLogPath)                  // set at start, kept by endSession
        assertNull(list.first().endMs)
        assertEquals(p.id, repo.ensureDefaultProfile("other").id)       // one default profile only
    }

    @Test fun samples_roundTripPerSession() = runBlocking {
        val p = repo.ensureDefaultProfile("My EV80")
        val s = repo.startSession(p.id, SessionType.CHARGE_DC, 0, "GS_USB", null)
        repo.addSample(SampleEntity(s, 1_000, packV = 358.5, packA = -79.4, insulationKOhm = 1930.0))
        repo.addSample(SampleEntity(s, 2_000, packV = 358.6, packA = -79.5))
        val got = db.samples().forSession(s).first()
        assertEquals(2, got.size)
        assertNotNull(got[0].insulationKOhm)
        assertNull(got[1].insulationKOhm)
    }

    @Test fun closeOrphans_endsOpenSessionsAtLastSample() = runBlocking {
        val p = repo.ensureDefaultProfile("My EV80")
        val killed = repo.startSession(p.id, SessionType.CHARGE_DC, 10_000, "STN_BT", "OBDLink LX")
        repo.setRawPaths(killed, "/x/k.log.gz", "/x/k.marks")
        repo.addSample(SampleEntity(killed, 70_000, packV = 358.5))
        val noSamples = repo.startSession(p.id, SessionType.CHARGE_ATTEMPT, 90_000, "STN_BT", "OBDLink LX")
        assertEquals(2, repo.closeOrphans())
        val byId = db.sessions().all().first().associateBy { it.id }
        assertEquals("INTERRUPTED", byId[killed]!!.outcome)
        assertEquals(70_000L, byId[killed]!!.endMs)
        assertEquals("/x/k.log.gz", byId[killed]!!.rawLogPath)
        assertEquals(90_000L, byId[noSamples]!!.endMs)
        assertEquals(0, repo.closeOrphans())
    }

    @Test fun driveTotals_sumsDrivesInRange() = runBlocking {
        val p = repo.ensureDefaultProfile("My EV80")
        val a = repo.startSession(p.id, SessionType.DRIVE, 1_000, "STN_BT", null)
        repo.setDriveTotals(a, 3.0, 0.9)
        val b = repo.startSession(p.id, SessionType.DRIVE, 10_000, "STN_BT", null)
        repo.setDriveTotals(b, 2.0, 0.5)
        repo.endSession(a, 2_000, SessionOutcome.ENDED, null, 0, 0)
        repo.endSession(b, 11_000, SessionOutcome.ENDED, null, 0, 0)
        repo.startSession(p.id, SessionType.CHARGE_DC, 20_000, "STN_BT", null)
        // a drive still open is not counted: the screens add its live values themselves
        val open = repo.startSession(p.id, SessionType.DRIVE, 30_000, "STN_BT", null)
        repo.setDriveTotals(open, 9.0, 9.0)
        val all = db.sessions().driveTotals(p.id, 0, Long.MAX_VALUE).first()
        assertEquals(5.0, all.km, 1e-9); assertEquals(1.4, all.kwh, 1e-9)
        val first = db.sessions().driveTotals(p.id, 0, 5_000).first()
        assertEquals(3.0, first.km, 1e-9); assertEquals(0.9, first.kwh, 1e-9)
    }

    @Test fun cellSnapshots_bucketed() = runBlocking {
        val p = repo.ensureDefaultProfile("My EV80")
        val mv = IntArray(104) { 3300 + it }
        for (i in 0 until 100) repo.addCellSnapshot(io.github.ev80diag.data.store.CellSnapshotEntity(
            p.id, 100_000L + i * 1_000L, io.github.ev80diag.data.store.CellBlob.pack(mv), charging = false))
        // two steps: bucket times from the index, then the blobs for those times only
        val times = db.cellSnapshots().bucketTimes(p.id, 0, Long.MAX_VALUE, 10_000).first()
        assertEquals(10, times.size)
        val rows = db.cellSnapshots().atTimes(p.id, times)
        assertEquals(10, rows.size)
        org.junit.Assert.assertArrayEquals(mv, io.github.ev80diag.data.store.CellBlob.unpack(rows[0].mv))
    }
}
