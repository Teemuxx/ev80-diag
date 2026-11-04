package io.github.ev80diag.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ev80diag.pipeline.RecorderState
import io.github.ev80diag.pipeline.ValueHistory
import io.github.ev80diag.service.Recorder
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Keeps the last 10 minutes of every value while the Details screen lives, for the per-signal chart. */
class DetailsViewModel : ViewModel() {
    val state: StateFlow<RecorderState> = Recorder.state
    val history = ValueHistory()

    init {
        viewModelScope.launch {
            Recorder.state.collect { st -> if (st.decoded.isNotEmpty()) history.add(st.van.lastFrameMs.takeIf { it > 0 } ?: System.currentTimeMillis(), st.decoded) }
        }
    }
}
