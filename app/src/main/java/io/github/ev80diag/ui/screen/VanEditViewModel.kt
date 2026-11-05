package io.github.ev80diag.ui.screen

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ev80diag.calc.SocTracker
import io.github.ev80diag.data.store.Ev80Database
import io.github.ev80diag.data.store.ProfileEntity
import io.github.ev80diag.export.SessionExporter
import io.github.ev80diag.profile.VanAdmin
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the Edit van screen shows for one van; [van] id 0 = a new van. */
data class VanForm(val van: ProfileEntity, val capacityAh: Float, val sessions: Int = 0, val bytes: Long = 0)

class VanEditViewModel(app: Application) : AndroidViewModel(app) {
    private val db = Ev80Database.get(app)
    private val _form = MutableStateFlow<VanForm?>(null)
    val form: StateFlow<VanForm?> = _form
    val vanCount: StateFlow<Int> = db.profiles().all().map { it.size }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    fun load(id: Long) {
        if (_form.value?.van?.id == id && id != 0L) return
        viewModelScope.launch {
            val van = db.profiles().byId(id)
            _form.value = if (van == null) VanForm(ProfileEntity(name = "", createdMs = 0), SocTracker.capacityOf(-1))
            else VanAdmin.dataSize(getApplication(), id).let { (n, bytes) -> VanForm(van, SocTracker.capacityOf(id), n, bytes) }
        }
    }

    fun save(name: String, vin: String, notes: String, capacityAh: Float?, done: () -> Unit) {
        val f = _form.value ?: return
        viewModelScope.launch {
            VanAdmin.save(getApplication(), f.van.copy(name = name.trim(), vin = vin.trim().ifEmpty { null }, notes = notes.trim().ifEmpty { null }), capacityAh)
            done()
        }
    }

    fun delete(done: () -> Unit) {
        val id = _form.value?.van?.id ?: return
        viewModelScope.launch { if (VanAdmin.delete(getApplication(), id)) done() }
    }

    suspend fun exportAll(): File? = _form.value?.van?.id?.let { SessionExporter.buildAll(getApplication(), it) }
}
