package com.crvdiag.honda.capture

import com.crvdiag.elm.CommandPolicy
import com.crvdiag.elm.Verdict
import com.crvdiag.transport.RecordedLog
import com.crvdiag.transport.TrafficLog
import com.crvdiag.transport.Transport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class CaptureTest {
    private fun verdict(cmd: String) = CapturePolicy.check(CommandPolicy.normalize(cmd))
    private fun allowed(cmd: String) = verdict(cmd) == Verdict.Allowed

    @Test
    fun `read requests pass`() {
        listOf("0100", "010C", "03", "07", "0A", "0902", "06A4", "22 1234", "21 01", "1A 87", "19 02 FF", "13", "18 00 FF 00")
            .forEach { assertTrue(allowed(it), it) }
    }

    @Test
    fun `anything that can change a module is blocked`() {
        listOf("04", "14 FF FF FF", "10 03", "11 01", "27 01", "2E 1234 00", "2F 1234 03", "31 01 FF00", "3B 01 00",
            "23 001000 10", "34 00", "3D 00", "28 00", "85 02", "3E 00", "AB")
            .forEach { assertTrue(!allowed(it), it) }
    }

    @Test
    fun `adapter settings pass, adapter EEPROM and bus control are blocked`() {
        listOf("ATZ", "ATE0", "ATSP7", "ATSH DA 10 F1", "ATSH18DA10F1", "ATSH7E0", "ATSH7C0", "ATCRA 18DAF110", "ATCAF0",
            "ATCF 7E8", "ATCM 7F0", "ATCP 18", "ATFCSH 7E0", "ATFCSD 300000", "ATFCSM1", "ATST FF", "ATAT1")
            .forEach { assertTrue(allowed(it), it) }
        listOf("ATPP 01 SV 00", "ATSD 00", "ATMA", "ATFI", "ATSI", "ATBI", "ATBRD 23", "ATLP", "ATCSM1", "STDI", "AT@3 XXX")
            .forEach { assertTrue(!allowed(it), it) }
    }

    private class FakeAdapter : Transport {
        val sent = mutableListOf<String>()
        private val out = Channel<String>(Channel.UNLIMITED)
        override suspend fun connect() = Unit
        override suspend fun send(command: String) {
            sent += command
            out.send("OK\r\r>")
        }
        override val responses: Flow<String> = out.receiveAsFlow()
        override suspend fun close() { out.close() }
    }

    @Test
    fun `relay forwards reads, answers blocked commands with a question mark and logs them`() = runTest {
        val adapter = FakeAdapter()
        val log = TrafficLog(clock = { 0L })
        val relay = CaptureRelay(adapter, log)
        val client = ByteArrayInputStream("ATZ\r01 00\r04\r22 1234\r\n10 03\r".toByteArray())
        val toClient = ByteArrayOutputStream()
        relay.serve(client, toClient)

        assertEquals(listOf("ATZ\r", "0100\r", "221234\r"), adapter.sent)
        assertEquals("?\r\r>?\r\r>", toClient.toString())
        assertEquals(3, relay.stats.value.relayed)
        assertEquals(2, relay.stats.value.blocked)
        val blocked = log.entries.value.filter { it.text.startsWith("BLOCKED") }.map { it.text.substringBefore(" (") }
        assertEquals(listOf("BLOCKED 04", "BLOCKED 1003"), blocked)
    }

    @Test
    fun `summary groups Honda requests by header and counts standard ones`() {
        val log = """
            10:00:00.000 TX ATZ\r
            10:00:00.100 RX ELM327 v1.5\r\r>
            10:00:00.200 TX 010C\r
            10:00:00.300 RX 18DAF11104410C0A2C\r\r>
            10:00:00.400 TX ATSH18DA10F1\r
            10:00:00.500 RX OK\r\r>
            10:00:00.600 TX 221234\r
            10:00:00.700 RX 18DAF11005621234AB\r\r>
            10:00:00.800 TX 221234\r
            10:00:00.900 RX NO DATA\r\r>
            10:00:01.000 INFO BLOCKED 1003 (Service 10 can change module state: blocked in capture mode)
        """.trimIndent()
        val s = CaptureSummary.of(RecordedLog.parse(log))
        assertEquals(1, s.standardRequests)
        assertEquals(listOf(CaptureSummary.HondaRequest("18DA10F1", "221234", 2, "18DAF11005621234AB")), s.honda)
        assertEquals(listOf("1003"), s.blocked)
        assertTrue(s.asComments().any { it.startsWith("# HONDA 18DA10F1 221234 x2") })
    }
}
