package com.crvdiag.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A saved scan report (PDF in the app's reports folder). */
@Entity(tableName = "scan_reports")
data class ScanReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAtEpochMs: Long,
    /** "Pre-repair scan", "Post-repair scan" or "Health check". */
    val type: String,
    val vin: String?,
    val fileName: String,
    /** One line, e.g. "MIL off · 0 stored · 1 pending · 4/8 self-tests". */
    val summary: String,
)

@Dao
interface ScanReportDao {
    @Insert
    suspend fun insert(report: ScanReportEntity): Long

    @Query("SELECT * FROM scan_reports ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<ScanReportEntity>>

    @Query("DELETE FROM scan_reports WHERE id = :id")
    suspend fun delete(id: Long)
}
