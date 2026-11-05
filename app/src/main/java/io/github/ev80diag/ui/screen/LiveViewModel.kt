package io.github.ev80diag.ui.screen

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ev80diag.calc.SocTracker
import io.github.ev80diag.profile.ActiveProfile
import io.github.ev80diag.data.store.Ev80Database
import io.github.ev80diag.service.Recorder
import io.github.ev80diag.settings.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class LiveViewModel(app: Application) : AndroidViewModel(app) {
    private val db = Ev80Database.get(app)
    private val settings = AppSettings(app)

    val profileId: StateFlow<Long?> = ActiveProfile.flow(app).map { it?.id }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Average over all stored drives, once there are at least 5 km of them (the range's fallback). */
    private val allTimeKwhPer100: Flow<Double?> = profileId.flatMapLatest { id ->
        if (id == null) flowOf(null)
        else db.sessions().driveTotals(id, 0, Long.MAX_VALUE).map { t -> if (t.km >= 5.0) t.kwh / t.km * 100.0 else null }
    }

    val ui: StateFlow<LiveUi?> = combine(Recorder.state, allTimeKwhPer100, SocTracker.snapshot) { st, allTime, soc ->
        LiveUiBuilder.build(st, soc.capacityAh.toDouble(), allTime, LiveThresholds(settings.spreadWarnMv, settings.lowCellMv))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
