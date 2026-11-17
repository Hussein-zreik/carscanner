package com.crvdiag.obd

import com.crvdiag.elm.Elm327
import com.crvdiag.elm.ElmProtocol
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiagnosticsReaderTest {
    // SYNTHETIC replies modelled on this car's state on 2026-11-17 (Car Scanner): pending P145C,
    // MIL off, four monitors incomplete. TODO(capture): replace with a real recording.
    private val car = mapOf(
        "0101" to "7E8064101 0007E5A5\r7E9064101 00000000\r\r",
        "03" to "7E8024300\r7E9024300\r\r",
        "07" to "7E804470114 5C\r7E9024700\r\r",
        "020200" to "7E8054202000000\r\r",
        "ATSH7DF" to "OK\r\r",
    )

    private suspend fun kotlinx.coroutines.test.TestScope.read(replies: Map<String, String>): Pair<FakeCar, DiagnosticsReport> {
        val fake = FakeCar(replies)
        val elm = Elm327(fake, ConsolePolicy).also { it.start(backgroundScope) }
        return fake to DiagnosticsReader(elm, ElmProtocol.CAN_11_500, headersOn = true).read()
    }

    @Test
    fun `reads readiness, stored and pending codes per ECU`() = runTest {
        val (fake, report) = read(car)
        assertEquals(listOf("ATSH7DF", "0101", "03", "07", "020200"), fake.sent)
        assertEquals(4, report.readiness.getValue("7E8").incomplete.size)
        assertEquals(mapOf("7E8" to emptyList<String>(), "7E9" to emptyList()), report.stored)
        assertEquals(listOf("P145C"), report.pending.getValue("7E8"))
        assertTrue(report.freezeFrames.isEmpty())
        assertTrue(report.problems.isEmpty())
    }

    @Test
    fun `stored code with freeze frame`() = runTest {
        val withFrame = car + mapOf(
            "03" to "7E804430101 71\r\r",
            "020200" to "7E8054202000171\r\r",
            "020C00" to "7E80542 0C000D48\r\r",
            "020500" to "7E804420500 7D\r\r",
        )
        val (_, report) = read(withFrame)
        val frame = report.freezeFrames.getValue("7E8")
        assertEquals("P0171", frame.dtc)
        val values = frame.values.associate { it.pid to it.value }
        assertEquals(850.0, values[0x0C])
        assertEquals(85.0, values[0x05])
    }

    @Test
    fun `adapter errors are reported, not thrown`() = runTest {
        val (_, report) = read(car + ("03" to "CAN ERROR\r\r"))
        assertEquals(listOf("Stored codes: CAN ERROR"), report.problems)
    }
}
