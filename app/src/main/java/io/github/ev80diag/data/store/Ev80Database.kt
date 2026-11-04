package io.github.ev80diag.data.store

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ProfileEntity::class, SessionEntity::class, SampleEntity::class, CellSnapshotEntity::class],
    version = 2, exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class Ev80Database : RoomDatabase() {
    abstract fun profiles(): ProfileDao
    abstract fun sessions(): SessionDao
    abstract fun samples(): SampleDao
    abstract fun cellSnapshots(): CellSnapshotDao

    companion object {
        @Volatile private var instance: Ev80Database? = null

        fun get(context: Context): Ev80Database = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, Ev80Database::class.java, "ev80diag.db")
                .build().also { instance = it }
        }
    }
}
