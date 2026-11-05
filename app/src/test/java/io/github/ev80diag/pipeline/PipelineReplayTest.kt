package io.github.ev80diag.pipeline

import io.github.ev80diag.data.store.SampleEntity
import io.github.ev80diag.data.store.SessionRepository
import io.github.ev80diag.session.SessionOutcome
import io.github.ev80diag.session.SessionType
import io.github.ev80diag.settings.FullCanLogging
import io.github.ev80diag.transport.CandumpLine
import io.github.ev80diag.transport.ReplaySource
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineReplayTest {
    class FakeRepo : SessionRepository {
        data class S(val id: Long, var profileId: Long, var type: SessionType, val startMs: Long, var outcome: SessionOutcome? = null,
                     var endMs: Long? = null, var charged: Double? = null, var raw: String? = null, var marks: String? = null,
                     var frames: Long = 0, var dropped: Long = 0, var km: Double? = null, var kwh: Double? = null)
        val sessions = ArrayList<S>()
        val samples = ArrayList<SampleEntity>()
        override suspend fun startSession(profileId: Long, type: SessionType, startMs: Long, adapterKind: String, adapterName: String?): Long {
            sessions.add(S(sessions.size + 1L, profileId, type, startMs)); return sessions.size.toLong()
        }
        override suspend fun updateType(id: Long, type: SessionType) { sessions[(id - 1).toInt()].type = type }
        override suspend fun setRawPaths(id: Long, rawLogPath: String, marksPath: String) {
            sessions[(id - 1).toInt()].apply { raw = rawLogPath; marks = marksPath }
        }
        override suspend fun endSession(id: Long, endMs: Long, outcome: SessionOutcome, chargedKwh: Double?, framesTotal: Long, droppedFrames: Long) {
            sessions[(id - 1).toInt()].apply { this.outcome = outcome; this.endMs = endMs; charged = chargedKwh; frames = framesTotal; dropped = droppedFrames }
        }
        override suspend fun addSample(sample: SampleEntity) { samples.add(sample) }
        val snapshots = ArrayList<io.github.ev80diag.data.store.CellSnapshotEntity>()
        override suspend fun setDriveTotals(id: Long, km: Double, kwh: Double) { sessions[(id - 1).toInt()].apply { this.km = km; this.kwh = kwh } }
        override suspend fun addCellSnapshot(s: io.github.ev80diag.data.store.CellSnapshotEntity) { snapshots.add(s) }

        var vans = listOf(io.github.ev80diag.profile.VanPrint(1, null, null))
        data class Move(val sessionId: Long?, val from: Long, val to: Long, val sinceMs: Long)
        val moves = ArrayList<Move>()
        val lastSeen = HashMap<Long, Pair<Double?, Double?>>()
        val marks = ArrayList<io.github.ev80diag.data.store.MarkEntity>()
        override suspend fun vans() = vans
        override suspend fun moveConnectionData(sessionId: Long?, from: Long, to: Long, sinceMs: Long) {
            moves.add(Move(sessionId, from, to, sinceMs))
            sessionId?.let { sessions[(it - 1).toInt()].profileId = to }
            snapshots.replaceAll { if (it.profileId == from && it.tsMs >= sinceMs) it.copy(profileId = to) else it }
            marks.replaceAll { if (it.profileId == from && it.tsMs >= sinceMs) it.copy(profileId = to) else it }
        }
        override suspend fun setLastSeen(profileId: Long, odoKm: Double?, chargeKwh: Double?, ms: Long) {
            val old = lastSeen[profileId]
            lastSeen[profileId] = (odoKm ?: old?.first) to (chargeKwh ?: old?.second)
        }
        override suspend fun addMark(m: io.github.ev80diag.data.store.MarkEntity) { marks.add(m) }
    }

    private fun fixture(name: String): () -> java.io.InputStream = { io.github.ev80diag.TestLogs.resource("fixtures/$name") }

    private fun runPipeline(name: String, mode: FullCanLogging, repo: FakeRepo, dir: File): Pipeline {
        val p = Pipeline(dir, { mode }, repo, profileId = 1, replayMode = true)
        runBlocking {
            var given = false
            p.run { if (given) null else { given = true; ReplaySource(fixture(name)) } }
        }
        return p
    }

    private fun readFrames(path: String) = GZIPInputStream(File(path).inputStream()).bufferedReader().useLines { it.mapNotNull(CandumpLine::parse).toList() }

    @Test
    fun etStart_recordsChargeWithPreRoll() {
        val repo = FakeRepo(); val dir = Files.createTempDirectory("sess").toFile()
        runPipeline("et_terra54_start.log.gz", FullCanLogging.CHARGING_ONLY, repo, dir)
        val s = repo.sessions.single()
        assertEquals(SessionType.CHARGE_DC, s.type)
        assertNotNull(s.raw)
        val frames = readFrames(s.raw!!)
        val all = io.github.ev80diag.TestLogs.load("et_terra54_start.log.gz")
        assertEquals("every frame from the (back-dated) start on", all.size, frames.size)
        assertEquals(all.first().timestamp, frames.first().timestamp)
        // framesTotal counts from when the session was confirmed (5 s after its back-dated start), the raw file from the start
        assertTrue("frames ${s.frames}", s.frames in all.size - 1_200L..all.size.toLong())
        assertEquals(0L, s.dropped)
        assertTrue("samples ${repo.samples.size}", repo.samples.size in 280..320)    // ~5 min at 1 Hz
        assertTrue(repo.samples.any { it.insulationKOhm != null && it.packA != null })
    }

    @Test
    fun drive_notRawLogged_whenChargingOnly() {
        val repo = FakeRepo(); val dir = Files.createTempDirectory("sess").toFile()
        runPipeline("lx_state_changes.log.gz", FullCanLogging.CHARGING_ONLY, repo, dir)
        val s = repo.sessions.single()
        assertEquals(SessionType.DRIVE, s.type)
        assertNull(s.raw)
        assertTrue(repo.samples.isNotEmpty())
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun drive_rawLogged_whenAlways_withPreRoll() {
        val repo = FakeRepo(); val dir = Files.createTempDirectory("sess").toFile()
        runPipeline("lx_state_changes.log.gz", FullCanLogging.ALWAYS, repo, dir)
        val s = repo.sessions.single()
        assertNotNull(s.raw)
        val frames = readFrames(s.raw!!)
        val all = io.github.ev80diag.TestLogs.load("lx_state_changes.log.gz")
        assertEquals("the drive starts 26 s in; pre-roll (120 s) reaches back to the first frame", all.size, frames.size)
    }

    @Test
    fun terra60lTrip_endsStoppedByBms_andRawLogHoldsTheStop() {
        val repo = FakeRepo(); val dir = Files.createTempDirectory("sess").toFile()
        runPipeline("terra60l_trip.log.gz", FullCanLogging.CHARGING_ONLY, repo, dir)
        val s = repo.sessions.first()
        assertEquals(SessionOutcome.STOPPED_BY_BMS, s.outcome)
        val lines = readFrames(s.raw!!)
        assertTrue(lines.any { it.id == 0x200 && it.data[0] == 0x12.toByte() })
    }

    /** Emits a charge from the ET fixture, then stays connected (like a live adapter) until cancelled. */
    private class HangingSource(private val frames: List<io.github.ev80diag.can.CanFrame>) : io.github.ev80diag.transport.CanSource {
        override val info = io.github.ev80diag.transport.SourceInfo(io.github.ev80diag.transport.SourceKind.STN_BT, "fake", true, 200)
        override suspend fun run(sink: io.github.ev80diag.transport.FrameSink) {
            frames.forEach { sink.onFrame(it) }
            kotlinx.coroutines.awaitCancellation()
        }
    }

    @Test
    fun cancel_endsOpenSession_andFlushesRawLog() = runBlocking {
        val repo = FakeRepo(); val dir = Files.createTempDirectory("sess").toFile()
        val all = io.github.ev80diag.TestLogs.load("et_terra54_end.log.gz")     // charge in progress from the first frame
        // replayMode: the fake source dumps 250 s of frames at once, so the bus must hold it back (a live adapter never runs ahead like that)
        val p = Pipeline(dir, { FullCanLogging.CHARGING_ONLY }, repo, profileId = 1, replayMode = true)
        val job = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            var given = false
            p.run { if (given) null else { given = true; HangingSource(all) } }
        }
        while (p.state.value.framesTotal < all.size - 50) kotlinx.coroutines.delay(20)             // everything is through the decoder
        job.cancelAndJoin()                                                                         // the user taps Disconnect
        val s = repo.sessions.single()
        assertNotNull("session must be ended on cancel", s.outcome)
        val frames = readFrames(s.raw!!)
        assertTrue("raw log has ${frames.size} of ${all.size}", frames.size >= all.size - 100)
    }

    @Test
    fun stateFlow_endsDisconnected() {
        val repo = FakeRepo()
        val p = runPipeline("et_terra54_end.log.gz", FullCanLogging.CHARGING_ONLY, repo, Files.createTempDirectory("s").toFile())
        assertEquals(LinkStatus.DISCONNECTED, p.state.value.link)
        assertEquals(18, p.state.value.temps?.size)
    }

    @Test
    fun rawPath_isStoredWhenTheSessionStarts() = runBlocking {
        val repo = FakeRepo(); val dir = Files.createTempDirectory("sess").toFile()
        val all = io.github.ev80diag.TestLogs.load("et_terra54_end.log.gz")
        val p = Pipeline(dir, { FullCanLogging.CHARGING_ONLY }, repo, profileId = 1, replayMode = true)
        val job = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            var given = false
            p.run { if (given) null else { given = true; HangingSource(all) } }
        }
        while (p.state.value.framesTotal < 1_000) kotlinx.coroutines.delay(20)
        val open = repo.sessions.single()
        assertNull(open.outcome)
        assertNotNull("a killed app must still find the raw log", open.raw)
        job.cancelAndJoin()
    }

    private class RefusingSource : io.github.ev80diag.transport.CanSource {
        override val info = io.github.ev80diag.transport.SourceInfo(io.github.ev80diag.transport.SourceKind.STN_BT, "clone", true, 200)
        override suspend fun run(sink: io.github.ev80diag.transport.FrameSink) =
            throw io.github.ev80diag.transport.AdapterRefusedException("not an STN adapter")
    }

    @Test
    fun refusedAdapter_isReportedToCaller_andStaysRefused() = runBlocking {
        val p = Pipeline(Files.createTempDirectory("s").toFile(), { FullCanLogging.CHARGING_ONLY }, FakeRepo(), profileId = 1)
        val results = ArrayList<SourceResult>()
        p.run { last -> results.add(last); if (last == SourceResult.NONE) RefusingSource() else null }
        assertEquals(listOf(SourceResult.NONE, SourceResult.REFUSED), results)
        assertEquals(LinkStatus.REFUSED, p.state.value.link)
    }

    /** Delivers [frames], then fails like a Bluetooth link dropping out of range. */
    private class DroppingSource(private val frames: List<io.github.ev80diag.can.CanFrame>) : io.github.ev80diag.transport.CanSource {
        override val info = io.github.ev80diag.transport.SourceInfo(io.github.ev80diag.transport.SourceKind.STN_BT, "lx", true, 200)
        override suspend fun run(sink: io.github.ev80diag.transport.FrameSink) {
            sink.onEvent(io.github.ev80diag.transport.SourceEvent.Stats(500.0))
            frames.forEach { sink.onFrame(it) }
            kotlinx.coroutines.delay(500)
            throw java.io.IOException("link lost")
        }
    }

    /** Reconnect attempts that never reach the adapter. */
    private class UnreachableSource : io.github.ev80diag.transport.CanSource {
        override val info = io.github.ev80diag.transport.SourceInfo(io.github.ev80diag.transport.SourceKind.STN_BT, "lx", true, 200)
        override suspend fun run(sink: io.github.ev80diag.transport.FrameSink) { kotlinx.coroutines.awaitCancellation() }
    }

    @Test
    fun linkLoss_doesNotEndCharge_untilGraceTimePassed() = runBlocking {
        val repo = FakeRepo()
        val frames = io.github.ev80diag.TestLogs.load("et_terra54_end.log.gz").take(3_000)   // DC charging
        val lastFrame = frames.last().timestamp
        val now = java.util.concurrent.atomic.AtomicLong(lastFrame)
        val p = Pipeline(Files.createTempDirectory("s").toFile(), { FullCanLogging.CHARGING_ONLY }, repo, profileId = 1, clock = { now.get() })
        val job = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            p.run { last -> if (last == SourceResult.NONE) DroppingSource(frames) else UnreachableSource() }
        }
        while (p.state.value.link != LinkStatus.CONNECTING || repo.sessions.isEmpty()) kotlinx.coroutines.delay(20)
        now.set(lastFrame + 3 * 60_000)                        // out of range for 3 min, van still charging
        kotlinx.coroutines.delay(2_500)                      // a few 1 s ticks
        assertNull("charge must survive a 3 min link outage", repo.sessions.single().outcome)
        now.set(lastFrame + 6 * 60_000)                        // past the grace time: the van is treated as gone
        kotlinx.coroutines.delay(2_500)
        val s = repo.sessions.single()
        assertEquals(SessionOutcome.ENDED, s.outcome)
        assertEquals("ends at the last frame seen", lastFrame, s.endMs)
        job.cancelAndJoin()
    }

    @Test
    fun drive1023_totalsAndSnapshots() {
        val repo = FakeRepo(); val dir = Files.createTempDirectory("sess").toFile()
        // the drive is closed when the log ends, so watch the published state while it runs
        var lastDrive: io.github.ev80diag.calc.DriveSnapshot? = null
        val p = Pipeline(dir, { FullCanLogging.CHARGING_ONLY }, repo, profileId = 1, replayMode = true)
        runBlocking {
            val watch = launch(kotlinx.coroutines.Dispatchers.Default) { p.state.collect { st -> st.drive?.let { lastDrive = it } } }
            var given = false
            p.run { if (given) null else { given = true; ReplaySource(fixture("drive_1023.log.gz")) } }
            watch.cancel()
        }
        val s = repo.sessions.single { it.type == SessionType.DRIVE }
        assertEquals(5.6, s.km!!, 0.15)
        assertEquals(1.62, s.kwh!!, 0.05)
        assertEquals(28.9, lastDrive!!.kwhPer100Recent!!, 1.5)
        assertTrue("snapshots ${repo.snapshots.size}", repo.snapshots.size in 25..40)
        for (c in repo.snapshots) {
            assertTrue(!c.charging)
            val mv = io.github.ev80diag.data.store.CellBlob.unpack(c.mv)
            assertEquals(104, mv.size)
            assertTrue(mv.all { it in 2000..5000 })
        }
    }

    /** 10-23 12:19 drive: the accelerator reads up to tens of %, and it is released whenever the brake is pressed. */
    @Test
    fun drive1023_pedals() {
        val repo = FakeRepo(); val dir = Files.createTempDirectory("sess").toFile()
        var maxAccel = 0.0; var accelWhileBraking = 0.0; var brakeSeen = 0.0
        val p = Pipeline(dir, { FullCanLogging.CHARGING_ONLY }, repo, profileId = 1, replayMode = true)
        runBlocking {
            val watch = launch(kotlinx.coroutines.Dispatchers.Default) {
                p.state.collect { st ->
                    val a = st.decoded["accel_pedal_pct"] ?: return@collect
                    val b = st.decoded["brake_pedal"] ?: return@collect
                    maxAccel = maxOf(maxAccel, a); brakeSeen = maxOf(brakeSeen, b)
                    if (b > 50) accelWhileBraking = maxOf(accelWhileBraking, a)
                }
            }
            var given = false
            p.run { if (given) null else { given = true; ReplaySource(fixture("drive_1023.log.gz")) } }
            watch.cancel()
        }
        assertTrue("max accelerator $maxAccel", maxAccel > 30)
        assertTrue("max brake $brakeSeen", brakeSeen > 50)
        assertEquals(0.0, accelWhileBraking, 1.0)
    }

    @Test
    fun etEnd_snapshotsEvery2s() {
        val repo = FakeRepo(); val dir = Files.createTempDirectory("sess").toFile()
        runPipeline("et_terra54_end.log.gz", FullCanLogging.CHARGING_ONLY, repo, dir)
        val charging = repo.snapshots.filter { it.charging }
        assertTrue("snapshots ${repo.snapshots.size}", charging.size > 50)
        charging.zipWithNext().forEach { (a, b) -> assertTrue(b.tsMs - a.tsMs >= 2_000) }
    }

    /** A drive, then an hour later a DC charge (only the BMS set on the bus): drive-side values must not look current. */
    @Test
    fun driveThenCharge_driveValuesNotPublishedAsCurrent() = runBlocking {
        val repo = FakeRepo(); val dir = Files.createTempDirectory("sess").toFile()
        val drive = io.github.ev80diag.TestLogs.load("drive_1023.log.gz")
        val charge = io.github.ev80diag.TestLogs.load("et_terra54_start.log.gz")
        val shift = drive.last().timestamp + 3_600_000L - charge.first().timestamp
        val frames = drive + charge.map { it.copy(timestamp = it.timestamp + shift) }
        val p = Pipeline(dir, { FullCanLogging.CHARGING_ONLY }, repo, profileId = 1, replayMode = true)
        var given = false
        p.run { if (given) null else { given = true; ListSource(frames) } }
        val d = p.state.value.decoded
        assertTrue("pack_v present", d.containsKey("pack_v"))
        assertNull("drive_temp_302 ${d["drive_temp_302"]}", d["drive_temp_302"])
        assertNull("speed ${d["speed_kmh"]}", d["speed_kmh"])
    }

    private class ListSource(private val frames: List<io.github.ev80diag.can.CanFrame>) : io.github.ev80diag.transport.CanSource {
        override val info = io.github.ev80diag.transport.SourceInfo(io.github.ev80diag.transport.SourceKind.REPLAY, "list", true, 1)
        override suspend fun run(sink: io.github.ev80diag.transport.FrameSink) {
            sink.onEvent(io.github.ev80diag.transport.SourceEvent.Stats(0.0))
            frames.forEach { sink.onFrame(it) }
        }
    }
}
