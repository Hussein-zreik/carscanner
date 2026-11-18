package com.crvdiag.obd

import com.crvdiag.elm.Elm327
import com.crvdiag.elm.ElmProtocol
import com.crvdiag.elm.ElmResult
import com.crvdiag.transport.FakeTransport
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * REAL replies from the 2026-11-18 car session (0.11.0): late answers, junk after the prompt,
 * NRC 0x78 on clear, VIN missing its consecutive frames.
 */
class LateRepliesTest {
    private val can29 = ElmProtocol.CAN_29_500
    private val log = File("../testdata/2026-11-18_wifi_29bit-late-replies.log").readText()

    /** Replays the whole recording, or only the lines of one "# --- excerpt X" section. */
    private fun TestScope.replay(excerpt: Char? = null): Pair<FakeTransport, Elm327> {
        val text = if (excerpt == null) log else log.lines()
            .dropWhile { !it.startsWith("# --- excerpt $excerpt") }.drop(1)
            .takeWhile { !it.startsWith("# --- excerpt") }.joinToString("\n")
        val car = FakeTransport.fromText(text)
        return car to Elm327(car, ConsolePolicy).also { it.start(backgroundScope) }
    }

    // --- matching rules on real lines ---

    @Test
    fun `REAL - the late RPM answer does not count as the speed answer`() {
        val late = listOf("18DAF11D04410C17A4", "18DAF11104410C17B0")
        assertTrue(ObdReplyMatch.filter("010D", late, can29, headersOn = true).isEmpty())
        assertEquals(late, ObdReplyMatch.filter("010C", late, can29, headersOn = true))
    }

    @Test
    fun `REAL - coolant answer received for the load request is dropped`() {
        val coolant = listOf("18DAF11103410541", "18DAF11D03410541")
        assertTrue(ObdReplyMatch.filter("0104", coolant, can29, headersOn = true).isEmpty())
        assertEquals(coolant, ObdReplyMatch.filter("0105", coolant, can29, headersOn = true))
    }

    @Test
    fun `REAL - multi-frame mode 06 answer kept, junk tail dropped`() {
        val lines = listOf(
            "18DAF111101346A40B240005", "18DAF111210000FFFFA40C24", "18DAF1112200090000FFFF55",
            "DAF11D2300000055555555", "4",
        )
        assertEquals(lines.take(3), ObdReplyMatch.filter("06A4", lines, can29, headersOn = true))
        assertTrue(ObdReplyMatch.filter("06A5", lines, can29, headersOn = true).isEmpty())
    }

    @Test
    fun `negative response and multi-PID answers belong to their request`() {
        assertTrue(ObdReplyMatch.belongs("04", listOf(0x7F, 0x04, 0x78)))
        assertTrue(ObdReplyMatch.belongs("010C0D05", listOf(0x41, 0x0D, 0x00, 0x0C, 0x0A, 0x00)))
        assertTrue(!ObdReplyMatch.belongs("03", listOf(0x47, 0x00)))
        assertTrue(ObdReplyMatch.belongs("03", listOf(0x43, 0x00)))
    }

    // --- whole flows on the replayed car ---

    @Test
    fun `REAL - speed request never takes the stale RPM reply`() = runTest {
        val (_, elm) = replay()
        // 1st recorded answer to 010D: only the late 410C reply → wait, then Timeout (not a wrong value).
        val first = elm.obdRequest("010D", can29, headersOn = true, timeoutMs = 1_500, retries = 0)
        assertTrue(first is ElmResult.Timeout, "got $first")
        // 2nd: NO DATA, then the late 410C answer in the same chunk → still not ours → NO DATA.
        val second = elm.obdRequest("010D", can29, headersOn = true, timeoutMs = 1_500, retries = 0)
        assertEquals(ElmResult.NoData, second)
    }

    @Test
    fun `REAL - clear answered with NRC 0x78 is confirmed by re-reading readiness`() = runTest {
        val (car, elm) = replay('C')
        val outcome = CodeClearer(elm, can29, headersOn = true).clear()
        assertEquals(ClearOutcome.Cleared(listOf("18DAF111", "18DAF11D"), emptyMap(), confirmedByReread = true), outcome)
        assertEquals(1, car.sent.count { it == "04\r" }, "04 is never repeated")
        assertTrue("ATSH" !in car.sent.joinToString(), "no ATSH on 29-bit")
    }

    @Test
    fun `REAL - VIN with only its first frame is retried, then reported missing`() = runTest {
        val (car, elm) = replay('D')
        val (_, msgs) = elm.obdRead("0902", can29, true) { m -> m.any { VehicleInfo.vin(it) != null } }
        assertTrue(msgs.none { VehicleInfo.vin(it) != null })
        assertEquals(3, car.sent.count { it == "0902\r" })
    }

    @Test
    fun `REAL - misfire counters show cylinder 3, dropped cylinder 2 is retried`() = runTest {
        val (car, elm) = replay('E')
        val snap = MisfireReader(elm, can29, headersOn = true, engineResponseId = "18DAF111").read()
        assertEquals(1, snap.current[1])
        assertEquals(9, snap.current[3])
        assertEquals(5, snap.average10[3])
        assertNull(snap.current[2]) // NO DATA every time in the recording
        assertEquals(3, car.sent.count { it == "06A3\r" })
    }
}
