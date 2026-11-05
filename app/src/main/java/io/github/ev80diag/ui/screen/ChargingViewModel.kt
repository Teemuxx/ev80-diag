package io.github.ev80diag.ui.screen

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ev80diag.data.store.Ev80Database
import io.github.ev80diag.data.store.SampleEntity
import io.github.ev80diag.data.store.SessionEntity
import io.github.ev80diag.profile.ActiveProfile
import io.github.ev80diag.pipeline.RecorderState
import io.github.ev80diag.service.Recorder
import io.github.ev80diag.session.SessionType
import io.github.ev80diag.settings.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChargingScreenState(
    val ui: ChargingUi,
    val session: SessionEntity,
    val samples: List<SampleEntity>,
    val active: Boolean,
    val waiting: Boolean,
    val insulationLowKOhm: Int,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChargingViewModel(app: Application) : AndroidViewModel(app) {
    private val db = Ev80Database.get(app)
    private val settings = AppSettings(app)

    private val activeId = Recorder.state.map { s -> s.session?.takeIf { it.type != SessionType.DRIVE }?.id }.distinctUntilChanged()

    /** The running charge/attempt, or else the one recorded last (by id: a replayed old log is older by start time). */
    private val shown = combine(activeId, ActiveProfile.sessions(app)) { id, sessions ->
        (id?.let { a -> sessions.firstOrNull { it.id == a } } ?: sessions.filter { it.type.startsWith("CHARGE") }.maxByOrNull { it.id }) to (id != null)
    }.distinctUntilChanged()

    val screen: StateFlow<ChargingScreenState?> = shown.flatMapLatest { (session, active) ->
        if (session == null) flowOf(null)
        else combine(Recorder.state, db.samples().forSession(session.id)) { st: RecorderState, samples ->
            val ui = ChargingUiBuilder.build(st, active, session, samples, ChargingThresholds(
                settings.insulationLowKOhm.toDouble(), settings.coldBatteryC.toDouble(), settings.spreadWarnMv))
            ChargingScreenState(ui, session, samples, active, waiting = active && session.type == "CHARGE_ATTEMPT",
                insulationLowKOhm = settings.insulationLowKOhm)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setChargerName(sessionId: Long, name: String) {
        viewModelScope.launch {
            db.sessions().setChargerName(sessionId, name.trim().ifEmpty { null })
        }
    }
}
