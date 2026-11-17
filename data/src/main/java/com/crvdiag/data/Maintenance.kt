package com.crvdiag.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.roundToInt

/** A maintenance item with the owner's interval ("whichever comes first"). Distances in miles. */
@Entity(tableName = "maintenance_items")
data class MaintenanceItemEntity(
    @PrimaryKey val key: String,
    val name: String,
    val groupName: String,
    val intervalMiles: Int?,
    val intervalMonths: Int?,
    val sortOrder: Int,
    val note: String? = null,
)

/** A service the owner did (or knows was done). Odometer may be unknown. */
@Entity(tableName = "service_records")
data class ServiceRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemKey: String,
    val epochDay: Long,
    val odometerMiles: Int?,
    val notes: String? = null,
)

/** An odometer reading typed in by the owner (the car's odometer is branded "not actual"). */
@Entity(tableName = "odometer_readings")
data class OdometerReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    val miles: Int,
)

@Dao
interface MaintenanceDao {
    /** Seeds defaults; existing rows (with the owner's edited intervals) are kept. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDefaults(items: List<MaintenanceItemEntity>)

    @Query("SELECT * FROM maintenance_items ORDER BY sortOrder")
    fun observeItems(): Flow<List<MaintenanceItemEntity>>

    @Query("UPDATE maintenance_items SET intervalMiles = :miles, intervalMonths = :months WHERE `key` = :key")
    suspend fun updateInterval(key: String, miles: Int?, months: Int?)

    @Insert
    suspend fun insertRecord(record: ServiceRecordEntity): Long

    @Query("DELETE FROM service_records WHERE id = :id")
    suspend fun deleteRecord(id: Long)

    @Query("SELECT * FROM service_records ORDER BY epochDay DESC, id DESC")
    fun observeRecords(): Flow<List<ServiceRecordEntity>>

    @Insert
    suspend fun insertReading(reading: OdometerReadingEntity): Long

    @Query("DELETE FROM odometer_readings WHERE id = :id")
    suspend fun deleteReading(id: Long)

    @Query("SELECT * FROM odometer_readings ORDER BY epochDay DESC, id DESC")
    fun observeReadings(): Flow<List<OdometerReadingEntity>>
}

/**
 * Starting-point intervals for a 2011 CR-V 2.4 4WD. NOT Honda's official schedule: the owner
 * confirms them against the owner's manual / Maintenance Minder and can edit every one.
 */
object MaintenanceDefaults {
    val ITEMS = listOf(
        MaintenanceItemEntity("oil", "Engine oil & filter", "Oil", 5_000, 6, 0, "0W-20. The dashboard Maintenance Minder may ask sooner."),
        MaintenanceItemEntity("atf", "Automatic transmission fluid (ATF)", "Drivetrain fluids", 30_000, 36, 1, "Honda ATF DW-1."),
        MaintenanceItemEntity("rear_diff", "Rear differential fluid (4WD)", "Drivetrain fluids", 30_000, 36, 2,
            "Honda Dual Pump Fluid II. Change sooner if the rear groans or shudders in tight, slow turns."),
        MaintenanceItemEntity("tire_rotation", "Tire rotation", "Tires & brakes", 7_500, 12, 3),
        MaintenanceItemEntity("brake_pads", "Brake pad inspection", "Tires & brakes", 15_000, 12, 4),
        MaintenanceItemEntity("brake_fluid", "Brake fluid", "Tires & brakes", null, 36, 5, "Honda DOT 3."),
        MaintenanceItemEntity("air_filter", "Engine air filter", "Filters & coolant", 30_000, 36, 6),
        MaintenanceItemEntity("cabin_filter", "Cabin (pollen) filter", "Filters & coolant", 15_000, 12, 7),
        MaintenanceItemEntity("coolant", "Engine coolant", "Filters & coolant", 60_000, 60, 8, "Honda Long Life Coolant Type 2."),
        MaintenanceItemEntity("spark_plugs", "Spark plugs", "Filters & coolant", 105_000, null, 9),
    )
}

/** Where an item stands. Remaining values are negative when overdue. */
sealed interface DueStatus {
    /** Never recorded, or the record can't be compared (e.g. mileage unknown and no time interval). */
    data object Unknown : DueStatus
    data class Ok(val milesLeft: Int?, val daysLeft: Long?) : DueStatus
    data class Soon(val milesLeft: Int?, val daysLeft: Long?) : DueStatus
    data class Overdue(val milesOver: Int?, val daysOver: Long?) : DueStatus
}

data class OdometerEstimate(val miles: Int, val estimated: Boolean, val milesPerDay: Double?)

object MaintenanceLogic {
    /** "Due soon" window: 30 days, or 10 % of the mileage interval (at least 500 mi). */
    const val SOON_DAYS = 30L

    fun status(
        item: MaintenanceItemEntity,
        last: ServiceRecordEntity?,
        currentMiles: Int?,
        today: LocalDate,
    ): DueStatus {
        if (last == null) return DueStatus.Unknown
        val milesLeft = if (item.intervalMiles != null && last.odometerMiles != null && currentMiles != null)
            last.odometerMiles + item.intervalMiles - currentMiles else null
        val daysLeft = item.intervalMonths?.let {
            ChronoUnit.DAYS.between(today, LocalDate.ofEpochDay(last.epochDay).plusMonths(it.toLong()))
        }
        if (milesLeft == null && daysLeft == null) return DueStatus.Unknown
        val soonMiles = item.intervalMiles?.let { max(500, it / 10) } ?: 0
        return when {
            (milesLeft != null && milesLeft < 0) || (daysLeft != null && daysLeft < 0) ->
                DueStatus.Overdue(milesLeft?.let { -it }?.takeIf { it > 0 }, daysLeft?.let { -it }?.takeIf { it > 0 })
            (milesLeft != null && milesLeft <= soonMiles) || (daysLeft != null && daysLeft <= SOON_DAYS) ->
                DueStatus.Soon(milesLeft, daysLeft)
            else -> DueStatus.Ok(milesLeft, daysLeft)
        }
    }

    /**
     * Today's mileage from the owner's readings: the latest reading, plus the average daily
     * mileage (from readings at least 14 days apart, within the last year) times days since.
     */
    fun estimateMiles(readings: List<OdometerReadingEntity>, today: LocalDate): OdometerEstimate? {
        val sorted = readings.sortedWith(compareBy({ it.epochDay }, { it.miles }))
        val latest = sorted.lastOrNull() ?: return null
        val window = sorted.filter { it.epochDay >= latest.epochDay - 365 }
        val first = window.first()
        val spanDays = latest.epochDay - first.epochDay
        val perDay = if (spanDays >= 14 && latest.miles > first.miles) (latest.miles - first.miles).toDouble() / spanDays else null
        val daysSince = today.toEpochDay() - latest.epochDay
        return if (perDay != null && daysSince > 0) {
            OdometerEstimate((latest.miles + perDay * daysSince).roundToInt(), estimated = true, milesPerDay = perDay)
        } else {
            OdometerEstimate(latest.miles, estimated = false, milesPerDay = perDay)
        }
    }

    /**
     * Rejects a reading lower than one recorded on an earlier-or-same day (typo protection;
     * this car's odometer history is already irregular). Returns a message or null if fine.
     */
    fun checkReading(miles: Int, day: LocalDate, readings: List<OdometerReadingEntity>): String? {
        if (miles <= 0 || miles > 1_000_000) return "Enter the odometer in miles."
        val earlierMax = readings.filter { it.epochDay <= day.toEpochDay() }.maxByOrNull { it.miles } ?: return null
        return if (miles < earlierMax.miles)
            "Lower than ${"%,d".format(earlierMax.miles)} mi recorded on ${LocalDate.ofEpochDay(earlierMax.epochDay)}. Check the number."
        else null
    }
}
