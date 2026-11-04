package io.github.ev80diag.ui.screen

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ev80diag.data.store.Ev80Database
import io.github.ev80diag.data.store.SessionEntity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExportDebugViewModel(app: Application) : AndroidViewModel(app) {
    private val db = Ev80Database.get(app)
    val profileName: StateFlow<String?> = db.profiles().all().map { it.firstOrNull()?.name }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val sessions: StateFlow<List<SessionEntity>> = db.sessions().all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _storage = MutableStateFlow(0L)
    val storageBytes: StateFlow<Long> = _storage

    init {
        viewModelScope.launch {
            _storage.value = withContext(Dispatchers.IO) {
                File(app.filesDir, "sessions").walkTopDown().filter { it.isFile }.sumOf { it.length() } +
                    app.getDatabasePath("ev80diag.db").let { if (it.exists()) it.length() else 0L }
            }
        }
    }
}
