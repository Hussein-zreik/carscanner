package com.crvdiag.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ScanEntity::class, DriveLogEntity::class,
        MaintenanceItemEntity::class, ServiceRecordEntity::class, OdometerReadingEntity::class,
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2), // v2 adds drive_logs
        AutoMigration(from = 2, to = 3), // v3 adds maintenance tables
    ],
)
abstract class CrvDatabase : RoomDatabase() {
    abstract fun scanDao(): ScanDao
    abstract fun driveLogDao(): DriveLogDao
    abstract fun maintenanceDao(): MaintenanceDao

    companion object {
        @Volatile private var instance: CrvDatabase? = null

        /** One database per process. */
        fun get(context: Context): CrvDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, CrvDatabase::class.java, "crv-diag.db")
                .build().also { instance = it }
        }
    }
}
