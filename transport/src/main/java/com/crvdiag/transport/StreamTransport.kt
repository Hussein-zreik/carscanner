package com.crvdiag.transport

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** The two halves of an open byte link. */
class Streams(val input: InputStream, val output: OutputStream)

/**
 * Shared logic for socket-like transports (Bluetooth RFCOMM, TCP): a background reader that
 * turns incoming bytes into ASCII text chunks, and a serialized writer.
 *
 * [responses] supports a single collector. It completes when the link closes (by [close] or
 * end of stream) and fails with [IOException] if the link drops unexpectedly.
 */
abstract class StreamTransport(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Transport {

    /** Opens the underlying link. Blocking; always called on [ioDispatcher]. */
    protected abstract fun open(): Streams

    /** Closes the underlying link, unblocking any pending read or connect. Must be idempotent. */
    protected abstract fun closeLink()

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val chunks = Channel<String>(Channel.UNLIMITED)
    private val writeLock = Mutex()
    private var readJob: Job? = null

    @Volatile private var output: OutputStream? = null
    @Volatile private var closed = false

    override val responses: Flow<String> = chunks.receiveAsFlow()

    override suspend fun connect() {
        check(output == null) { "Already connected" }
        val streams = withContext(ioDispatcher) { open() }
        if (closed) {
            // close() raced with open(): don't leak the freshly opened link.
            withContext(ioDispatcher) { runCatching { closeLink() } }
            throw IOException("Closed while connecting")
        }
        output = streams.output
        readJob = scope.launch { readLoop(streams.input) }
    }

    private fun readLoop(input: InputStream) {
        val buffer = ByteArray(1024)
        try {
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) chunks.trySend(String(buffer, 0, n, Charsets.US_ASCII))
            }
            chunks.close()
        } catch (e: IOException) {
            // A read failing because *we* closed the link is a normal shutdown.
            if (closed) chunks.close() else chunks.close(e)
        }
    }

    override suspend fun send(command: String) {
        withContext(ioDispatcher) {
            writeLock.withLock {
                val out = output ?: throw IOException("Not connected")
                if (closed) throw IOException("Link closed")
                out.write(command.toByteArray(Charsets.US_ASCII))
                out.flush()
            }
        }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        withContext(ioDispatcher) { runCatching { closeLink() } }
        readJob?.cancel()
        chunks.close()
        scope.cancel()
    }
}
