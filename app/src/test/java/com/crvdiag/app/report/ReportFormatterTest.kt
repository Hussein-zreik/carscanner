package com.crvdiag.app.report

import com.crvdiag.elm.ElmProtocol
import com.crvdiag.obd.DiagnosticsReport
import com.crvdiag.obd.EcuDiscovery
import com.crvdiag.obd.MisfireCount
import com.crvdiag.obd.Readiness
import com.crvdiag.obd.ScanData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class ReportFormatterTest {
    private val ecus = EcuDiscovery.fromPid00(listOf("7E8064100BE3FA813", "7E9064100983A8003"), ElmProtocol.CAN_11_500)

    private fun input(vin: String?, pending: List<String> = listOf("P145C")) = ReportInput(
        type = ReportType.PreRepair, notes = "Before EVAP purge valve replacement",
        createdAt = ZonedDateTime.of(2026, 11, 17, 15, 30, 0, 0, ZoneOffset.UTC), appVersion = "0.8.0",
        adapterId = "ELM327 v1.5", protocolLabel = "CAN 11-bit 500 kbps", voltage = "12.1V",
        odometerMiles = 153_704, odometerEstimated = true, ecus = ecus,
        data = ScanData(
            DiagnosticsReport(
                readiness = mapOf("7E8" to Readiness.decode(0x00, 0x07, 0xE5, 0xA5)),
                stored = mapOf("7E8" to emptyList()), pending = mapOf("7E8" to pending),
                freezeFrames = emptyMap(), problems = emptyList(),
            ),
            permanent = mapOf("7E8" to emptyList()), vin = vin, calibrationIds = emptyMap(), ecuNames = emptyMap(),
            misfires = listOf(MisfireCount(1, 0, 0), MisfireCount(2, 0, 0)),
        ),
        describeCode = { if (it == "P145C") "EVAP purge flow malfunction (per aftermarket sources)" else "?" },
    )

    private fun text(blocks: List<Block>) = blocks.joinToString("\n") { b ->
        when (b) {
            is Block.Title -> b.title + " " + b.subtitle
            is Block.Heading -> b.text
            is Block.Text -> b.text
            is Block.Fields -> b.rows.joinToString("\n") { "${it.label}: ${it.value}" }
            is Block.Table -> b.rows.joinToString("\n") { it.cells.joinToString(" | ") }
        }
    }

    @Test
    fun `unscanned systems are never reported as clean`() {
        val t = text(ReportFormatter.build(input("5J6RE4H50BL017022")))
        listOf("Airbags (SRS)", "ABS / stability control (VSA)", "Tire pressure (TPMS)").forEach {
            assertTrue(t.contains("$it | NOT SCANNED"), "missing NOT SCANNED for $it")
        }
        assertTrue(t.contains("Engine computer (7E8) | Scanned"))
    }

    @Test
    fun `VIN check and profile match`() {
        val good = text(ReportFormatter.build(input("5J6RE4H50BL017022")))
        assertTrue(good.contains("VIN check digit: Valid"))
        assertTrue(good.contains("Matches this car's VIN: Yes"))
        val other = text(ReportFormatter.build(input("1HGCM82633A004352")))
        assertTrue(other.contains("Matches this car's VIN: NO"))
        assertTrue(text(ReportFormatter.build(input(null))).contains("VIN reported by the car: Not reported"))
    }

    @Test
    fun `codes, readiness, misfires and summary`() {
        val built = input("5J6RE4H50BL017022")
        val t = text(ReportFormatter.build(built))
        assertTrue(t.contains("P145C | Pending | EVAP purge flow malfunction"))
        assertTrue(t.contains("Emission self-tests (readiness): 4 of 8 complete"))
        assertTrue(t.contains("1 | 0 | 0"))
        assertTrue(t.contains("Read-only scan"))
        assertEquals("MIL off · 0 stored · 1 pending · 0 permanent · 4/8 self-tests", ReportFormatter.summary(built))
        assertTrue(text(ReportFormatter.build(input("5J6RE4H50BL017022", pending = emptyList())))
            .contains("No stored, pending or permanent codes"))
    }
}
