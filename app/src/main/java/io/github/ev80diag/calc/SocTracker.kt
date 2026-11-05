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
    private var loadedState = SocState()

    @Synchronized
    fun init(context: Context) {
        if (store != null) return
        store = SocStore(PrefsKeyValue(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)))
        defaultCapacityAh = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getFloat("capacity_ah", defaultCapacityAh)
    }

    /** JVM tests: a fresh tracker on [kv]. */
    @Synchronized
    internal fun initForTest(kv: io.github.ev80diag.data.KeyValue) {
        store = SocStore(kv); activeProfileId = null; lastSaveTs = 0L
        estimator.restore(SocState()); publish()
    }

    /**
     * Saves the current van's state and loads [profileId]'s. [revertCurrent]: the current van was recorded by mistake
     * (Undo of a switch), so it keeps the state it had when it was activated. The single 0.3 state goes to the first
     * van activated (after the update there is only one).
     */
    @Synchronized
    fun activate(profileId: Long, revertCurrent: Boolean = false) {
        if (profileId == activeProfileId) return
        val s = store
        activeProfileId?.let { s?.save(it, if (revertCurrent) loadedState else estimator.state) }
        s?.migrateLegacy(profileId)
        activeProfileId = profileId
        loadedState = s?.load(profileId, defaultCapacityAh) ?: SocState(capacityAh = defaultCapacityAh)
        estimator.restore(loadedState)
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
