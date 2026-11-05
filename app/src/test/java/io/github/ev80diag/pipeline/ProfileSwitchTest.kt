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
    }

    private class Run(val p: Pipeline, val events: List<ProfileEvent>, val feed: RecordingFeed)

    /** Replays [fixture]; [react] sees every new profile event while the replay runs (like the UI would). */
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
            var given = false
            p.run { if (given) null else { given = true; ReplaySource({ TestLogs.resource("fixtures/$fixture") }) } }
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
        val repo = FakeRepo().apply { vans = listOf(et, lytee) }
        val r = run("terra60l_trip.log.gz", repo, react = { p, e -> if (e is ProfileEvent.Switched) p.chooseProfile(e.from) })
        assertEquals(1, r.events.size)
        assertEquals(1L, r.p.profileId.value)
        assertTrue(repo.sessions.all { it.profileId == 1L })
        assertTrue(repo.snapshots.all { it.profileId == 1L })
        assertEquals(2, repo.moves.size)
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
}
