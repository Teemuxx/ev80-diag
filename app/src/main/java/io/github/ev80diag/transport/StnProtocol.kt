package io.github.ev80diag.transport

import io.github.ev80diag.can.CanFrame

enum class LineKind { FRAME, CAN_ERROR, BUFFER_FULL, STOPPED, PROMPT, OTHER }
enum class MonitorAction { CONTINUE, RESTART, REFUSE }

/**
 * Command set and line parsing for STN-chip adapters (OBDLink). Listen-only: the init list holds
 * configuration commands only (no OBD requests) and monitoring uses STMA.
 */
object StnProtocol {
    /** ATSP6 = ISO 15765, 11 bit, 500 kbit/s; ATCSM1 = silent monitoring; STI identifies the chip. */
    val initCommands = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH1", "ATSP6", "ATCAF0", "ATCSM1", "STI")
    const val MONITOR_COMMAND = "STMA"

    private val FRAME = Regex("^([0-9A-F]{3})((?:[0-9A-F]{2}){1,8})$")

    fun isStn(stiReply: String): Boolean = stiReply.uppercase().contains("STN")

    private fun compact(line: String) = line.uppercase().filterNot { it == ' ' || it == '>' || it == '\n' }

    fun parseMonitorLine(line: String, timestampMs: Long): CanFrame? {
        val m = FRAME.matchEntire(compact(line)) ?: return null
        val hex = m.groupValues[2]
        val data = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return CanFrame(m.groupValues[1].toInt(16), data, timestampMs)
    }

    fun classify(line: String): LineKind {
        val c = compact(line)
        return when {
            c.isEmpty() -> if (line.contains('>')) LineKind.PROMPT else LineKind.OTHER
            c.contains("CANERROR") -> LineKind.CAN_ERROR
            c.contains("BUFFERFULL") -> LineKind.BUFFER_FULL
            c.contains("STOPPED") -> LineKind.STOPPED
            FRAME.matches(c) -> LineKind.FRAME
            else -> LineKind.OTHER
        }
    }
}

/** What the source does about a line / an identification result. CAN ERROR is normal at wake and sleep. */
object MonitorPolicy {
    fun onLine(kind: LineKind): MonitorAction = when (kind) {
        LineKind.STOPPED, LineKind.BUFFER_FULL -> MonitorAction.RESTART
        else -> MonitorAction.CONTINUE
    }

    fun afterSti(reply: String): MonitorAction =
        if (StnProtocol.isStn(reply)) MonitorAction.CONTINUE else MonitorAction.REFUSE

    /** ATCSM1 must be confirmed: without silent monitoring the adapter would acknowledge frames on the bus. */
    fun afterCsm(reply: String): MonitorAction =
        if (reply.uppercase().contains("OK")) MonitorAction.CONTINUE else MonitorAction.REFUSE
}
