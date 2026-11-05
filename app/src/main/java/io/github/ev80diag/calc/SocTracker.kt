package io.github.ev80diag.calc

import android.content.Context
import io.github.ev80diag.data.PrefsKeyValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide holder of the active van's [SocEstimator]: persisted per van in SharedPreferences (independent of the
 * log database, so clearing the DB cannot corrupt it) and shared by the recorder and the screens.
 * Call [init] once from Application.onCreate, then [activate] with the active van.
 */
object SocTracker {

    private const val PREFS = "soc_state"
    private const val SAVE_INTERVAL_MS = 10_000L

    private var store: SocStore? = null
    /** 0.3 kept the capacity in the old "settings" prefs; used as the default for vans without a saved state. */
    private var defaultCapacityAh = SocState().capacityAh
    private val estimator = SocEstimator()
    private val _snapshot = MutableStateFlow(estimator.snapshot())
    val snapshot: StateFlow<SocSnapshot> = _snapshot
    private var lastSaveTs = 0L
    @Volatile var activeProfileId: Long? = null
        private set

    @Synchronized
    fun init(context: Context) {
        if (store != null) return
        store = SocStore(PrefsKeyValue(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)))
        defaultCapacityAh = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getFloat("capacity_ah", defaultCapacityAh)
    }

    /** Moves the single 0.3 state to the first van (once). */
    @Synchronized
    fun migrateLegacy(firstProfileId: Long) { store?.migrateLegacy(firstProfileId) }

    /** Saves the current van's state and loads [profileId]'s. */
    @Synchronized
    fun activate(profileId: Long) {
        if (profileId == activeProfileId) return
        activeProfileId?.let { store?.save(it, estimator.state) }
        activeProfileId = profileId
        estimator.restore(store?.load(profileId, defaultCapacityAh) ?: SocState(capacityAh = defaultCapacityAh))
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
    fun capacityOf(profileId: Long): Float =
        if (profileId == activeProfileId) estimator.state.capacityAh
        else store?.load(profileId, defaultCapacityAh)?.capacityAh ?: defaultCapacityAh

    @Synchronized
    fun setCapacity(profileId: Long, capacityAh: Float) {
        if (profileId == activeProfileId) {
            estimator.setCapacity(capacityAh)
            publish()
            save(System.currentTimeMillis())
        } else store?.let { it.save(profileId, it.load(profileId, defaultCapacityAh).copy(capacityAh = capacityAh)) }
    }

    @Synchronized
    fun delete(profileId: Long) {
        store?.delete(profileId)
        if (profileId == activeProfileId) activeProfileId = null
    }

    /** Persist now (service shutdown). */
    @Synchronized
    fun flush() = save(System.currentTimeMillis())

    private fun publish() { _snapshot.value = estimator.snapshot() }

    private fun save(ts: Long) {
        lastSaveTs = ts
        val id = activeProfileId ?: return
        store?.save(id, estimator.state)
    }
}
