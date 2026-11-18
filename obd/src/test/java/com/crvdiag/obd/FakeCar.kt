package com.crvdiag.obd

import com.crvdiag.transport.Transport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Adapter + car after startup (echo off, headers on, protocol 6). Unknown commands get "?".
 * Replies are SYNTHETIC in the real clone's format unless a test says otherwise.
 */
class FakeCar(
    private val replies: Map<String, String>,
    /** Per command: replies used in order before falling back to [replies] (simulates dropped replies). */
    sequences: Map<String, List<String>> = emptyMap(),
) : Transport {
    private val out = Channel<String>(Channel.UNLIMITED)
    private val queued = sequences.mapValues { ArrayDeque(it.value) }
    val sent = mutableListOf<String>()

    override suspend fun connect() = Unit
    override suspend fun send(command: String) {
        val cmd = command.removeSuffix("\r")
        sent += cmd
        val reply = queued[cmd]?.removeFirstOrNull() ?: replies[cmd] ?: "?\r\r"
        out.send("$reply>")
    }
    override val responses: Flow<String> = out.receiveAsFlow()
    override suspend fun close() { out.close() }
}
