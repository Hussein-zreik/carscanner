package com.crvdiag.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ScanEntity::class], version = 1, exportSchema = true)
abstract class CrvDatabase : RoomDatabase() {
    abstract fun scanDao(): ScanDao

    companion object {
        fun create(context: Context): CrvDatabase =
            Room.databaseBuilder(context.applicationContext, CrvDatabase::class.java, "crv-diag.db")
                .build()
    }
}
