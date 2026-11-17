package com.crvdiag.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class MaintenanceLogicTest {
    private val today = LocalDate.of(2026, 11, 17)
    private val oil = MaintenanceDefaults.ITEMS.first { it.key == "oil" } // 5,000 mi / 6 months
    private val brakeFluid = MaintenanceDefaults.ITEMS.first { it.key == "brake_fluid" } // 36 months only

    private fun done(daysAgo: Long, miles: Int?) = ServiceRecordEntity(itemKey = "x", epochDay = today.minusDays(daysAgo).toEpochDay(), odometerMiles = miles)

    @Test
    fun `whichever comes first`() {
        // 3,000 mi and ~2 months ago: OK with 2,000 mi left.
        assertEquals(DueStatus.Ok::class, MaintenanceLogic.status(oil, done(60, 150_000), 153_000, today)::class)
        // Mileage fine, but 7 months old → overdue by time.
        val byTime = MaintenanceLogic.status(oil, done(213, 152_000), 153_000, today)
        assertTrue(byTime is DueStatus.Overdue && byTime.daysOver != null && byTime.milesOver == null)
        // Recent, but 5,400 mi driven → overdue by distance.
        val byMiles = MaintenanceLogic.status(oil, done(30, 147_600), 153_000, today)
        assertTrue(byMiles is DueStatus.Overdue && byMiles.milesOver == 400)
    }

    @Test
    fun `due soon window`() {
        assertTrue(MaintenanceLogic.status(oil, done(30, 148_400), 153_000, today) is DueStatus.Soon) // 400 mi left
        assertTrue(MaintenanceLogic.status(oil, done(165, 152_900), 153_000, today) is DueStatus.Soon) // ~17 days left
    }

    @Test
    fun `unknown when never recorded or not comparable`() {
        assertEquals(DueStatus.Unknown, MaintenanceLogic.status(oil, null, 153_000, today))
        val sparkPlugs = MaintenanceDefaults.ITEMS.first { it.key == "spark_plugs" } // miles only
        assertEquals(DueStatus.Unknown, MaintenanceLogic.status(sparkPlugs, done(400, null), 153_000, today))
        // Time-only item works without any mileage.
        assertTrue(MaintenanceLogic.status(brakeFluid, done(1200, null), null, today) is DueStatus.Overdue)
    }

    @Test
    fun `odometer estimate from the owner's readings`() {
        val readings = listOf(
            OdometerReadingEntity(epochDay = today.minusDays(100).toEpochDay(), miles = 150_000),
            OdometerReadingEntity(epochDay = today.minusDays(10).toEpochDay(), miles = 153_600), // 40 mi/day
        )
        val est = MaintenanceLogic.estimateMiles(readings, today)!!
        assertEquals(153_600 + 400, est.miles)
        assertTrue(est.estimated)
        assertEquals(40.0, est.milesPerDay!!, 0.001)
        // A single reading is shown as-is.
        val single = MaintenanceLogic.estimateMiles(readings.take(1), today)!!
        assertEquals(150_000, single.miles)
        assertNull(MaintenanceLogic.estimateMiles(emptyList(), today))
    }

    @Test
    fun `reading lower than an earlier one is rejected`() {
        val readings = listOf(OdometerReadingEntity(epochDay = today.minusDays(10).toEpochDay(), miles = 153_600))
        assertNotNull(MaintenanceLogic.checkReading(135_600, today, readings)) // typo
        assertNull(MaintenanceLogic.checkReading(153_900, today, readings))
        assertNull(MaintenanceLogic.checkReading(150_000, today.minusDays(30), readings)) // older date, lower is fine
    }
}
