package com.crvdiag.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** One recorded drive: a CSV of decoded values plus the raw adapter log (testdata/ format). */
@Entity(tableName = "drive_logs")
data class DriveLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
    /** File names inside the app's logs folder. */
    val csvFile: String,
    val rawFile: String,
    val rows: Int = 0,
    /** Drive watch: misfires counted during the drive, e.g. "cyl 1: 0 · cyl 2: 3 · …" (v5). */
    @ColumnInfo(defaultValue = "NULL") val misfireSummary: String? = null,
)

@Dao
interface DriveLogDao {
    @Insert
    suspend fun insert(log: DriveLogEntity): Long

    @Query("UPDATE drive_logs SET endedAtEpochMs = :endedAt, rows = :rows WHERE id = :id")
    suspend fun finish(id: Long, endedAt: Long, rows: Int)

    @Query("UPDATE drive_logs SET misfireSummary = :summary WHERE id = :id")
    suspend fun setMisfireSummary(id: Long, summary: String)

    @Query("SELECT * FROM drive_logs ORDER BY startedAtEpochMs DESC")
    fun observeAll(): Flow<List<DriveLogEntity>>

    @Query("DELETE FROM drive_logs WHERE id = :id")
    suspend fun delete(id: Long)
}
