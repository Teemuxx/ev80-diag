package io.github.ev80diag.calc

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide holder of the [SocEstimator]: persisted in SharedPreferences (independent of the
 * log database, so clearing the DB cannot corrupt it) and shared by the monitor service and the
 * Settings screen. Call [init] once from Application.onCreate.
 */
object SocTracker {

    private const val PREFS = "soc_state"
    private const val SAVE_INTERVAL_MS = 10_000L

    private var prefs: android.content.SharedPreferences? = null
    private val estimator = SocEstimator()
    private val _snapshot = MutableStateFlow(estimator.snapshot())
    val snapshot: StateFlow<SocSnapshot> = _snapshot
    private var lastSaveTs = 0L

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val settings = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val loaded = SocState(
            calibrated = p.getBoolean("calibrated", false),
            capacityAh = p.getFloat("capacityAh", settings.getFloat("capacity_ah", 168f)),
            consumedAh = p.getFloat("consumedAh", 0f),
            ahSinceAnchor = p.getFloat("ahSinceAnchor", 0f),
            lastUpdateTs = p.getLong("lastUpdateTs", 0L),
            lastAnchorTs = p.getLong("lastAnchorTs", 0L),
            anchorKind = AnchorKind.entries.getOrElse(p.getInt("anchorKind", 0)) { AnchorKind.NONE },
            continuous = p.getBoolean("continuous", true),
            sinceFullContinuous = p.getBoolean("sinceFullContinuous", false)
        )
        estimator.restore(loaded)
        publish()
    }

    @Synchronized
    fun onCurrent(ts: Long, currentA: Float) {
        estimator.onCurrent(ts, currentA)
        publish()
        if (ts - lastSaveTs >= SAVE_INTERVAL_MS) save(ts)
    }

    @Synchronized
    fun onCellExtremes(ts: Long, maxCellMv: Int, minCellMv: Int, currentA: Float) {
        val anchored = estimator.onCellExtremes(ts, maxCellMv, minCellMv, currentA)
        publish() // also reflects a stale transition
        if (anchored) save(ts)
    }

    @Synchronized
    fun setManual(socPercent: Float) {
        val ts = System.currentTimeMillis()
        estimator.setManual(ts, socPercent)
        publish()
        save(ts)
    }

    @Synchronized
    fun setCapacity(capacityAh: Float) {
        estimator.setCapacity(capacityAh)
        publish()
        save(System.currentTimeMillis())
    }

    /** Persist now (service shutdown). */
    @Synchronized
    fun flush() = save(System.currentTimeMillis())

    private fun publish() { _snapshot.value = estimator.snapshot() }

    private fun save(ts: Long) {
        lastSaveTs = ts
        val s = estimator.state
        prefs?.edit()
            ?.putBoolean("calibrated", s.calibrated)
            ?.putFloat("capacityAh", s.capacityAh)
            ?.putFloat("consumedAh", s.consumedAh)
            ?.putFloat("ahSinceAnchor", s.ahSinceAnchor)
            ?.putLong("lastUpdateTs", s.lastUpdateTs)
            ?.putLong("lastAnchorTs", s.lastAnchorTs)
            ?.putInt("anchorKind", s.anchorKind.ordinal)
            ?.putBoolean("continuous", s.continuous)
            ?.putBoolean("sinceFullContinuous", s.sinceFullContinuous)
            ?.apply()
    }
}
