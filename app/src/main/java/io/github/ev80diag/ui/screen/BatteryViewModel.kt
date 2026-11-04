package io.github.ev80diag.ui.screen

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ev80diag.battery.CellSeries
import io.github.ev80diag.battery.CellSeriesData
import io.github.ev80diag.battery.CellStats
import io.github.ev80diag.battery.CellView
import io.github.ev80diag.battery.TimeWindow
import io.github.ev80diag.data.store.CellBlob
import io.github.ev80diag.data.store.Ev80Database
import io.github.ev80diag.data.store.SessionEntity
import io.github.ev80diag.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch

/** The snapshot shown in the heatmap (at the chosen time) and the chart data over the window. */
data class BatteryUi(
    val window: TimeWindow,
    val snapshotMs: Long?,
    val view: CellView?,
    val charging: Boolean,
    val series: CellSeriesData?,
    val extra: Set<Int>,
)

@OptIn(ExperimentalCoroutinesApi::class)
class BatteryViewModel(app: Application) : AndroidViewModel(app) {
    private val db = Ev80Database.get(app)
    val spreadWarnMv = AppSettings(app).spreadWarnMv

    private val profileId: StateFlow<Long?> = db.profiles().all().map { it.firstOrNull()?.id }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val window = MutableStateFlow(TimeWindow.last(TimeWindow.QUICK[1], System.currentTimeMillis()))
    /** null: the latest snapshot in the window. */
    private val pickedMs = MutableStateFlow<Long?>(null)
    private val extra = MutableStateFlow<Set<Int>>(emptySet())

    val sessions: StateFlow<List<SessionEntity>> = db.sessions().all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // a window that ends "now" moves along
        viewModelScope.launch {
            while (true) {
                delay(10_000)
                val w = window.value
                if (w.followNow) window.value = TimeWindow.last(w.lengthMs, System.currentTimeMillis())
            }
        }
    }

    // a new snapshot arrives every 2 s while charging: re-read at most every 2 s, off the main thread
    private val rows = combine(profileId, window) { id, w -> id to w }.flatMapLatest { (id, w) ->
        if (id == null) flowOf(emptyList())
        else db.cellSnapshots().bucketTimes(id, w.fromMs, w.toMs, w.bucketMs).conflate()
            .transform { times -> emit(db.cellSnapshots().atTimes(id, times)); delay(REFRESH_MS) }
    }

    val ui: StateFlow<BatteryUi?> = combine(window, rows, pickedMs, extra) { w, r, picked, ex ->
        val snaps = r.map { it.tsMs to CellBlob.unpack(it.mv) }
        val at = picked ?: snaps.lastOrNull()?.first ?: w.toMs
        val row = r.lastOrNull { it.tsMs <= at } ?: r.firstOrNull()
        BatteryUi(w, row?.tsMs, row?.let { CellStats.of(CellBlob.unpack(it.mv)) }, row?.charging == true,
            CellSeries.build(snaps, at, ex), ex)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun now() = System.currentTimeMillis()
    fun setWindow(w: TimeWindow) { window.value = w; pickedMs.value = null }
    fun quick(lengthMs: Long) = setWindow(TimeWindow.last(lengthMs, now()))
    fun earlier() = setWindow(window.value.earlier())
    fun later() = setWindow(window.value.later(now()))
    fun zoomOut() = setWindow(window.value.zoomOut(now()))
    fun zoom(factor: Double, focusMs: Long) { window.value = window.value.zoom(factor, focusMs, now()) }
    fun pan(deltaMs: Long) { window.value = window.value.pan(deltaMs, now()) }
    fun session(s: SessionEntity) = setWindow(TimeWindow(s.startMs, s.endMs ?: now(), s.endMs == null))
    fun range(from: Long, to: Long) = setWindow(TimeWindow(from, minOf(to, now()), to >= now()))
    fun pickTime(ms: Long) { pickedMs.value = ms }
    fun toggleCell(idx: Int) { extra.value = if (idx in extra.value) extra.value - idx else extra.value + idx }

    /** From the end of the newest complete charge to now, or null if there was none. */
    fun sinceLastFull(): Boolean {
        val c = sessions.value.firstOrNull { it.outcome == "COMPLETE" } ?: return false
        setWindow(TimeWindow(c.endMs ?: c.startMs, now(), true)); return true
    }

    private companion object { const val REFRESH_MS = 2_000L }
}
