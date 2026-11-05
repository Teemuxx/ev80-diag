package io.github.ev80diag.pipeline

import io.github.ev80diag.calc.SocConfidence
import io.github.ev80diag.calc.SocEstimator
import io.github.ev80diag.calc.SocSnapshot
import io.github.ev80diag.calc.SocTracker

/** Where the pipeline sends the values the app's own SOC estimate needs. */
interface SocFeed {
    fun onCurrent(tsMs: Long, currentA: Float)
    fun onCellExtremes(tsMs: Long, maxMv: Int, minMv: Int, currentA: Float)
    /** null when unknown, or stale (data missed since the last anchor, e.g. driven without the app). */
    fun socPercent(): Float?
    /**
     * The recorded van is settled as [profileId]; input that follows belongs to its estimate. [revertCurrent]: the van
     * recorded until now was wrong (Undo), so its estimate goes back to how it was before.
     */
    fun onProfile(profileId: Long, revertCurrent: Boolean = false) {}

    object None : SocFeed {
        override fun onCurrent(tsMs: Long, currentA: Float) {}
        override fun onCellExtremes(tsMs: Long, maxMv: Int, minMv: Int, currentA: Float) {}
        override fun socPercent(): Float? = null
    }

    object Tracker : SocFeed {
        override fun onCurrent(tsMs: Long, currentA: Float) = SocTracker.onCurrent(tsMs, currentA)
        override fun onCellExtremes(tsMs: Long, maxMv: Int, minMv: Int, currentA: Float) =
            SocTracker.onCellExtremes(tsMs, maxMv, minMv, currentA)
        override fun socPercent(): Float? = shown(SocTracker.snapshot.value)
        override fun onProfile(profileId: Long, revertCurrent: Boolean) = SocTracker.activate(profileId, revertCurrent)
    }

    /** An in-memory estimate for replays: an old log must not overwrite the van's saved SOC state. */
    class Fresh : SocFeed {
        private val estimator = SocEstimator()
        override fun onCurrent(tsMs: Long, currentA: Float) = estimator.onCurrent(tsMs, currentA)
        override fun onCellExtremes(tsMs: Long, maxMv: Int, minMv: Int, currentA: Float) {
            estimator.onCellExtremes(tsMs, maxMv, minMv, currentA)
        }
        override fun socPercent(): Float? = shown(estimator.snapshot())
    }

    private companion object {
        fun shown(s: SocSnapshot): Float? = s.socPercent.takeIf { s.confidence == SocConfidence.OK }
    }
}
