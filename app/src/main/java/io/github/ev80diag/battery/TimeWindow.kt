package io.github.ev80diag.battery

/**
 * The period the Battery screen shows. [followNow]: the window ends at "now" and moves along with it.
 * Nothing ever reaches past now.
 */
data class TimeWindow(val fromMs: Long, val toMs: Long, val followNow: Boolean) {
    val lengthMs: Long get() = toMs - fromMs

    /** One stored snapshot per bucket, so even 30 days load about 400 rows. */
    val bucketMs: Long get() = maxOf(1_000L, lengthMs / 400)

    fun earlier() = TimeWindow(fromMs - lengthMs, toMs - lengthMs, false)

    fun later(nowMs: Long) = shiftedTo(toMs + lengthMs, lengthMs, nowMs)

    fun zoomOut(nowMs: Long): TimeWindow {
        val len = lengthMs * 2
        return shiftedTo((fromMs + toMs) / 2 + len / 2, len, nowMs)
    }

    /** [factor] > 1 zooms in; the moment at [focusMs] stays at the same place on screen. */
    fun zoom(factor: Double, focusMs: Long, nowMs: Long): TimeWindow {
        val len = maxOf(MIN_LENGTH_MS, (lengthMs / factor).toLong())
        val frac = if (lengthMs > 0) (focusMs - fromMs).toDouble() / lengthMs else 0.5
        val from = focusMs - (frac * len).toLong()
        return shiftedTo(from + len, len, nowMs)
    }

    fun pan(deltaMs: Long, nowMs: Long) = shiftedTo(toMs + deltaMs, lengthMs, nowMs)

    private fun shiftedTo(to: Long, len: Long, nowMs: Long): TimeWindow {
        val end = minOf(to, nowMs)
        return TimeWindow(end - len, end, end >= nowMs)
    }

    companion object {
        const val MIN_LENGTH_MS = 60_000L
        private const val MIN = 60_000L
        private const val HOUR = 60 * MIN
        private const val DAY = 24 * HOUR
        /** Quick ranges of the time picker: 15 min, 1 h, 6 h, 24 h, 7 d, 30 d. */
        val QUICK = listOf(15 * MIN, HOUR, 6 * HOUR, DAY, 7 * DAY, 30 * DAY)

        fun last(durationMs: Long, nowMs: Long) = TimeWindow(nowMs - durationMs, nowMs, true)
    }
}
