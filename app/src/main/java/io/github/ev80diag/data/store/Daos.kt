package io.github.ev80diag.data.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Insert suspend fun insert(p: ProfileEntity): Long
    @Update suspend fun update(p: ProfileEntity)
    @Query("SELECT * FROM profile ORDER BY id LIMIT 1") suspend fun first(): ProfileEntity?
    @Query("SELECT * FROM profile ORDER BY id") fun all(): Flow<List<ProfileEntity>>
}

@Dao
interface SessionDao {
    @Insert suspend fun insert(s: SessionEntity): Long
    @Update suspend fun update(s: SessionEntity)
    @Query("SELECT * FROM session WHERE id = :id") suspend fun byId(id: Long): SessionEntity?
    @Query("SELECT * FROM session ORDER BY startMs DESC") fun all(): Flow<List<SessionEntity>>
    @Query("SELECT * FROM session WHERE endMs IS NULL") suspend fun open(): List<SessionEntity>
    @Query("SELECT * FROM session WHERE profileId = :profileId ORDER BY startMs") suspend fun listForProfile(profileId: Long): List<SessionEntity>
    // Single-column updates: the pipeline, the Charging screen (charger name) and cleanup write the same row.
    @Query("UPDATE session SET type = :type WHERE id = :id") suspend fun setType(id: Long, type: String)
    @Query("UPDATE session SET rawLogPath = :raw, marksPath = :marks WHERE id = :id") suspend fun setRawPaths(id: Long, raw: String?, marks: String?)
    @Query("UPDATE session SET chargerName = :name WHERE id = :id") suspend fun setChargerName(id: Long, name: String?)
    @Query("UPDATE session SET endMs = :endMs, outcome = :outcome, chargedKwh = :kwh, framesTotal = :frames, droppedFrames = :dropped WHERE id = :id")
    suspend fun end(id: Long, endMs: Long, outcome: String, kwh: Double?, frames: Long, dropped: Long)
    @Query("SELECT * FROM session WHERE type = 'DRIVE' AND rawLogPath IS NOT NULL AND startMs < :before")
    suspend fun driveRawBefore(before: Long): List<SessionEntity>
    @Query("DELETE FROM session WHERE id = :id") suspend fun delete(id: Long)
    @Query("UPDATE session SET distanceKm = :km, driveKwh = :kwh WHERE id = :id") suspend fun setDriveTotals(id: Long, km: Double, kwh: Double)
    @Query("SELECT COALESCE(SUM(distanceKm), 0) AS km, COALESCE(SUM(driveKwh), 0) AS kwh FROM session " +
        "WHERE type = 'DRIVE' AND endMs IS NOT NULL AND profileId = :profileId AND startMs >= :from AND startMs < :to")
    /** Ended drives only; the open one is in RecorderState.drive (its stored totals lag up to a minute). */
    fun driveTotals(profileId: Long, from: Long, to: Long): Flow<DriveTotals>
    @Query("SELECT * FROM session WHERE profileId = :profileId AND type IN ('CHARGE_AC', 'CHARGE_DC') ORDER BY startMs DESC LIMIT 1")
    fun lastCharge(profileId: Long): Flow<SessionEntity?>
}

@Dao
interface SampleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(s: SampleEntity)
    @Query("SELECT * FROM sample WHERE sessionId = :id ORDER BY tsMs") fun forSession(id: Long): Flow<List<SampleEntity>>
    @Query("SELECT * FROM sample WHERE sessionId = :id ORDER BY tsMs") suspend fun listForSession(id: Long): List<SampleEntity>
    @Query("DELETE FROM sample WHERE sessionId = :id") suspend fun deleteForSession(id: Long)
    @Query("SELECT MAX(tsMs) FROM sample WHERE sessionId = :id") suspend fun lastTs(id: Long): Long?
}

@Dao
interface CellSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(s: CellSnapshotEntity)
    /** One page of the whole history, oldest first, for the profile export (blocking: call it off the main thread). */
    @Query("SELECT * FROM cell_snapshot WHERE profileId = :profileId AND tsMs > :afterTs ORDER BY tsMs LIMIT :limit")
    fun page(profileId: Long, afterTs: Long, limit: Int): List<CellSnapshotEntity>
    /**
     * Step 1: the first snapshot time per [bucketMs], read from the primary-key index only (no blobs), so even
     * 30 days stay cheap. Step 2, [atTimes], loads the blobs for those ~400 times.
     */
    @Query("SELECT MIN(tsMs) FROM cell_snapshot WHERE profileId = :profileId AND tsMs BETWEEN :from AND :to " +
        "GROUP BY tsMs / :bucketMs ORDER BY 1")
    fun bucketTimes(profileId: Long, from: Long, to: Long, bucketMs: Long): Flow<List<Long>>
    @Query("SELECT * FROM cell_snapshot WHERE profileId = :profileId AND tsMs IN (:times) ORDER BY tsMs")
    suspend fun atTimes(profileId: Long, times: List<Long>): List<CellSnapshotEntity>
    @Query("SELECT * FROM cell_snapshot WHERE profileId = :profileId AND tsMs <= :ts ORDER BY tsMs DESC LIMIT 1")
    suspend fun latestAtOrBefore(profileId: Long, ts: Long): CellSnapshotEntity?
}
