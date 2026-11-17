package com.crvdiag.obd

import com.crvdiag.elm.Elm327
import com.crvdiag.elm.ElmProtocol
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScanReportTest {
    private val can = ElmProtocol.CAN_11_500
    private val vinHex = "354A36524534483530424C303137303232" // 5J6RE4H50BL017022 (this car)

    @Test
    fun `VIN check digit of this car is valid`() {
        assertTrue(VehicleInfo.vinCheckDigitValid("5J6RE4H50BL017022"))
        assertFalse(VehicleInfo.vinCheckDigitValid("5J6RE4H51BL017022"))
        assertFalse(VehicleInfo.vinCheckDigitValid("5J6RE4H50BL01702"))
    }

    @Test
    fun `VIN from a multi-frame 0902 reply, headers on and off`() {
        // 49 02 01 + 17 bytes = 20 bytes → first frame + 2 consecutive frames (SYNTHETIC framing).
        val headered = listOf("7E8 10 14 49 02 01 35 4A 36", "7E8 21 52 45 34 48 35 30 42", "7E8 22 4C 30 31 37 30 32 32")
        val m1 = ObdMessages.collect(headered, can, headersOn = true).single()
        assertEquals("5J6RE4H50BL017022", VehicleInfo.vin(m1))
        val headerless = listOf("014", "0: 49 02 01 35 4A 36", "1: 52 45 34 48 35 30 42", "2: 4C 30 31 37 30 32 32")
        assertEquals("5J6RE4H50BL017022", VehicleInfo.vin(ObdMessages.collect(headerless, can, headersOn = false).single()))
    }

    @Test
    fun `mode 06 misfire result decodes`() {
        // MID A2 (cyl 1): TID 0B EWMA = 0 and TID 0C this cycle = 0, limits 0..65535 (as Car Scanner showed).
        val msg = EcuMessage("7E8", ("46 A2 0B 24 00 00 00 00 FF FF A2 0C 24 00 00 00 00 FF FF").split(" ").map { it.toInt(16) })
        val tests = Mode06.parse(msg)!!
        assertEquals(2, tests.size)
        assertEquals(MonitorTest(0xA2, 0x0B, 0x24, 0, 0, 0xFFFF), tests[0])
        assertTrue(tests.all { it.passed })
    }

    @Test
    fun `collector reads everything read-only and groups per ECU`() = runTest {
        // SYNTHETIC replies in the clone's format modelled on this car (TODO(capture): replace).
        val car = FakeCar(mapOf(
            "ATSH7DF" to "OK\r\r",
            "0101" to "7E8064101 0007E5A5\r\r",
            "03" to "7E8024300\r\r",
            "07" to "7E804470114 5C\r\r",
            "020200" to "7E8054202000000\r\r",
            "0A" to "7E8024A00\r\r",
            "0902" to "7E8101449020135 4A36\r7E8215245344835 3042\r7E8224C3031373032 32\r\r",
            "0904" to "NO DATA\r\r",
            "090A" to "NO DATA\r\r",
            "06A2" to "7E81013 46A20B24000000 \r7E821 00FFFFA20C2400 00\r7E82200 00FFFF0000000000\r\r",
            "06A3" to "NO DATA\r\r", "06A4" to "NO DATA\r\r", "06A5" to "NO DATA\r\r",
        ))
        val elm = Elm327(car, ConsolePolicy).also { it.start(backgroundScope) }
        val data = ScanReportCollector(elm, can, headersOn = true).collect()

        assertTrue(car.sent.none { it == "04" }, "a report must never clear codes")
        assertEquals("5J6RE4H50BL017022", data.vin)
        assertEquals(listOf("P145C"), data.diagnostics.pending["7E8"])
        assertEquals(mapOf("7E8" to emptyList<String>()), data.permanent)
        assertEquals(listOf(MisfireCount(1, 0, 0)), data.misfires)
        assertTrue(data.diagnostics.problems.isEmpty())
    }
}
