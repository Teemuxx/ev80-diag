package io.github.ev80diag.session

enum class SessionType { CHARGE_AC, CHARGE_DC, CHARGE_ATTEMPT, DRIVE }
enum class SessionOutcome { COMPLETE, STOPPED_BY_BMS, ENDED, NO_CHARGE, INTERRUPTED }

sealed interface SessionEvent {
    /** [preRollFromMs]: the raw log should include frames from this time on (the lead-in). */
    data class Started(val type: SessionType, val startMs: Long, val preRollFromMs: Long) : SessionEvent
    data class TypeChanged(val type: SessionType, val atMs: Long) : SessionEvent
    data class Ended(val endMs: Long, val outcome: SessionOutcome) : SessionEvent
}

/**
 * Turns the van state into sessions (spec §6/§11):
 * - a charge *attempt* starts when the BMS has been awake while plugged in for [attemptConfirmMs]
 *   (so the half second at an ignition off/on cycle doesn't count), back-dated to the first moment;
 *   it becomes a charge on 0x15/0x16;
 * - a charge ends 60 s after leaving the charging states (immediately when the BMS stops it);
 * - an attempt that never charges ends after 120 s of silence, or as soon as the van is driven;
 * - a drive starts when the van is READY *and out of P*, back-dated to the start of READY, and runs until
 *   the van has not been READY for 120 s. READY in P alone is also how the van looks while it prepares or
 *   pauses an AC charge (drive side awake, contactors closed), so it neither starts a drive nor ends a charge
 *   at once: an open charge then gets the same 60 s as any other pause.
 * Short gaps never split a session; a silence ends a session at the last frame seen.
 */
class SessionDetector(
    private val preRollMs: Long = 120_000,
    private val chargeEndMs: Long = 60_000,
    private val idleEndMs: Long = 120_000,
    private val attemptConfirmMs: Long = 5_000,
) {
    private class Open(var type: SessionType, val startMs: Long) {
        var leftAt: Long? = null
        var leftVia: SessionOutcome = SessionOutcome.ENDED
    }

    private var open: Open? = null
    private var offSince: Long? = null
    private var pluggedSince: Long? = null
    private var readySince: Long? = null
    private var lastActive = 0L

    private val Open.isCharge get() = type == SessionType.CHARGE_AC || type == SessionType.CHARGE_DC
    private val Open.isAttempt get() = type == SessionType.CHARGE_ATTEMPT
    private val Open.isDrive get() = type == SessionType.DRIVE

    fun onState(s: VanState, nowMs: Long): List<SessionEvent> {
        val ev = ArrayList<SessionEvent>(2)
        if (s.power != PowerState.OFF) { lastActive = nowMs; offSince = null } else if (offSince == null) offSince = nowMs
        if (s.power != PowerState.PLUGGED) pluggedSince = null
        if (s.power != PowerState.READY) readySince = null
        val o = open
        when (s.power) {
            PowerState.CHARGING_AC, PowerState.CHARGING_DC -> {
                val t = if (s.power == PowerState.CHARGING_AC) SessionType.CHARGE_AC else SessionType.CHARGE_DC
                if (o != null && o.isDrive) end(ev, nowMs, SessionOutcome.ENDED)
                val cur = open
                if (cur == null) start(ev, t, nowMs)
                else {
                    cur.leftAt = null
                    if (cur.type != t) { cur.type = t; ev.add(SessionEvent.TypeChanged(t, nowMs)) }
                }
            }
            PowerState.PLUGGED -> {
                if (o != null && o.isDrive) end(ev, nowMs, SessionOutcome.ENDED)
                val cur = open
                if (cur == null) {
                    val since = pluggedSince ?: nowMs.also { pluggedSince = it }
                    if (nowMs - since >= attemptConfirmMs) start(ev, SessionType.CHARGE_ATTEMPT, since)
                } else if (cur.isCharge) leave(cur, nowMs, SessionOutcome.ENDED)
            }
            PowerState.CHARGE_COMPLETE -> if (o != null && o.isCharge) leave(o, nowMs, SessionOutcome.COMPLETE, overwrite = true)
            PowerState.STOPPED_BY_BMS -> if (o != null && !o.isDrive) end(ev, nowMs, SessionOutcome.STOPPED_BY_BMS)
            PowerState.READY -> {
                val since = readySince ?: nowMs.also { readySince = it }
                val outOfPark = s.gear != null && s.gear != 'P'
                when {
                    o != null && o.isDrive -> o.leftAt = null
                    !outOfPark -> if (o != null && o.isCharge) leave(o, nowMs, SessionOutcome.ENDED)   // an attempt stays open
                    else -> {
                        var from = since
                        if (o != null && o.isAttempt) end(ev, nowMs, SessionOutcome.NO_CHARGE)
                        if (o != null && o.isCharge) {
                            val chargeEnd = o.leftAt ?: nowMs
                            end(ev, chargeEnd, o.leftVia)
                            from = maxOf(from, chargeEnd)
                        }
                        start(ev, SessionType.DRIVE, from)
                    }
                }
            }
            PowerState.ON -> when {
                o == null -> {}
                o.isAttempt -> end(ev, nowMs, SessionOutcome.NO_CHARGE)
                o.isCharge -> leave(o, nowMs, SessionOutcome.ENDED)
                o.isDrive -> leave(o, nowMs, SessionOutcome.ENDED)
            }
            PowerState.OFF -> {
                if (o != null && (o.isCharge || o.isDrive)) leave(o, s.lastFrameMs, SessionOutcome.ENDED)
            }
        }
        expire(ev, nowMs)
        return ev
    }

    /** Closes whatever is open (service stopping). */
    fun finish(nowMs: Long): List<SessionEvent> {
        val o = open ?: return emptyList()
        val ev = ArrayList<SessionEvent>(1)
        end(ev, o.leftAt ?: nowMs, if (o.isAttempt) SessionOutcome.NO_CHARGE else o.leftVia)
        return ev
    }

    private fun start(ev: MutableList<SessionEvent>, t: SessionType, now: Long) {
        open = Open(t, now)
        ev.add(SessionEvent.Started(t, now, now - preRollMs))
    }

    private fun leave(o: Open, now: Long, via: SessionOutcome, overwrite: Boolean = false) {
        if (o.leftAt == null) { o.leftAt = now; o.leftVia = via } else if (overwrite) o.leftVia = via
    }

    private fun end(ev: MutableList<SessionEvent>, endMs: Long, outcome: SessionOutcome) {
        if (open == null) return
        open = null
        ev.add(SessionEvent.Ended(endMs, outcome))
    }

    private fun expire(ev: MutableList<SessionEvent>, now: Long) {
        val o = open ?: return
        val left = o.leftAt
        if (left != null) {
            val limit = if (o.isDrive) idleEndMs else chargeEndMs
            if (now - left >= limit) end(ev, left, o.leftVia)
        } else if (o.isAttempt) {
            val off = offSince
            if (off != null && now - off >= idleEndMs) end(ev, lastActive, SessionOutcome.NO_CHARGE)
        }
    }
}
