package com.crvdiag.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach

/** Decorator that records every byte sent and received in [log]. Wrap every real transport. */
class LoggingTransport(
    private val delegate: Transport,
    private val log: TrafficLog,
) : Transport {
    override suspend fun connect() = delegate.connect()

    override suspend fun send(command: String) {
        log.add(TrafficLog.Direction.TX, command)
        delegate.send(command)
    }

    override val responses: Flow<String> =
        delegate.responses.onEach { log.add(TrafficLog.Direction.RX, it) }

    override suspend fun close() = delegate.close()
}
