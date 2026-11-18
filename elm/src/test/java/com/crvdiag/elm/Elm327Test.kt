package com.crvdiag.elm

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Elm327Test {
    private val policy = CompositePolicy(AdapterCommandPolicy, CommandPolicy { cmd ->
        if (cmd == "0100") Verdict.Allowed else Verdict.NotApplicable
    })

    @Test
    fun `strips echo and returns typed result`() = runTest {
        val adapter = ScriptedAdapter(ScriptedAdapter.CLONE)
        val elm = Elm327(adapter, policy).also { it.start(backgroundScope) }
        assertEquals(ElmResult.Ok(listOf("ELM327 v1.5")), elm.execute("ati"))
        assertEquals(listOf("ATI\r"), adapter.sent)
    }

    @Test
    fun `dropped reply is retried once`() = runTest {
        val adapter = ScriptedAdapter(ScriptedAdapter.CLONE).apply { drop["ATRV"] = 1 }
        val elm = Elm327(adapter, policy).also { it.start(backgroundScope) }
        assertEquals(ElmResult.Ok(listOf("12.1V")), elm.execute("ATRV"))
        assertEquals(listOf("ATRV\r", "ATRV\r"), adapter.sent)
    }

    @Test
    fun `gives up with Timeout after retries`() = runTest {
        val adapter = ScriptedAdapter(ScriptedAdapter.CLONE).apply { drop["ATRV"] = 5 }
        val elm = Elm327(adapter, policy).also { it.start(backgroundScope) }
        assertEquals(ElmResult.Timeout, elm.execute("ATRV", retries = 1))
    }

    @Test
    fun `refused command never reaches the adapter`() = runTest {
        val adapter = ScriptedAdapter(ScriptedAdapter.CLONE)
        val refused = mutableListOf<String>()
        val elm = Elm327(adapter, policy) { cmd, _ -> refused += cmd }.also { it.start(backgroundScope) }
        val result = elm.execute("AT SH 7C0")
        assertTrue(result is ElmResult.Error && result.message.startsWith("Refused"))
        assertTrue(adapter.sent.isEmpty())
        assertEquals(listOf("ATSH7C0"), refused)
    }

    @Test
    fun `closed link gives Error, not an exception`() = runTest {
        val adapter = ScriptedAdapter(ScriptedAdapter.CLONE)
        val elm = Elm327(adapter, policy).also { it.start(backgroundScope) }
        adapter.close()
        assertTrue(elm.execute("ATI") is ElmResult.Error)
        assertEquals(null, elm.closed.await())
    }

    // --- reply filter (late answers from the clone, 2026-11-18) ---

    private val speedOnly = ReplyFilter { lines -> lines.filter { "410D" in it } }
    private val obdPolicy = CompositePolicy(AdapterCommandPolicy, CommandPolicy { Verdict.Allowed })

    @Test
    fun `stale reply is skipped and the real answer returned`() = runTest {
        val adapter = ScriptedAdapter(mapOf("010D" to "18DAF11104410C17B0\r\r>18DAF11103410D00\r\r"), echo = false)
        val elm = Elm327(adapter, obdPolicy).also { it.start(backgroundScope) }
        assertEquals(ElmResult.Ok(listOf("18DAF11103410D00")), elm.execute("010D", filter = speedOnly))
    }

    @Test
    fun `late answer after NO DATA is accepted within the grace period`() = runTest {
        val adapter = ScriptedAdapter(mapOf("010D" to "NO DATA\r\r>18DAF11103410D00\r\r"), echo = false)
        val elm = Elm327(adapter, obdPolicy).also { it.start(backgroundScope) }
        assertEquals(ElmResult.Ok(listOf("18DAF11103410D00")), elm.execute("010D", filter = speedOnly))
    }

    @Test
    fun `NO DATA with only stale data stays NO DATA`() = runTest {
        val adapter = ScriptedAdapter(mapOf("010D" to "NO DATA\r\r>18DAF11104410C17B0\r\r"), echo = false)
        val elm = Elm327(adapter, obdPolicy).also { it.start(backgroundScope) }
        assertEquals(ElmResult.NoData, elm.execute("010D", filter = speedOnly))
    }

    @Test
    fun `without a filter the first reply is still the answer`() = runTest {
        val adapter = ScriptedAdapter(mapOf("010D" to "18DAF11104410C17B0\r\r>18DAF11103410D00\r\r"), echo = false)
        val elm = Elm327(adapter, obdPolicy).also { it.start(backgroundScope) }
        assertEquals(ElmResult.Ok(listOf("18DAF11104410C17B0")), elm.execute("010D"))
    }

    @Test
    fun `stale question mark is not the answer when the real one follows`() = runTest {
        val adapter = ScriptedAdapter(mapOf("010D" to "?\r\r>18DAF11103410D00\r\r"), echo = false)
        val elm = Elm327(adapter, obdPolicy).also { it.start(backgroundScope) }
        assertEquals(ElmResult.Ok(listOf("18DAF11103410D00")), elm.execute("010D", filter = speedOnly))
    }

    @Test
    fun `a real question mark is still returned after the grace period`() = runTest {
        val adapter = ScriptedAdapter(mapOf("010C0D" to "?\r\r"), echo = false)
        val elm = Elm327(adapter, obdPolicy).also { it.start(backgroundScope) }
        assertEquals(ElmResult.Error("?"), elm.execute("010C0D", filter = speedOnly))
    }
}
