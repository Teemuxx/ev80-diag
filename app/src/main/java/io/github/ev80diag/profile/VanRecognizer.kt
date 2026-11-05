package io.github.ev80diag.profile

/** A van's recognition fingerprint: the last odometer and lifetime charge counter seen on it. */
data class VanPrint(val id: Long, val lastOdoKm: Double?, val lastChargeKwh: Double?)

enum class Evidence { ODOMETER, CHARGE_COUNTER }

sealed interface Recognition {
    data class Match(val id: Long, val by: Evidence) : Recognition
    /** The vans that fit, or all vans when none does. */
    data class Unclear(val candidates: List<Long>) : Recognition
    data object NoEvidence : Recognition
}

/**
 * Which van is this? The odometer (`0x380`, ignition on) and the charge counter (`0x590`, also while only the BMS is
 * awake) only ever rise, so a van fits when the value seen is at or a little above its last one.
 */
object VanRecognizer {
    const val MAX_ODO_GAP_KM = 3_000.0
    const val MAX_CHARGE_GAP_KWH = 300.0
    private const val TOLERANCE = 0.2

    fun recognize(vans: List<VanPrint>, odoKm: Double?, chargeKwh: Double?): Recognition {
        val odo = odoKm?.takeIf { it > 0 }
        val charge = chargeKwh?.takeIf { it > 0 }
        if (odo == null && charge == null) return Recognition.NoEvidence
        val fitting = ArrayList<Pair<Long, Evidence>>()
        val incomparable = ArrayList<Long>()     // never seen, or seen only with the other value
        for (v in vans) {
            val checks = buildList {
                if (odo != null && v.lastOdoKm != null) add(Evidence.ODOMETER to fits(odo, v.lastOdoKm, MAX_ODO_GAP_KM))
                if (charge != null && v.lastChargeKwh != null) add(Evidence.CHARGE_COUNTER to fits(charge, v.lastChargeKwh, MAX_CHARGE_GAP_KWH))
            }
            when {
                checks.isEmpty() -> incomparable += v.id
                checks.all { it.second } -> fitting += v.id to checks.first().first
            }
        }
        return when {
            fitting.size == 1 -> Recognition.Match(fitting[0].first, fitting[0].second)
            fitting.size > 1 -> Recognition.Unclear(fitting.map { it.first })
            incomparable.size == 1 -> Recognition.Match(incomparable[0], if (odo != null) Evidence.ODOMETER else Evidence.CHARGE_COUNTER)
            incomparable.size > 1 -> Recognition.Unclear(incomparable)
            else -> Recognition.Unclear(vans.map { it.id })
        }
    }

    private fun fits(seen: Double, last: Double, gap: Double) = seen >= last - TOLERANCE && seen <= last + gap
}
