package io.github.ev80diag.profile

import android.content.Context
import io.github.ev80diag.DebugLog
import io.github.ev80diag.calc.SocTracker
import io.github.ev80diag.data.store.Ev80Database
import io.github.ev80diag.data.store.ProfileEntity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Adding, editing and deleting vans (the Edit van screen). */
object VanAdmin {
    /** Inserts ([ProfileEntity.id] 0) or updates the van and returns its id. */
    suspend fun save(ctx: Context, van: ProfileEntity, capacityAh: Float?): Long {
        val db = Ev80Database.get(ctx)
        val id = if (van.id == 0L) db.profiles().insert(van.copy(createdMs = System.currentTimeMillis()))
        else { db.profiles().byId(van.id)?.let { old -> db.profiles().update(old.copy(name = van.name, vin = van.vin, notes = van.notes)) }; van.id }
        if (capacityAh != null && capacityAh > 0f) SocTracker.setCapacity(id, capacityAh)
        return id
    }

    /** Sessions and the bytes of their raw logs + marks files. */
    suspend fun dataSize(ctx: Context, id: Long): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val sessions = Ev80Database.get(ctx).sessions().listForProfile(id)
        sessions.size to sessions.sumOf { s -> listOfNotNull(s.rawLogPath, s.marksPath).sumOf { File(it).length() } }
    }

    /** Removes the van with its sessions, samples, cell history, marks, files and SOC state. Never the last van. */
    suspend fun delete(ctx: Context, id: Long): Boolean = withContext(Dispatchers.IO) {
        val db = Ev80Database.get(ctx)
        val others = db.profiles().list().filter { it.id != id }
        if (others.isEmpty()) return@withContext false
        if (ActiveProfile.flow(ctx).value?.id == id) ActiveProfile.set(ctx, others.first().id)
        val sessions = db.sessions().listForProfile(id)
        sessions.forEach { s -> listOfNotNull(s.rawLogPath, s.marksPath).forEach { File(it).delete() } }
        db.samples().deleteForProfile(id)       // before the sessions: it finds the samples through them
        db.sessions().deleteForProfile(id)
        db.cellSnapshots().deleteForProfile(id)
        db.marks().deleteForProfile(id)
        db.profiles().delete(id)
        SocTracker.delete(id)
        DebugLog.log("van $id deleted (${sessions.size} sessions)")
        true
    }
}
