package com.crvdiag.elm

import com.crvdiag.transport.Transport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuardedSenderTest {
    private class Recorder : Transport {
        val sent = mutableListOf<String>()
        override suspend fun connect() = Unit
        override suspend fun send(command: String) { sent += command }
        override val responses: Flow<String> = emptyFlow()
        override suspend fun close() = Unit
    }

    private val transport = Recorder()
    private val refused = mutableListOf<String>()
    private val sender = GuardedSender(transport, CompositePolicy(AdapterCommandPolicy)) { cmd, _ -> refused += cmd }

    @Test
    fun `allowed command is normalized and sent with CR`() = runTest {
        assertEquals(Verdict.Allowed, sender.send(" at z "))
        assertEquals(listOf("ATZ\r"), transport.sent)
    }

    @Test
    fun `refused command never reaches the transport`() = runTest {
        assertTrue(sender.send("AT SH 7C0") is Verdict.Refused)
        assertTrue(transport.sent.isEmpty())
        assertEquals(listOf("ATSH7C0"), refused)
    }
}
