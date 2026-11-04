package io.github.ev80diag.ui.screen

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ev80diag.calc.SocTracker
import io.github.ev80diag.data.store.DriveTotals
import io.github.ev80diag.data.store.Ev80Database
import io.github.ev80diag.service.Recorder
import io.github.ev80diag.settings.AppSettings
import io.github.ev80diag.ui.tiles.TileContext
import io.github.ev80diag.ui.tiles.TileKind
import io.github.ev80diag.ui.tiles.TileLayout
import io.github.ev80diag.ui.tiles.TileLayoutStore
import io.github.ev80diag.ui.tiles.TileSpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class TilesViewModel(app: Application) : AndroidViewModel(app) {
    private val db = Ev80Database.get(app)
    private val settings = AppSettings(app)
    private val store = TileLayoutStore(app.getSharedPreferences("ev80_tiles", Context.MODE_PRIVATE))
    private val none = DriveTotals(0.0, 0.0)

    private val profileId: StateFlow<Long?> = db.profiles().all().map { it.firstOrNull()?.id }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _layout = MutableStateFlow(TileLayout.DEFAULT)
    val layout: StateFlow<List<TileSpec>> = _layout

    init {
        viewModelScope.launch { _layout.value = store.load(profileId.filterNotNull().first()) }
    }

    fun set(slot: Int, spec: TileSpec) {
        val id = profileId.value ?: return
        _layout.value = _layout.value.toMutableList().also { it[slot] = spec }
        store.save(id, _layout.value)
    }

    private fun totals(from: Long, to: Long): Flow<DriveTotals> =
        profileId.flatMapLatest { id -> if (id == null) flowOf(none) else db.sessions().driveTotals(id, from, to) }

    private val allTime = totals(0, Long.MAX_VALUE)

    /** Drives since the end of the newest charge (all drives when there is none). */
    private val sinceCharge: Flow<DriveTotals> = profileId.flatMapLatest { id ->
        if (id == null) flowOf(none)
        else db.sessions().lastCharge(id).flatMapLatest { c -> db.sessions().driveTotals(id, c?.endMs ?: c?.startMs ?: 0, Long.MAX_VALUE) }
    }

    private val periods: Flow<Map<Int, DriveTotals>> = _layout.flatMapLatest { l ->
        val slots = l.withIndex().filter { it.value.kind == TileKind.CONS_PERIOD }
        if (slots.isEmpty()) flowOf(emptyMap())
        else combine(slots.map { (i, t) -> totals(t.fromMs ?: 0, t.toMs ?: 0).map { i to it } }) { it.toMap() }
    }

    val context: StateFlow<TileContext?> = combine(Recorder.state, allTime, sinceCharge, periods, SocTracker.snapshot) { st, all, since, per, soc ->
        val allKwh = if (all.km >= 5.0) all.kwh / all.km * 100.0 else null
        val live = LiveUiBuilder.build(st, soc.capacityAh.toDouble(), allKwh, LiveThresholds(settings.spreadWarnMv, settings.lowCellMv))
        TileContext(live, st, since, all, per)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
