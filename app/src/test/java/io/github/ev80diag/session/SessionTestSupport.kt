package io.github.ev80diag.session

import io.github.ev80diag.TestLogs
import io.github.ev80diag.TestLogs.frame
import io.github.ev80diag.can.CanFrame
import io.github.ev80diag.signals.Decoder

/** Runs frames through Decoder -> VanStateTracker -> SessionDetector and records what came out. */
class Run {
    val decoder = Decoder()
    val tracker = VanStateTracker()
    val detector = SessionDetector()
    val states = ArrayList<Pair<Long, VanState>>()
    val events = ArrayList<Pair<Long, SessionEvent>>()
    val insulationFaultAt = ArrayList<Long>()
    var lastTs = 0L

    fun feed(f: CanFrame) {
        val decoded = decoder.decode(f)
        if (decoded["insulation_fault"] == 1.0) insulationFaultAt.add(f.timestamp)
        val s = tracker.onFrame(f, decoded)
        states.add(f.timestamp to s)
        detector.onState(s, f.timestamp).forEach { events.add(f.timestamp to it) }
        lastTs = f.timestamp
    }

    fun tick(nowMs: Long) {
        val s = tracker.onTick(nowMs)
        states.add(nowMs to s)
        detector.onState(s, nowMs).forEach { events.add(nowMs to it) }
        lastTs = nowMs
    }

    fun feedAll(frames: List<CanFrame>) = frames.forEach { feed(it) }
    fun stateAt(tsMs: Long): VanState = states.last { it.first <= tsMs }.second
    inline fun <reified T : SessionEvent> eventsOf() = events.filter { it.second is T }

    companion object {
        fun fixture(name: String) = Run().also { it.feedAll(TestLogs.load(name)) }
        fun bms(state: String, ts: Long) = frame(0x200, state + "00006404010000".substring(0, 14), ts)
        /** One round of the frames that make READY with the gear in P: drive side, contactors, 344 V DC link, gear. */
        fun readyInPark(ts: Long) = listOf(
            frame(0x300, "0000000000000000", ts), frame(0x580, "0005000000000000", ts),
            frame(0x301, "720D000000000000", ts), frame(0x230, "0800000000000000", ts),
        )
    }
}
