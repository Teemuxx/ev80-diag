package io.github.ev80diag.transport

import io.github.ev80diag.can.CanFrame

/** candump -L text: `(1793273355.027116) can0 200#0600006404010000`. 11-bit IDs only. */
object CandumpLine {
    private val LINE = Regex("""^\((\d+)\.(\d{6})\)\s+\S+\s+([0-9A-Fa-f]{3})#((?:[0-9A-Fa-f]{2})*)$""")

    fun parse(line: String): CanFrame? {
        val m = LINE.matchEntire(line.trim()) ?: return null
        val hex = m.groupValues[4]
        val data = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val ts = m.groupValues[1].toLong() * 1000 + m.groupValues[2].toLong() / 1000
        return CanFrame(m.groupValues[3].toInt(16), data, ts)
    }

    fun format(f: CanFrame): String {
        val sb = StringBuilder(48)
        sb.append('(').append(f.timestamp / 1000).append('.')
            .append((f.timestamp % 1000).toString().padStart(3, '0')).append("000) can0 ")
            .append(f.id.toString(16).uppercase().padStart(3, '0')).append('#')
        for (b in f.data) sb.append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        return sb.toString()
    }

    private const val HEX = "0123456789ABCDEF"
}
