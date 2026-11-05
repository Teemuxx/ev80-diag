package io.github.ev80diag.profile

import android.content.Context
import io.github.ev80diag.R
import io.github.ev80diag.calc.SocTracker
import io.github.ev80diag.data.store.Ev80Database
import io.github.ev80diag.data.store.ProfileEntity
import io.github.ev80diag.data.store.RoomSessionRepository
import io.github.ev80diag.data.store.SessionEntity
import io.github.ev80diag.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The van all screens show and the recorder starts on (stored in [AppSettings.activeProfileId]). */
object ActiveProfile {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val storedId = MutableStateFlow(-1L)
    @Volatile private var active: StateFlow<ProfileEntity?>? = null

    /** The stored van if it still exists, else the first one. */
    fun resolve(profiles: List<ProfileEntity>, storedId: Long): ProfileEntity? =
        profiles.firstOrNull { it.id == storedId } ?: profiles.firstOrNull()

    /** Once from Application.onCreate: creates the first van if needed and keeps the SOC tracker on the active van. */
    @Synchronized
    fun init(ctx: Context) {
        if (active != null) return
        val app = ctx.applicationContext
        val db = Ev80Database.get(app)
        storedId.value = AppSettings(app).activeProfileId
        val flow = combine(db.profiles().all(), storedId) { list, id -> resolve(list, id) }
            .stateIn(scope, SharingStarted.Eagerly, null)
        active = flow
        scope.launch {
            val first = RoomSessionRepository(db).ensureDefaultProfile(app.getString(R.string.default_van_name))
            SocTracker.migrateLegacy(first.id)       // 0.3 kept one SOC state: it is the first van's
            flow.collect { p -> p?.let { SocTracker.activate(it.id) } }
        }
    }

    fun flow(ctx: Context): StateFlow<ProfileEntity?> { init(ctx); return active!! }

    /** The active van's sessions, newest first. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun sessions(ctx: Context): Flow<List<SessionEntity>> = flow(ctx).flatMapLatest { p ->
        if (p == null) flowOf(emptyList()) else Ev80Database.get(ctx).sessions().allForProfile(p.id)
    }

    /** Makes [id] the active van (only while nothing records; while connected use Recorder.chooseProfile). */
    fun set(ctx: Context, id: Long) {
        AppSettings(ctx).activeProfileId = id
        storedId.value = id
    }

    /** The van to record into now, read straight from the database (the service may start before the flow fills). */
    suspend fun current(ctx: Context): ProfileEntity {
        val app = ctx.applicationContext
        val db = Ev80Database.get(app)
        val first = RoomSessionRepository(db).ensureDefaultProfile(app.getString(R.string.default_van_name))
        return resolve(db.profiles().list(), AppSettings(app).activeProfileId) ?: first
    }
}
