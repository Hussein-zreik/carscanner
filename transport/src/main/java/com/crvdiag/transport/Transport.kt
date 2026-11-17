package com.crvdiag.transport

import kotlinx.coroutines.flow.Flow

/**
 * Byte-level link to the OBD adapter (Bluetooth SPP, Wi-Fi TCP, or a fake replaying
 * recorded sessions). Knows nothing about ELM327 or OBD; it only moves text.
 *
 * Implementations: BluetoothSppTransport (Session 2), WifiTcpTransport, FakeTransport (Session 6).
 */
interface Transport {
    /** Opens the link. Suspends until connected or throws on failure. */
    suspend fun connect()

    /** Writes [command] to the adapter. Line termination is the caller's job (ELM uses '\r'). */
    suspend fun send(command: String)

    /** Raw text chunks as they arrive from the adapter, in order. */
    val responses: Flow<String>

    /** Closes the link and releases the socket. Safe to call more than once. */
    suspend fun close()
}
