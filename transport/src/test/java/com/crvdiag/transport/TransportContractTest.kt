package com.crvdiag.transport

import app.cash.turbine.test
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Smoke test proving the JUnit5 + coroutines-test + Turbine toolchain works on the
 * Transport contract. The real FakeTransport (replaying /testdata) comes in Session 6.
 */
class TransportContractTest {

    /** Minimal echo transport: replies to every command with a canned ELM-style answer. */
    private class EchoTransport : Transport {
        private val channel = Channel<String>(Channel.UNLIMITED)
        val sent = mutableListOf<String>()

        override suspend fun connect() = Unit
        override suspend fun send(command: String) {
            sent += command
            channel.send("OK\r\r>")
        }
        override val responses: Flow<String> = channel.receiveAsFlow()
        override suspend fun close() {
            channel.close()
        }
    }

    @Test
    fun `responses arrive in order and complete on close`() = runTest {
        val transport = EchoTransport()
        transport.connect()

        transport.responses.test {
            transport.send("ATE0\r")
            assertEquals("OK\r\r>", awaitItem())
            transport.send("ATL0\r")
            assertEquals("OK\r\r>", awaitItem())
            transport.close()
            awaitComplete()
        }
        assertEquals(listOf("ATE0\r", "ATL0\r"), transport.sent)
    }
}
