package io.github.ev80diag.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDetectorTest {
    private val charge1228 = 1793273488_000L        // 2026-10-29 12:31:28 CET
    private val complete1251 = 1793274700_000L      // 12:51:40
    private val trip1149 = 1792748950_000L          // 2026-10-23 11:49:10

    @Test
    fun etStart_becomesDcCharge_afterAttempt() {
        val r = Run.fixture("et_terra54_start.log.gz")
        val started = r.eventsOf<SessionEvent.Started>().single().second as SessionEvent.Started
        assertEquals(SessionType.CHARGE_ATTEMPT, started.type)
        assertTrue(started.startMs < charge1228)
        assertEquals(started.startMs - 120_000, started.preRollFromMs)
        val changed = r.eventsOf<SessionEvent.TypeChanged>().single()
        assertEquals(SessionType.CHARGE_DC, (changed.second as SessionEvent.TypeChanged).type)
        assertTrue("changed at ${changed.first}", changed.first in charge1228 - 3_000..charge1228 + 3_000)
        assertTrue(r.eventsOf<SessionEvent.Ended>().isEmpty())
    }

    @Test
    fun etEnd_completes_andEndsAfter60s() {
        val r = Run.fixture("et_terra54_end.log.gz")
        val started = r.eventsOf<SessionEvent.Started>().single().second as SessionEvent.Started
        assertEquals(SessionType.CHARGE_DC, started.type)           // already charging when the fixture begins
        val complete = r.states.first { it.second.power == PowerState.CHARGE_COMPLETE }.first
        assertTrue("complete at $complete", complete in complete1251 - 3_000..complete1251 + 3_000)
        assertTrue(r.eventsOf<SessionEvent.Ended>().isEmpty())
        r.tick(complete + 30_000)
        assertTrue(r.eventsOf<SessionEvent.Ended>().isEmpty())
        r.tick(complete + 61_000)
        val ended = r.eventsOf<SessionEvent.Ended>().single().second as SessionEvent.Ended
        assertEquals(SessionOutcome.COMPLETE, ended.outcome)
        assertEquals(complete, ended.endMs)
    }

    @Test
    fun terra60lTrip_stoppedByBms_withInsulationFault() {
        val r = Run.fixture("terra60l_trip.log.gz")
        val ended = r.eventsOf<SessionEvent.Ended>().single()
        assertEquals(SessionOutcome.STOPPED_BY_BMS, (ended.second as SessionEvent.Ended).outcome)
        assertTrue("ended at ${ended.first}", ended.first in trip1149..trip1149 + 5_000)
        assertTrue(r.insulationFaultAt.any { it in trip1149..trip1149 + 5_000 })
    }

    @Test
    fun shortGap_doesNotSplitSession() {
        val r = Run()
        for (i in 0 until 100) r.feed(Run.bms("16", 1_000_000L + i * 200L))
        for (i in 0 until 100) r.feed(Run.bms("16", 1_024_000L + i * 200L))   // after a 4 s gap
        assertEquals(1, r.eventsOf<SessionEvent.Started>().size)
        assertTrue(r.eventsOf<SessionEvent.Ended>().isEmpty())
    }

    @Test
    fun attemptWithoutCharge_isRecordedAsNoCharge() {
        val r = Run()
        for (i in 0 until 450) r.feed(Run.bms("06", 1_000_000L + i * 200L))       // 90 s awake while plugged
        val started = r.eventsOf<SessionEvent.Started>().single().second as SessionEvent.Started
        assertEquals(SessionType.CHARGE_ATTEMPT, started.type)
        var t = r.lastTs
        while (t < r.lastTs + 140_000 && r.eventsOf<SessionEvent.Ended>().isEmpty()) { t += 5_000; r.tick(t) }
        val ended = r.eventsOf<SessionEvent.Ended>().single().second as SessionEvent.Ended
        assertEquals(SessionOutcome.NO_CHARGE, ended.outcome)
    }

    @Test
    fun drive_startsWhenReady_and_endsAfterIdle() {
        val r = Run.fixture("lx_state_changes.log.gz")
        val s = r.eventsOf<SessionEvent.Started>().single().second as SessionEvent.Started
        assertEquals(SessionType.DRIVE, s.type)
        assertTrue(r.eventsOf<SessionEvent.Ended>().isEmpty())     // READY ended only 50 s before the log ends
        r.tick(r.lastTs + 200_000)
        assertEquals(SessionOutcome.ENDED, (r.eventsOf<SessionEvent.Ended>().single().second as SessionEvent.Ended).outcome)
    }

    @Test
    fun acCharge_readyInParkAroundPauses_isOneSession() {
        // AC prep and both pauses show READY with gear P (drive side awake, contactors closed, DC link 344 V)
        val r = Run.fixture("ac_charge_pauses.log.gz")
        val started = r.eventsOf<SessionEvent.Started>().map { (it.second as SessionEvent.Started).type }
        assertEquals(listOf(SessionType.CHARGE_AC), started)
        assertTrue(r.eventsOf<SessionEvent.Ended>().isEmpty())     // last pause began 35 s before the log ends
        r.tick(r.lastTs + 61_000)
        val ended = r.eventsOf<SessionEvent.Ended>().single().second as SessionEvent.Ended
        assertEquals(SessionOutcome.ENDED, ended.outcome)
        assertTrue("ended at ${ended.endMs}", ended.endMs in 1789056788_000L..1789056790_000L)   // 0x15 left at +473.5 s
    }

    @Test
    fun drive_startsWhenGearLeavesPark_backdatedToReady() {
        val r = Run.fixture("lx_state_changes.log.gz")
        val readyAt = r.states.first { it.second.power == PowerState.READY }.first
        val firstGear = r.states.first { it.second.power == PowerState.READY && it.second.gear != 'P' }.first
        val (at, ev) = r.eventsOf<SessionEvent.Started>().single()
        assertEquals(firstGear, at)
        assertEquals(readyAt, (ev as SessionEvent.Started).startMs)
    }

    @Test
    fun readyInPark_withoutDriving_startsNothing() {
        val r = Run()
        for (i in 0 until 300) r.feedAll(Run.readyInPark(1_000_000L + i * 100L))     // 30 s READY, gear P
        assertTrue(r.eventsOf<SessionEvent.Started>().isEmpty())
    }

    @Test
    fun finish_closesOpenSession() {
        val r = Run.fixture("et_terra54_end.log.gz")
        val ev = r.detector.finish(r.lastTs)
        assertEquals(1, ev.size)
        assertTrue(ev[0] is SessionEvent.Ended)
    }
}
