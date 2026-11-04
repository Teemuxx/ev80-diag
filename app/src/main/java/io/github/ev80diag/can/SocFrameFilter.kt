package io.github.ev80diag.can

/**
 * Drops the burst of nonsense 0x322 frames the BMS emits for ~400 ms after (re)waking.
 * Frames are accepted only once [settleMs] have passed since the first frame after a gap
 * longer than [gapMs].
 */
class SocFrameFilter(private val gapMs: Long = 5_000, private val settleMs: Long = 1_000) {

    private var lastFrameTs = Long.MIN_VALUE
    private var burstStartTs = Long.MIN_VALUE

    fun accept(timestamp: Long): Boolean {
        if (lastFrameTs == Long.MIN_VALUE || timestamp - lastFrameTs > gapMs) {
            burstStartTs = timestamp
        }
        lastFrameTs = timestamp
        return timestamp - burstStartTs >= settleMs
    }
}
