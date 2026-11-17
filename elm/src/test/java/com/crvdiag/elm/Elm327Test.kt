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
}
