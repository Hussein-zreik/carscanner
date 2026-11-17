package com.crvdiag.elm

import com.crvdiag.transport.Transport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.IOException

/**
 * Test double that behaves like the V03H4 clone seen in testdata/: echo is ON after ATZ and
 * arrives as its own chunk, replies end with "\r\r>", unknown commands get "?".
 */
class ScriptedAdapter(
    private val replies: Map<String, String>,
    private var echo: Boolean = true,
) : Transport {
    private val out = Channel<String>(Channel.UNLIMITED)
    private var closed = false
    val sent = mutableListOf<String>()

    /** Number of times to stay silent (simulated dropped reply) per command. */
    val drop = mutableMapOf<String, Int>()

    override suspend fun connect() = Unit

    override suspend fun send(command: String) {
        if (closed) throw IOException("Link closed")
        sent += command
        val cmd = command.removeSuffix("\r")
        val toDrop = drop[cmd] ?: 0
        if (toDrop > 0) {
            drop[cmd] = toDrop - 1
            return
        }
        if (echo) out.send("$cmd\r")
        when (cmd) {
            "ATZ", "ATE1" -> echo = true
            "ATE0" -> echo = false
        }
        out.send((replies[cmd] ?: "?\r\r") + ">")
    }

    override val responses: Flow<String> = out.receiveAsFlow()

    override suspend fun close() {
        closed = true
        out.close()
    }

    companion object {
        /**
         * Clone replies. ATZ/ATRV/0100 payloads are from the real recording; the CAN headers
         * (7E8/7E9, PCI 06) are SYNTHETIC until a recording with ATH1 exists.
         * TODO(capture): replace with a real ATH1 recording after the Session 3 car test.
         */
        val CLONE = mapOf(
            "ATZ" to "\r\rELM327 v1.5\r\r",
            "ATI" to "ELM327 v1.5\r\r",
            "ATE0" to "OK\r\r",
            "ATL0" to "OK\r\r",
            "ATS0" to "OK\r\r",
            "ATH1" to "OK\r\r",
            "ATSP0" to "OK\r\r",
            "ATSP6" to "OK\r\r",
            "ATRV" to "12.1V\r\r",
            "0100" to "SEARCHING...\r7E8064100BE3FA813 \r7E9064100983A8003 \r\r",
            "ATDPN" to "A6\r\r",
        )
    }
}
