package com.crvdiag.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ScanEntity::class, DriveLogEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)], // v2 adds drive_logs
)
abstract class CrvDatabase : RoomDatabase() {
    abstract fun scanDao(): ScanDao
    abstract fun driveLogDao(): DriveLogDao

    companion object {
        @Volatile private var instance: CrvDatabase? = null

        /** One database per process. */
        fun get(context: Context): CrvDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, CrvDatabase::class.java, "crv-diag.db")
                .build().also { instance = it }
        }
    }
}
