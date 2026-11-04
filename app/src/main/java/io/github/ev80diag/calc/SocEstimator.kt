package io.github.ev80diag.calc

/** Where the current SOC value was last pinned to a known reference. */
enum class AnchorKind { NONE, MANUAL, FULL, LOW_VOLTAGE }

enum class SocConfidence {
    /** Never calibrated: no SOC shown. */
    UNCALIBRATED,
    /** Calibrated, but current samples were missed since the last anchor (app/BT offline). */
    STALE,
    /** Calibrated and continuously integrated since the last anchor. */
    OK
}

/** Everything that must survive an app restart. */
data class SocState(
    val calibrated: Boolean = false,
    val capacityAh: Float = 168f,
    /** Ah discharged since 100 % (0 = full). Clamped to 0..capacityAh. */
    val consumedAh: Float = 0f,
    /** Signed net Ah discharged since the last anchor, NOT clamped (used for capacity learning). */
    val ahSinceAnchor: Float = 0f,
    val lastUpdateTs: Long = 0L,
    val lastAnchorTs: Long = 0L,
    val anchorKind: AnchorKind = AnchorKind.NONE,
    /** True while the integration since the last anchor is continuous (no gaps). */
    val continuous: Boolean = true,
    /** True when the last FULL anchor has been followed only by continuous integration. */
    val sinceFullContinuous: Boolean = false
)

data class SocSnapshot(
    val socPercent: Float?,
    val remainingAh: Float?,
    val capacityAh: Float,
    val confidence: SocConfidence,
    val anchorKind: AnchorKind,
    val lastAnchorTs: Long
)

/**
 * Own state-of-charge estimate: coulomb counting from pack current (0x550), re-pinned
 * ("anchored") whenever the cells reveal a known SOC. LiFePO4 voltage is flat from ~20 % to ~90 %,
 * so voltage is only trusted at the two ends:
 *
 *  - FULL: highest cell >= [FULL_CELL_MV] with |I| <= [FULL_MAX_CURRENT_A] for [FULL_HOLD_MS]
 *    (the BMS stops charging on the max cell, so this is "full" in usable terms even when the
 *    pack is unbalanced and other cells are far lower).
 *  - LOW_VOLTAGE: sag-corrected lowest cell <= [LOW_CELL_MV] (mapped with [lowVoltageSoc]).
 *
 * Pure logic, no Android dependencies. Not thread-safe; [SocTracker] serialises access.
 *
 * Current sign: positive = discharge, negative = charge (0x550).
 */
class SocEstimator(initial: SocState = SocState()) {

    var state: SocState = initial
        private set

    private var fullSinceTs = 0L
    private var fullLatched = false
    private var lastLowAnchorTs = 0L

    /** Replace the state with one loaded from storage. The next sample re-baselines (no integration). */
    fun restore(loaded: SocState) {
        state = loaded
        lastTs = 0L
        fullSinceTs = 0L
        fullLatched = false
    }

    // ── Inputs ──────────────────────────────────────────────────────────────

    /** Feed a pack-current sample (call at 0x550 rate). */
    fun onCurrent(ts: Long, currentA: Float) {
        val s = state
        if (!s.calibrated) {
            lastCurrent = currentA; lastTs = ts
            return
        }
        // Never integrate across the offline period; a long gap marks the estimate stale.
        val prevTs = if (lastTs != 0L) lastTs else s.lastUpdateTs
        val dtMs = ts - prevTs
        if (dtMs < 0) return // clock went backwards: ignore sample
        var continuous = s.continuous
        var consumed = s.consumedAh
        var since = s.ahSinceAnchor
        var sinceFullContinuous = s.sinceFullContinuous
        if (dtMs > MAX_GAP_MS) {
            continuous = false
            sinceFullContinuous = false
        } else if (lastTs != 0L) {
            // trapezoid between two consecutive samples (first sample after restore/anchor only re-baselines)
            val dAh = (currentA + lastCurrent) / 2f * (dtMs / 3_600_000f)
            consumed = (consumed + dAh).coerceIn(0f, s.capacityAh)
            since += dAh
        }
        lastCurrent = currentA
        lastTs = ts
        state = s.copy(
            consumedAh = consumed, ahSinceAnchor = since, lastUpdateTs = ts,
            continuous = continuous, sinceFullContinuous = sinceFullContinuous
        )
    }

    /**
     * Feed a cell-extremes sample (mV) together with the current pack current.
     * @return true if an anchor was applied (caller should persist immediately).
     */
    fun onCellExtremes(ts: Long, maxCellMv: Int, minCellMv: Int, currentA: Float): Boolean {
        // No current samples for a while (e.g. cell-voltage-only monitor mode): integration is blind.
        if (state.calibrated && state.continuous && ts - state.lastUpdateTs > MAX_GAP_MS) {
            state = state.copy(continuous = false, sinceFullContinuous = false)
        }
        // FULL anchor
        if (maxCellMv >= FULL_CELL_MV && kotlin.math.abs(currentA) <= FULL_MAX_CURRENT_A) {
            if (fullSinceTs == 0L) fullSinceTs = ts
            if (!fullLatched && ts - fullSinceTs >= FULL_HOLD_MS) {
                fullLatched = true // re-arm only after the cell has dropped below the threshold
                anchorFull(ts)
                return true
            }
        } else {
            fullSinceTs = 0L
            fullLatched = false
        }

        // LOW anchor (sag-corrected lowest cell)
        val correctedMv = minCellMv + currentA * SAG_MV_PER_A
        if (correctedMv <= LOW_CELL_MV && ts - lastLowAnchorTs >= LOW_ANCHOR_INTERVAL_MS) {
            lastLowAnchorTs = ts
            anchorLow(ts, lowVoltageSoc(correctedMv))
            return true
        }
        return false
    }

    /** User-set SOC (first install, after a balance, or to correct drift). */
    fun setManual(ts: Long, socPercent: Float) {
        val s = state
        val soc = socPercent.coerceIn(0f, 100f)
        state = s.copy(
            calibrated = true,
            consumedAh = s.capacityAh * (1f - soc / 100f),
            ahSinceAnchor = 0f, lastUpdateTs = ts, lastAnchorTs = ts,
            anchorKind = AnchorKind.MANUAL, continuous = true,
            sinceFullContinuous = false
        )
        lastTs = 0L // next sample re-baselines without integrating
    }

    fun setCapacity(capacityAh: Float) {
        if (capacityAh <= 0f) return
        val s = state
        val soc = socFraction(s)
        state = s.copy(
            capacityAh = capacityAh,
            consumedAh = if (s.calibrated) capacityAh * (1f - soc) else s.consumedAh
        )
    }

    // ── Output ──────────────────────────────────────────────────────────────

    fun snapshot(): SocSnapshot {
        val s = state
        if (!s.calibrated) {
            return SocSnapshot(null, null, s.capacityAh, SocConfidence.UNCALIBRATED, AnchorKind.NONE, 0L)
        }
        val remaining = (s.capacityAh - s.consumedAh).coerceIn(0f, s.capacityAh)
        return SocSnapshot(
            socPercent = remaining / s.capacityAh * 100f,
            remainingAh = remaining,
            capacityAh = s.capacityAh,
            confidence = if (s.continuous) SocConfidence.OK else SocConfidence.STALE,
            anchorKind = s.anchorKind,
            lastAnchorTs = s.lastAnchorTs
        )
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private var lastTs = 0L
    private var lastCurrent = 0f

    private fun socFraction(s: SocState) =
        if (s.capacityAh > 0f) (1f - s.consumedAh / s.capacityAh).coerceIn(0f, 1f) else 0f

    private fun anchorFull(ts: Long) {
        val s = state
        state = s.copy(
            calibrated = true, consumedAh = 0f, ahSinceAnchor = 0f,
            lastUpdateTs = ts, lastAnchorTs = ts, anchorKind = AnchorKind.FULL,
            continuous = true, sinceFullContinuous = true
        )
    }

    private fun anchorLow(ts: Long, soc: Float) {
        val s = state
        var capacity = s.capacityAh
        // Learn capacity from a gap-free FULL -> LOW discharge (voltage map is approximate,
        // so only nudge, and only for a big enough swing).
        if (s.anchorKind == AnchorKind.FULL && s.sinceFullContinuous && s.ahSinceAnchor >= LEARN_MIN_AH && soc < 50f) {
            val implied = s.ahSinceAnchor / (1f - soc / 100f)
            capacity = (0.7f * capacity + 0.3f * implied).coerceIn(CAPACITY_MIN_AH, CAPACITY_MAX_AH)
        }
        state = s.copy(
            calibrated = true, capacityAh = capacity,
            consumedAh = capacity * (1f - soc / 100f), ahSinceAnchor = 0f,
            lastUpdateTs = ts, lastAnchorTs = ts, anchorKind = AnchorKind.LOW_VOLTAGE,
            continuous = true, sinceFullContinuous = false
        )
    }

    companion object {
        const val MAX_GAP_MS = 36_000L
        const val FULL_CELL_MV = 3550
        const val FULL_MAX_CURRENT_A = 10f
        const val FULL_HOLD_MS = 5_000L
        const val LOW_CELL_MV = 3100f
        const val LOW_ANCHOR_INTERVAL_MS = 60_000L
        /** Voltage sag compensation, same constant the Live screen's V-SOC has always used. */
        const val SAG_MV_PER_A = 0.6f
        const val LEARN_MIN_AH = 80f
        const val CAPACITY_MIN_AH = 100f
        const val CAPACITY_MAX_AH = 200f

        /** Low-end SOC from sag-corrected min-cell mV: 2900 mV = 0 %, 3200 mV = 20 % (existing V-SOC map). */
        fun lowVoltageSoc(correctedMv: Float): Float =
            ((correctedMv - 2900f) / 300f * 20f).coerceIn(0f, 20f)
    }
}
