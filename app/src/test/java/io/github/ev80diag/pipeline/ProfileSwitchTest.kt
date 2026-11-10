package io.github.ev80diag.pipeline

import io.github.ev80diag.TestLogs
import io.github.ev80diag.pipeline.PipelineReplayTest.FakeRepo
import io.github.ev80diag.profile.Evidence
import io.github.ev80diag.profile.VanPrint
import io.github.ev80diag.settings.FullCanLogging
import io.github.ev80diag.transport.ReplaySource
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ET = 1, Lytee = 2, with the fixtures' odometer / charge counter (kWh) as last-seen values. */
class ProfileSwitchTest {
    private val et = VanPrint(1, 49_551.1, 13_814.0)
    private val lytee = VanPrint(2, 57_509.9, 13_024.0)

    /** Counts SOC input and notes whether any arrived before the van was confirmed. */
    private class RecordingFeed : SocFeed {
        lateinit var pipeline: Pipeline
        var calls = 0
        var beforeConfirmed = 0
        private fun note() { calls++; if (!pipeline.state.value.profileConfirmed) beforeConfirmed++ }
        override fun onCurrent(tsMs: Long, currentA: Float) = note()
        override fun onCellExtremes(tsMs: Long, maxMv: Int, minMv: Int, currentA: Float) = note()
        override fun socPercent(): Float? = null
        val profiles = ArrayList<Pair<Long, Boolean>>()
        override fun onProfile(profileId: Long, revertCurrent: Boolean) { profiles.add(profileId to revertCurrent) }
    }

    private class Run(val p: Pipeline, val events: List<ProfileEvent>, val feed: RecordingFeed)

    /** Replays [fixture] ("a+b" = two source runs, like a reconnect); [react] sees every new profile event while the replay runs (like the UI would). */
    private fun run(fixture: String, repo: FakeRepo, active: Long = 1, recognize: Boolean = true,
                    react: (Pipeline, ProfileEvent) -> Unit = { _, _ -> }, onSession: (Pipeline) -> Unit = {}): Run {
        val feed = RecordingFeed()
        val p = Pipeline(Files.createTempDirectory("sess").toFile(), { FullCanLogging.CHARGING_ONLY }, repo, active,
            feed, replayMode = true, recognize = recognize)
        feed.pipeline = p
        val events = ArrayList<ProfileEvent>()
        runBlocking {
            val watch = CoroutineScope(coroutineContext).launch {
                var sessionSeen = false
                p.state.collect { st ->
                    st.profileEvent?.let { e -> if (events.lastOrNull() != e) { events.add(e); react(p, e) } }
                    if (!sessionSeen && st.session != null) { sessionSeen = true; onSession(p) }
                }
            }
            val queue = ArrayDeque(fixture.split('+'))
            p.run { queue.removeFirstOrNull()?.let { f -> ReplaySource({ TestLogs.resource("fixtures/$f") }) } }
            watch.cancel()
        }
        return Run(p, events, feed)
    }

    private fun assertLastSeen(repo: FakeRepo, id: Long, odo: Double, charge: Double) {
        val (o, c) = repo.lastSeen.getValue(id)
        assertEquals(odo, o!!, 0.01); assertEquals(charge, c!!, 0.01)
    }

    @Test fun singleVan_confirmedAtOnce() {
        val repo = FakeRepo().apply { vans = listOf(VanPrint(1, null, null)) }
        val r = run("lx_state_changes.log.gz", repo)
        assertTrue(r.events.isEmpty())
        assertTrue(r.p.state.value.profileConfirmed)
        assertEquals(0, r.feed.beforeConfirmed)
        assertEquals(1L, repo.sessions.single().profileId)
        assertLastSeen(repo, 1, 49_551.1, 13_814.0)
    }

    @Test fun twoVans_odometerMatchesActive_noEvent() {
        val repo = FakeRepo().apply { vans = listOf(et, lytee) }
        val r = run("lx_state_changes.log.gz", repo)
        assertTrue(r.events.isEmpty())
        assertEquals(1L, repo.sessions.single().profileId)
        assertTrue(repo.moves.isEmpty())
        assertLastSeen(repo, 1, 49_551.1, 13_814.0)
        assertNull(repo.lastSeen[2L])
    }

    @Test fun twoVans_counterMatchesOther_switchesAndMovesData() {
        val repo = FakeRepo().apply { vans = listOf(et, lytee) }
        val r = run("terra60l_trip.log.gz", repo)
        val e = r.events.single()
        assertEquals(ProfileEvent.Switched(1, 2, Evidence.CHARGE_COUNTER, e.seq), e)
        assertEquals(2L, r.p.profileId.value)
        assertTrue(repo.sessions.isNotEmpty())
        assertTrue(repo.sessions.all { it.profileId == 2L })
        assertTrue(repo.snapshots.isNotEmpty())
        assertTrue(repo.snapshots.all { it.profileId == 2L })
        assertTrue(r.feed.calls > 0)
        assertEquals("no SOC input before the van was known", 0, r.feed.beforeConfirmed)
        assertNull(repo.lastSeen[1L])
        assertEquals(13_024.0, repo.lastSeen[2L]!!.second!!, 0.01)
    }

    @Test fun twoVans_unclear_asks_thenChoiceMovesData() {
        val repo = FakeRepo().apply { vans = listOf(VanPrint(1, null, null), VanPrint(2, null, null)) }
        val r = run("lx_state_changes.log.gz", repo, react = { p, e -> if (e is ProfileEvent.Ask) p.chooseProfile(2) })
        assertEquals(listOf(1L, 2L), (r.events.first() as ProfileEvent.Ask).candidates)
        assertEquals(2L, r.p.profileId.value)
        assertTrue(r.p.state.value.profileConfirmed)
        assertNull(r.p.state.value.profileEvent)
        assertEquals(2L, repo.sessions.single().profileId)
        assertEquals(0, r.feed.beforeConfirmed)
        assertTrue(repo.lastSeen.keys == setOf(2L))
    }

    @Test fun undo_movesBack_noSecondSwitch() {
        // Lytee's stored values differ from what this connection sees, so a restore is visible
        val repo = FakeRepo().apply { vans = listOf(et, VanPrint(2, 57_000.0, 12_900.0)) }
        val r = run("terra60l_trip.log.gz", repo, react = { p, e -> if (e is ProfileEvent.Switched) p.chooseProfile(e.from) })
        assertEquals(1, r.events.size)
        assertEquals(1L, r.p.profileId.value)
        assertTrue(repo.sessions.all { it.profileId == 1L })
        assertTrue(repo.snapshots.all { it.profileId == 1L })
        assertEquals(2, repo.moves.size)
        assertEquals("the undone van keeps its old fingerprint", 57_000.0 to 12_900.0, repo.lastSeen[2L])
        assertEquals("the undone van's SOC goes back to before the switch", 1L to true, r.feed.profiles.last())
    }

    @Test fun replay_neverSwitches() {
        val repo = FakeRepo().apply { vans = listOf(et, lytee) }
        val r = run("terra60l_trip.log.gz", repo, recognize = false)
        assertTrue(r.events.isEmpty())
        assertTrue(repo.sessions.all { it.profileId == 1L })
        assertTrue(repo.lastSeen.isEmpty())
    }

    @Test fun mark_storedWithOpenSession() {
        val repo = FakeRepo()
        run("et_terra54_start.log.gz", repo, onSession = { it.mark("plugged in") })
        val m = repo.marks.single()
        assertEquals("plugged in", m.text); assertEquals(1L, m.sessionId); assertEquals(1L, m.profileId)
    }

    @Test fun mark_withoutSession() {
        val repo = FakeRepo()
        val feed = RecordingFeed()
        val p = Pipeline(Files.createTempDirectory("sess").toFile(), { FullCanLogging.CHARGING_ONLY }, repo, 1, feed, replayMode = true)
        feed.pipeline = p
        p.mark("before anything")
        runBlocking { var given = false; p.run { if (given) null else { given = true; ReplaySource({ TestLogs.resource("fixtures/lx_state_changes.log.gz") }) } } }
        val m = repo.marks.single()
        assertNull(m.sessionId); assertFalse(m.text.isEmpty())
    }

    /**
     * Phone, 2026-11-08 13:37: "mark a moment" after an hour connected froze the app (ANR in Pipeline.mark). The decode
     * select had left a cancelled waiter on the owner-input channel for every frame, and the first send walked past all of them.
     */
    @Test fun mark_afterManyFrames_isInstant() {
        val repo = FakeRepo()
        val p = Pipeline(Files.createTempDirectory("sess").toFile(), { FullCanLogging.CHARGING_ONLY }, repo, 1, SocFeed.None)
        var markNs = 0L
        val src = object : io.github.ev80diag.transport.CanSource {
            override val info = io.github.ev80diag.transport.SourceInfo(io.github.ev80diag.transport.SourceKind.STN_BT, "test", true, 1)
            override suspend fun run(sink: io.github.ev80diag.transport.FrameSink) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                var ts = 1_000_000L
                // one frame at a time, so the decoder waits for the next one each time (as with a live adapter)
                repeat(400_000) { sink.onFrame(io.github.ev80diag.can.CanFrame(0x7F0, ByteArray(8), ts++)); kotlinx.coroutines.yield() }
                val t0 = System.nanoTime(); p.mark("after an hour"); markNs = System.nanoTime() - t0
                repeat(100) { sink.onFrame(io.github.ev80diag.can.CanFrame(0x7F0, ByteArray(8), ts++)); kotlinx.coroutines.yield() }
            }
        }
        runBlocking { var given = false; p.run { if (given) null else { given = true; src } } }
        assertEquals("after an hour", repo.marks.single().text)
        assertTrue("mark took ${markNs / 1_000_000.0} ms", markNs < 3_000_000)
    }

    /** Review C1: a replay never moves data, whatever the owner taps. */
    @Test fun choose_whileReplaying_movesNothing() {
        val repo = FakeRepo().apply { vans = listOf(et, lytee) }
        val r = run("terra60l_trip.log.gz", repo, recognize = false, onSession = { it.chooseProfile(2) })
        assertTrue(repo.moves.isEmpty())
        assertTrue(repo.sessions.all { it.profileId == 1L })
        assertEquals(1L, r.p.profileId.value)
    }

    /** Review C1: a choice made while no connection is live only picks the van for the next one. */
    @Test fun choose_beforeAnyFrame_movesNothing() {
        val repo = FakeRepo().apply { vans = listOf(et, lytee) }
        val feed = RecordingFeed()
        val p = Pipeline(Files.createTempDirectory("sess").toFile(), { FullCanLogging.CHARGING_ONLY }, repo, 1, feed, replayMode = true)
        feed.pipeline = p
        p.chooseProfile(2)
        runBlocking { var given = false; p.run { if (given) null else { given = true; ReplaySource({ TestLogs.resource("fixtures/terra60l_trip.log.gz") }) } } }
        assertTrue("no data to move yet: ${repo.moves}", repo.moves.isEmpty())
        assertTrue(repo.sessions.all { it.profileId == 2L })
    }

    /** Review C2: the adapter moves from Lytee to ET within one pipeline (reconnect, logs in time order): recognised again. */
    @Test fun twoRuns_adapterMoved_reRecognises() {
        val repo = FakeRepo().apply { vans = listOf(et, lytee) }
        val r = run("terra60l_trip.log.gz+lx_state_changes.log.gz", repo, active = 2)
        assertEquals(listOf<ProfileEvent>(ProfileEvent.Switched(2, 1, Evidence.ODOMETER, r.events.single().seq)), r.events)
        assertEquals("Lytee's charge stays Lytee's", 2L, repo.sessions.first().profileId)
        assertEquals("ET's drive is ET's", 1L, repo.sessions.last().profileId)
        assertTrue(repo.snapshots.isNotEmpty() && repo.snapshots.all { s -> s.profileId == (if (s.tsMs < 1_793_000_000_000) 2L else 1L) })
        assertLastSeen(repo, 1, 49_551.1, 13_814.0)
        assertEquals(13_024.0, repo.lastSeen[2L]!!.second!!, 0.01)
    }

    /** [second]'s frames shifted to start [gapMs] after [first]'s last frame: the adapter moved quickly between the vans. */
    private fun shifted(first: String, second: String, gapMs: Long, skipMs: Long = 0): () -> java.io.InputStream = {
        val end = TestLogs.load(first).last().timestamp
        val all = TestLogs.load(second)
        val frames = all.filter { it.timestamp >= all.first().timestamp + skipMs }
        val shift = end + gapMs - frames.first().timestamp
        frames.joinToString("\n") { io.github.ev80diag.transport.CandumpLine.format(it.copy(timestamp = it.timestamp + shift)) }.byteInputStream()
    }

    /**
     * Review C2: Lytee's drive is still open (link-loss grace) when the adapter is in ET 30 s later, and ET is already
     * READY (lx_state_changes from +27 s): without ending it, Lytee's drive would just go on with ET's frames.
     */
    @Test fun quickMove_heldDriveEndsOnItsVan_newDataOnTheOther() {
        val repo = FakeRepo().apply { vans = listOf(et, lytee) }
        val feed = RecordingFeed()
        val p = Pipeline(Files.createTempDirectory("sess").toFile(), { FullCanLogging.CHARGING_ONLY }, repo, 2, feed, replayMode = true)
        feed.pipeline = p
        val sources = ArrayDeque(listOf({ TestLogs.resource("fixtures/drive_1023.log.gz") }, shifted("drive_1023.log.gz", "lx_state_changes.log.gz", 30_000, skipMs = 27_000)))
        runBlocking { p.run { sources.removeFirstOrNull()?.let { ReplaySource(it) } } }
        assertEquals(1L, p.profileId.value)
        assertEquals("sessions ${repo.sessions}", 2, repo.sessions.size)
        assertEquals("Lytee's drive ends on Lytee", 2L, repo.sessions[0].profileId)
        assertNotNull(repo.sessions[0].endMs)
        assertEquals("ET's drive is ET's", 1L, repo.sessions[1].profileId)
        assertLastSeen(repo, 1, 49_551.1, 13_814.0)
        assertEquals("ET's values never land on Lytee", 13_024.0, repo.lastSeen[2L]!!.second!!, 0.01)
    }

    /** Review I1: a question about the last connection is not left standing for the next one. */
    @Test fun staleAsk_clearedOnNextConnection() {
        val repo = FakeRepo().apply { vans = listOf(et, lytee) }
        // 09-10 AC log fits neither stored van (Unclear), then ET reconnects
        val r = run("ac_charge_pauses.log.gz+lx_state_changes.log.gz", repo)
        assertTrue(r.events.first() is ProfileEvent.Ask)
        assertNull(r.p.state.value.profileEvent)
        assertTrue(r.p.state.value.profileConfirmed)
    }

    /** Review I2: every session of the connection moves with the answer, not only the open one. */
    @Test fun answer_movesAllSessionsOfTheConnection() {
        val repo = FakeRepo().apply { vans = listOf(VanPrint(1, null, null), VanPrint(2, null, null)) }
        val feed = RecordingFeed()
        val p = Pipeline(Files.createTempDirectory("sess").toFile(), { FullCanLogging.CHARGING_ONLY }, repo, 1, feed, replayMode = true)
        feed.pipeline = p
        runBlocking {
            val watch = CoroutineScope(coroutineContext).launch {
                var answered = false
                p.state.collect { st -> if (!answered && st.profileEvent is ProfileEvent.Ask && (st.session?.id ?: 0) >= 2) { answered = true; p.chooseProfile(2) } }
            }
            // one source run with a charge and then a drive (the charge's end, then ignition on)
            val both = { java.io.SequenceInputStream(java.util.zip.GZIPInputStream(TestLogs.resource("fixtures/et_terra54_end.log.gz")),
                java.util.zip.GZIPInputStream(TestLogs.resource("fixtures/lx_state_changes.log.gz"))) }
            var given = false
            p.run { if (given) null else { given = true; ReplaySource(both) } }
            watch.cancel()
        }
        assertTrue("sessions ${repo.sessions}", repo.sessions.size >= 2)
        assertTrue(repo.sessions.all { it.profileId == 2L })
    }

    @Test fun mark_newlinesFlattened() {
        val repo = FakeRepo()
        val p = Pipeline(Files.createTempDirectory("sess").toFile(), { FullCanLogging.CHARGING_ONLY }, repo, 1, SocFeed.None, replayMode = true)
        p.mark("range dropped\n120 km left")
        runBlocking { var given = false; p.run { if (given) null else { given = true; ReplaySource({ TestLogs.resource("fixtures/lx_state_changes.log.gz") }) } } }
        assertEquals("range dropped 120 km left", repo.marks.single().text)
    }
}
