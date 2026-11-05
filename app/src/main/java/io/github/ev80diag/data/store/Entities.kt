package io.github.ev80diag.data.store

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdMs: Long,
    @ColumnInfo(defaultValue = "NULL") val vin: String? = null,
    @ColumnInfo(defaultValue = "NULL") val notes: String? = null,
    /** Recognition fingerprint: the last odometer and lifetime charge counter (kWh) seen on this van. */
    @ColumnInfo(defaultValue = "NULL") val lastOdoKm: Double? = null,
    @ColumnInfo(defaultValue = "NULL") val lastChargeKwh: Double? = null,
    @ColumnInfo(defaultValue = "NULL") val lastSeenMs: Long? = null,
)

/** A note the owner typed at a moment ("range dropped"); [sessionId] is the session open at that time, if any. */
@Entity(tableName = "mark", indices = [Index("profileId"), Index("sessionId")])
data class MarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val sessionId: Long?,
    val tsMs: Long,
    val text: String,
)

/** [type]/[outcome] hold the names of session.SessionType / SessionOutcome. */
@Entity(tableName = "session", indices = [Index("startMs")])
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val type: String,
    val outcome: String? = null,
    val startMs: Long,
    val endMs: Long? = null,
    val chargerName: String? = null,
    val chargedKwh: Double? = null,
    val rawLogPath: String? = null,
    val marksPath: String? = null,
    val adapterKind: String,
    val adapterName: String? = null,
    val droppedFrames: Long = 0,
    val framesTotal: Long = 0,
    /** DRIVE only: distance and net battery energy (discharge minus regen). */
    val distanceKm: Double? = null,
    val driveKwh: Double? = null,
)

/** One decoded sample per second while a session is open. */
@Entity(tableName = "sample", primaryKeys = ["sessionId", "tsMs"], indices = [Index("sessionId")])
data class SampleEntity(
    val sessionId: Long,
    val tsMs: Long,
    val packV: Double? = null,
    val packA: Double? = null,
    val socBms: Double? = null,
    val socApp: Double? = null,
    val insulationKOhm: Double? = null,
    val tempAvg: Double? = null,
    val tempMin: Double? = null,
    val tempMax: Double? = null,
    val cellMinMv: Double? = null,
    val cellMaxMv: Double? = null,
    val energyKwh: Double? = null,
)

/** All 104 cell voltages at one moment ([mv] = [CellBlob]); 1 per 2 s while charging, else 1 per 10 s. */
@Entity(tableName = "cell_snapshot", primaryKeys = ["profileId", "tsMs"])
data class CellSnapshotEntity(
    val profileId: Long,
    val tsMs: Long,
    val mv: ByteArray,
    val charging: Boolean,
)

/** 104 cell voltages as u16 little-endian (208 bytes). */
object CellBlob {
    fun pack(mv: IntArray): ByteArray {
        val out = ByteArray(mv.size * 2)
        for (i in mv.indices) { out[2 * i] = mv[i].toByte(); out[2 * i + 1] = (mv[i] shr 8).toByte() }
        return out
    }

    fun unpack(b: ByteArray): IntArray = IntArray(b.size / 2) { (b[2 * it].toInt() and 0xFF) or ((b[2 * it + 1].toInt() and 0xFF) shl 8) }
}

data class DriveTotals(val km: Double, val kwh: Double)
