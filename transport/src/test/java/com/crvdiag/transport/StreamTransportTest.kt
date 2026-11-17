package com.crvdiag.transport

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue

class StreamTransportTest {

    /** In-memory adapter link: the test plays the adapter side. */
    private class FakeLink : StreamTransport() {
        private val toApp = LinkedBlockingQueue<Int>()
        val fromApp = ByteArrayOutputStream()

        private val input = object : InputStream() {
            override fun read(): Int = when (val v = toApp.take()) {
                EOF -> -1
                CLOSED, BROKEN -> throw IOException("link down")
                else -> v
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val first = read()
                if (first < 0) return -1
                b[off] = first.toByte()
                var n = 1
                while (n < len) {
                    val next = toApp.peek() ?: break
                    if (next < 0) break
                    b[off + n++] = toApp.take().toByte()
                }
                return n
            }
        }

        override fun open() = Streams(input, fromApp)
        override fun closeLink() = toApp.put(CLOSED)

        fun adapterWrites(text: String) = text.forEach { toApp.put(it.code) }
        fun adapterHangsUp() = toApp.put(EOF)
        fun linkDrops() = toApp.put(BROKEN)

        companion object {
            const val EOF = -1
            const val CLOSED = -2
            const val BROKEN = -3
        }
    }

    /** Chunk boundaries are arbitrary; collect until [expected] has fully arrived. */
    private suspend fun ReceiveTurbine<String>.awaitText(expected: String) {
        val received = StringBuilder()
        while (received.length < expected.length) received.append(awaitItem())
        assertEquals(expected, received.toString())
    }

    @Test
    fun `send writes the exact bytes`() = runTest {
        val link = FakeLink()
        link.connect()
        link.send("ATZ\r")
        assertEquals("ATZ\r", link.fromApp.toString(Charsets.US_ASCII))
        link.close()
    }

    @Test
    fun `adapter replies arrive as text in order`() = runTest {
        val link = FakeLink()
        link.connect()
        link.responses.test {
            link.adapterWrites("\r\rELM327 v1.5\r\r>")
            awaitText("\r\rELM327 v1.5\r\r>")
            link.adapterWrites("OK\r\r>")
            awaitText("OK\r\r>")
            link.close()
            awaitComplete()
        }
    }

    @Test
    fun `adapter hang-up completes the flow`() = runTest {
        val link = FakeLink()
        link.connect()
        link.responses.test {
            link.adapterHangsUp()
            awaitComplete()
        }
        link.close()
    }

    @Test
    fun `unexpected link drop fails the flow with IOException`() = runTest {
        val link = FakeLink()
        link.connect()
        link.responses.test {
            link.linkDrops()
            assertTrue(awaitError() is IOException)
        }
        link.close()
    }

    @Test
    fun `send before connect or after close fails`() = runTest {
        val link = FakeLink()
        assertTrue(runCatching { link.send("ATZ\r") }.exceptionOrNull() is IOException)
        link.connect()
        link.close()
        assertTrue(runCatching { link.send("ATZ\r") }.exceptionOrNull() is IOException)
        link.close() // idempotent
    }
}
