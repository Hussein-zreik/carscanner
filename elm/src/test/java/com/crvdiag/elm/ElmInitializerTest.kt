package com.crvdiag.elm

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ElmInitializerTest {
    private val policy = CompositePolicy(AdapterCommandPolicy, CommandPolicy { cmd ->
        if (cmd == "0100") Verdict.Allowed else Verdict.NotApplicable
    })

    private suspend fun kotlinx.coroutines.test.TestScope.init(
        replies: Map<String, String>,
        protocol: ElmProtocol = ElmProtocol.AUTO,
    ): Pair<ScriptedAdapter, InitOutcome> {
        // echo = false before ATZ, exactly like the first reset in the real recording.
        val adapter = ScriptedAdapter(replies, echo = false)
        val elm = Elm327(adapter, policy).also { it.start(backgroundScope) }
        return adapter to ElmInitializer(elm).run(protocol)
    }

    @Test
    fun `baseline sequence against the clone`() = runTest {
        val (adapter, outcome) = init(ScriptedAdapter.CLONE)
        assertEquals(
            listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH1", "ATSP0", "ATRV", "0100", "ATDPN").map { "$it\r" },
            adapter.sent,
        )
        val report = (outcome as InitOutcome.Success).report
        assertEquals("ELM327 v1.5", report.adapterId)
        assertEquals("12.1V", report.voltage)
        assertEquals(DetectedProtocol(ElmProtocol.CAN_11_500, automatic = true), report.detected)
        assertEquals(listOf("7E8064100BE3FA813", "7E9064100983A8003"), report.pid00Lines)
        assertTrue(report.headersOn)
        assertTrue(report.warnings.isEmpty())
    }

    @Test
    fun `clone rejecting an optional command is only a warning`() = runTest {
        val (_, outcome) = init(ScriptedAdapter.CLONE - "ATS0") // scripted adapter answers "?"
        val report = (outcome as InitOutcome.Success).report
        assertEquals(listOf("ATS0 (spaces off) not accepted"), report.warnings)
    }

    @Test
    fun `car not answering fails at 0100 with a hint`() = runTest {
        val (_, outcome) = init(ScriptedAdapter.CLONE + ("0100" to "SEARCHING...\rNO DATA\r\r"))
        val failure = outcome as InitOutcome.Failure
        assertEquals("0100", failure.step)
        assertEquals(ElmResult.NoData, failure.result)
        assertTrue("ignition" in failure.hint.lowercase())
    }

    @Test
    fun `saved protocol 6 is set explicitly - no auto search`() = runTest {
        val (adapter, outcome) = init(ScriptedAdapter.CLONE + ("ATDPN" to "6\r\r"), ElmProtocol.CAN_11_500)
        assertTrue("ATSP6\r" in adapter.sent)
        assertTrue("ATSP0\r" !in adapter.sent)
        assertEquals(false, (outcome as InitOutcome.Success).report.detected?.automatic)
    }
}
