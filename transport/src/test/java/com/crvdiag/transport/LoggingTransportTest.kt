package com.crvdiag.transport

import app.cash.turbine.test
import com.crvdiag.transport.TrafficLog.Direction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.ZoneOffset

class LoggingTransportTest {
    private class Stub : Transport {
        val sent = mutableListOf<String>()
        override suspend fun connect() = Unit
        override suspend fun send(command: String) { sent += command }
        override val responses: Flow<String> = flowOf("OK\r\r>")
        override suspend fun close() = Unit
    }

    @Test
    fun `every command and reply is logged`() = runTest {
        val log = TrafficLog(clock = { 0L }, zone = ZoneOffset.UTC)
        val stub = Stub()
        val transport = LoggingTransport(stub, log)

        transport.send("ATE0\r")
        transport.responses.test {
            assertEquals("OK\r\r>", awaitItem())
            awaitComplete()
        }

        assertEquals(listOf("ATE0\r"), stub.sent)
        assertEquals(
            listOf(Direction.TX to "ATE0\r", Direction.RX to "OK\r\r>"),
            log.entries.value.map { it.direction to it.text },
        )
    }
}
