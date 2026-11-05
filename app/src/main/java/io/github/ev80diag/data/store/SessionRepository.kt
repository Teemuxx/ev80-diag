package io.github.ev80diag.data.store

import io.github.ev80diag.profile.VanPrint
import io.github.ev80diag.session.SessionOutcome
import io.github.ev80diag.session.SessionType

/** What the pipeline needs from storage; the Room version is [RoomSessionRepository], tests use a fake. */
interface SessionRepository {
    suspend fun startSession(profileId: Long, type: SessionType, startMs: Long, adapterKind: String, adapterName: String?): Long
    suspend fun updateType(id: Long, type: SessionType)
    suspend fun setRawPaths(id: Long, rawLogPath: String, marksPath: String)
    suspend fun endSession(id: Long, endMs: Long, outcome: SessionOutcome, chargedKwh: Double?, framesTotal: Long, droppedFrames: Long)
    suspend fun addSample(sample: SampleEntity)
    suspend fun setDriveTotals(id: Long, km: Double, kwh: Double)
    suspend fun addCellSnapshot(s: CellSnapshotEntity)
    suspend fun vans(): List<VanPrint>
    /** Recognition decided late: the open session, and cell snapshots and marks since [sinceMs], belong to [to]. */
    suspend fun moveConnectionData(sessionId: Long?, from: Long, to: Long, sinceMs: Long)
    suspend fun setLastSeen(profileId: Long, odoKm: Double?, chargeKwh: Double?, ms: Long)
    suspend fun addMark(m: MarkEntity)
}

class RoomSessionRepository(private val db: Ev80Database) : SessionRepository {
    override suspend fun startSession(profileId: Long, type: SessionType, startMs: Long, adapterKind: String, adapterName: String?) =
        db.sessions().insert(SessionEntity(profileId = profileId, type = type.name, startMs = startMs,
            adapterKind = adapterKind, adapterName = adapterName))

    override suspend fun updateType(id: Long, type: SessionType) = db.sessions().setType(id, type.name)

    override suspend fun setRawPaths(id: Long, rawLogPath: String, marksPath: String) =
        db.sessions().setRawPaths(id, rawLogPath, marksPath)

    override suspend fun endSession(id: Long, endMs: Long, outcome: SessionOutcome, chargedKwh: Double?, framesTotal: Long, droppedFrames: Long) =
        db.sessions().end(id, endMs, outcome.name, chargedKwh, framesTotal, droppedFrames)

    /**
     * Sessions still open when no pipeline runs were cut off by a kill or crash: they end at their last sample
     * as INTERRUPTED. Their raw log (flushed members) stays readable and shareable.
     */
    suspend fun closeOrphans(): Int {
        val open = db.sessions().open()
        for (s in open) db.sessions().end(s.id, db.samples().lastTs(s.id) ?: s.startMs, SessionOutcome.INTERRUPTED.name,
            s.chargedKwh, s.framesTotal, s.droppedFrames)
        return open.size
    }

    override suspend fun addSample(sample: SampleEntity) = db.samples().insert(sample)

    override suspend fun setDriveTotals(id: Long, km: Double, kwh: Double) = db.sessions().setDriveTotals(id, km, kwh)

    override suspend fun addCellSnapshot(s: CellSnapshotEntity) = db.cellSnapshots().insert(s)

    override suspend fun vans() = db.profiles().list().map { VanPrint(it.id, it.lastOdoKm, it.lastChargeKwh) }

    override suspend fun moveConnectionData(sessionId: Long?, from: Long, to: Long, sinceMs: Long) {
        sessionId?.let { db.sessions().moveToProfile(it, to) }
        db.cellSnapshots().moveToProfile(from, to, sinceMs)
        db.marks().moveToProfile(from, to, sinceMs)
    }

    override suspend fun setLastSeen(profileId: Long, odoKm: Double?, chargeKwh: Double?, ms: Long) =
        db.profiles().setLastSeen(profileId, odoKm, chargeKwh, ms)

    override suspend fun addMark(m: MarkEntity) { db.marks().insert(m) }

    /** The first van, created on first start. */
    suspend fun ensureDefaultProfile(name: String): ProfileEntity =
        db.profiles().first() ?: ProfileEntity(name = name, createdMs = System.currentTimeMillis())
            .let { it.copy(id = db.profiles().insert(it)) }
}
