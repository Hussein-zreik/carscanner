package com.crvdiag.app.cartest

import com.crvdiag.elm.Elm327
import com.crvdiag.elm.ElmProtocol
import com.crvdiag.obd.ConsolePolicy
import com.crvdiag.obd.EcuDiscovery
import com.crvdiag.transport.FakeTransport
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CarTestRunnerTest {
    private val can29 = ElmProtocol.CAN_29_500
    // Real 0100 lines from testdata/2026-11-18_wifi_29bit-codes-failed.log
    private val ecus = EcuDiscovery.fromPid00(listOf("18DAF11D064100983A8003", "18DAF111064100BE3FA813"), can29)

    /** Builds a replay recording (testdata format) from command → reply. */
    private fun recording(replies: Map<String, String>) = replies.entries.joinToString("\n") { (cmd, reply) ->
        "00:00:00.000 TX $cmd\\r\n00:00:00.001 RX " + reply.replace("\r", "\\r") + ">"
    }

    // SYNTHETIC 29-bit car (format of the real recording): light ON with stored P145C, engine idling.
    private val car = mapOf(
        "0101" to "18DAF111064101 8107E5A5\r18DAF11D06410100000000\r\r",
        "03" to "18DAF1110443 01145C\r18DAF11D024300\r\r",
        "07" to "18DAF111024700\r18DAF11D024700\r\r",
        "020200" to "NO DATA\r\r",
        "0A" to "18DAF111024A00\r\r",
        "0902" to "18DAF1111014490201354A36\r18DAF111215245344835 3042\r18DAF111224C30313730 3232\r\r",
        "0904" to "NO DATA\r\r", "090A" to "NO DATA\r\r",
        "06A2" to "18DAF1111013 46A20B24000000\r18DAF11121 00FFFFA20C2400 00\r18DAF1112200 00FFFF0000000000\r\r",
        "06A3" to "NO DATA\r\r", "06A4" to "NO DATA\r\r", "06A5" to "NO DATA\r\r",
        "0100" to "18DAF111064100BE3FA813\r18DAF11D064100983A8003\r\r",
        "0120" to "NO DATA\r\r",
        // Multi-PID not answered → the session falls back to single PIDs.
        "010C0D0504110B" to "NO DATA\r\r",
        "010C" to "18DAF11104410C0D48\r\r", "010D" to "18DAF11103410D00\r\r", "0105" to "18DAF1110341057D\r\r",
        "0104" to "18DAF11103410440\r\r", "0111" to "18DAF1110341112B\r\r", "010B" to "18DAF11103410B1E\r\r",
        "0110" to "18DAF11104411001 2C\r\r", "010F" to "18DAF11103410F48\r\r", "010E" to "18DAF11103410E8C\r\r",
        "0106" to "18DAF11103410682\r\r", "0107" to "18DAF1110341077D\r\r", "0115" to "18DAF11104411580FF\r\r",
        "0103" to "18DAF111044103 0200\r\r",
    )

    private var now = 0L
    private val clock = { now += 250; now }

    @Test
    fun `full car test on a 29-bit car - all checks pass and no ATSH is sent`() = runTest {
        val fake = FakeTransport.fromText(recording(car))
        val elm = Elm327(fake, ConsolePolicy).also { it.start(backgroundScope) }
        val outcome = CarTestRunner(elm, can29, headersOn = true, ecus = ecus, maxLiveSamples = 3, clock = clock).run {}

        val byTitle = outcome.results.associateBy { it.title }
        assertEquals(StepStatus.Pass, byTitle.getValue("Car network").status)
        assertEquals(StepStatus.Pass, byTitle.getValue("Trouble codes & readiness").status, byTitle.getValue("Trouble codes & readiness").detail)
        assertTrue("Light ON" in byTitle.getValue("Trouble codes & readiness").detail)
        assertTrue("P145C" in byTitle.getValue("Trouble codes & readiness").detail)
        assertEquals(StepStatus.Pass, byTitle.getValue("VIN").status)
        assertEquals(StepStatus.Pass, byTitle.getValue("Live data").status, byTitle.getValue("Live data").detail)
        assertEquals(3, outcome.liveSamples)
        assertTrue(fake.sent.none { it.startsWith("ATSH") }, "no ATSH on 29-bit")
        assertTrue(fake.sent.none { it.removeSuffix("\r") == "04" }, "a car test never clears codes")
    }

    @Test
    fun `silent car is reported as failed, never as clean`() = runTest {
        val silent = car.mapValues { "NO DATA\r\r" }
        val fake = FakeTransport.fromText(recording(silent))
        val elm = Elm327(fake, ConsolePolicy).also { it.start(backgroundScope) }
        val outcome = CarTestRunner(elm, can29, headersOn = true, ecus = ecus, maxLiveSamples = 2, clock = clock).run {}
        val byTitle = outcome.results.associateBy { it.title }
        assertEquals(StepStatus.Fail, byTitle.getValue("Trouble codes & readiness").status)
        assertEquals(StepStatus.Fail, byTitle.getValue("Live data").status)
    }
}
