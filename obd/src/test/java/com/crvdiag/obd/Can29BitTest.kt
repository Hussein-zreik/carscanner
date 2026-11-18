package com.crvdiag.obd

import com.crvdiag.elm.Elm327
import com.crvdiag.elm.ElmInitializer
import com.crvdiag.elm.ElmProtocol
import com.crvdiag.elm.InitOutcome
import com.crvdiag.transport.FakeTransport
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** This car is 29-bit CAN (recording 2026-11-18). The app must never send ATSH there. */
class Can29BitTest {
    private val can29 = ElmProtocol.CAN_29_500

    @Test
    fun `REAL recording - startup detects 29-bit CAN and both units`() = runTest {
        val car = FakeTransport.fromText(File("../testdata/2026-11-18_wifi_29bit-codes-failed.log").readText())
        val elm = Elm327(car, ConsolePolicy).also { it.start(backgroundScope) }
        val report = (ElmInitializer(elm).run() as InitOutcome.Success).report
        assertEquals(can29, report.detected?.protocol)
        val ecus = EcuDiscovery.fromPid00(report.pid00Lines, can29)
        assertEquals(setOf("18DAF111", "18DAF11D"), ecus.map { it.responseId }.toSet())
        assertEquals(18, ecus.first { it.responseId == "18DAF111" }.supportedPids.size) // engine
    }

    // SYNTHETIC 29-bit replies in the real format of the 2026-11-18 recording (TODO(capture): real codes).
    private val car = mapOf(
        "0101" to "18DAF111064101 8107E5A5\r18DAF11D06410100000000\r\r",
        "03" to "18DAF1110443 01145C\r18DAF11D024300\r\r",
        "07" to "18DAF111024700\r18DAF11D024700\r\r",
        "020200" to "18DAF111054202000000\r\r",
        "010C0D" to "18DAF11106410C0D480D00\r18DAF11D06410C0D480D00\r\r",
        "010C" to "18DAF11104410C0000\r18DAF11D04410C0000\r\r",
        "04" to "18DAF1110144\r18DAF11D0144\r\r",
    )

    @Test
    fun `codes are read without any ATSH on 29-bit - light ON shows the stored EVAP code`() = runTest {
        val fake = FakeCar(car)
        val elm = Elm327(fake, ConsolePolicy).also { it.start(backgroundScope) }
        val report = DiagnosticsReader(elm, can29, headersOn = true).read()
        assertTrue(fake.sent.none { it.startsWith("ATSH") }, "sent: ${fake.sent}")
        assertEquals(true, report.milOn)
        assertEquals(listOf("P145C"), report.stored["18DAF111"])
        assertTrue(report.storedRead)
    }

    @Test
    fun `live data keeps only the engine's answers, no ATSH`() = runTest {
        val fake = FakeCar(car)
        val elm = Elm327(fake, ConsolePolicy).also { it.start(backgroundScope) }
        val live = LiveDataSession(elm, can29, headersOn = true, requestId = "18DA11F1", clock = { 0L })
        assertTrue(live.begin())
        val sample = live.sample(listOf(0x0C, 0x0D))
        live.end()
        assertTrue(fake.sent.none { it.startsWith("ATSH") }, "sent: ${fake.sent}")
        assertEquals(850.0, sample.values.getValue(0x0C).value)
    }

    @Test
    fun `a dropped reply is retried before saying couldn't read`() = runTest {
        val fake = FakeCar(car, sequences = mapOf("03" to listOf("NO DATA\r\r", "NO DATA\r\r")))
        val elm = Elm327(fake, ConsolePolicy).also { it.start(backgroundScope) }
        val report = DiagnosticsReader(elm, can29, headersOn = true).read()
        assertEquals(3, fake.sent.count { it == "03" })
        assertEquals(listOf("P145C"), report.stored["18DAF111"])
        assertTrue(report.problems.isEmpty())
    }

    @Test
    fun `persistent silence is still reported after the retries`() = runTest {
        val fake = FakeCar(car + ("03" to "NO DATA\r\r"))
        val elm = Elm327(fake, ConsolePolicy).also { it.start(backgroundScope) }
        val report = DiagnosticsReader(elm, can29, headersOn = true).read()
        assertEquals(DiagnosticsReader.READ_ATTEMPTS, fake.sent.count { it == "03" })
        assertEquals(false, report.storedRead)
    }

    @Test
    fun `protocol layer refuses ATSH on 29-bit, even typed in the console`() {
        val policy29 = protocolAwareConsolePolicy { can29 }
        assertTrue(policy29.check("ATSH7DF") is com.crvdiag.elm.Verdict.Refused)
        assertTrue(policy29.check("ATSH7E0") is com.crvdiag.elm.Verdict.Refused)
        assertEquals(com.crvdiag.elm.Verdict.Allowed, policy29.check("0101"))
        // Before the protocol is known, and on 11-bit, the approved headers stay allowed.
        assertEquals(com.crvdiag.elm.Verdict.Allowed, protocolAwareConsolePolicy { null }.check("ATSH7DF"))
        assertEquals(com.crvdiag.elm.Verdict.Allowed, protocolAwareConsolePolicy { ElmProtocol.CAN_11_500 }.check("ATSH7DF"))
    }

    @Test
    fun `VIN from a 29-bit multi-frame reply`() {
        val lines = listOf("18DAF1111014490201354A36", "18DAF111215245344835 3042", "18DAF111224C30313730 3232")
        assertEquals("5J6RE4H50BL017022", VehicleInfo.vin(ObdMessages.collect(lines, can29, headersOn = true).single()))
    }

    @Test
    fun `clearing on 29-bit sends no ATSH`() = runTest {
        val fake = FakeCar(car)
        val elm = Elm327(fake, ConsolePolicy).also { it.start(backgroundScope) }
        val outcome = CodeClearer(elm, can29, headersOn = true).clear()
        assertEquals(listOf("010C", "04"), fake.sent)
        assertEquals(ClearOutcome.Cleared(listOf("18DAF111", "18DAF11D"), emptyMap()), outcome)
    }
}
