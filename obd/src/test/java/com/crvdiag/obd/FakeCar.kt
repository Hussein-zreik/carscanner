package com.crvdiag.obd

import com.crvdiag.transport.Transport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Adapter + car after startup (echo off, headers on, protocol 6). Unknown commands get "?".
 * Replies are SYNTHETIC in the real clone's format unless a test says otherwise.
 */
class FakeCar(private val replies: Map<String, String>) : Transport {
    private val out = Channel<String>(Channel.UNLIMITED)
    val sent = mutableListOf<String>()

    override suspend fun connect() = Unit
    override suspend fun send(command: String) {
        sent += command.removeSuffix("\r")
        out.send((replies[command.removeSuffix("\r")] ?: "?\r\r") + ">")
    }
    override val responses: Flow<String> = out.receiveAsFlow()
    override suspend fun close() { out.close() }
}
