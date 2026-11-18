package com.crvdiag.honda.capture

import com.crvdiag.elm.CommandPolicy
import com.crvdiag.elm.GuardedSender
import com.crvdiag.elm.Verdict
import com.crvdiag.transport.TrafficLog
import com.crvdiag.transport.TrafficLog.Direction
import com.crvdiag.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Capture mode: relays another diagnostic app (the "client", e.g. Car Scanner) to the adapter
 * and records every request and reply, so Honda's request IDs can be learned from the real car.
 *
 * SAFETY: every client command passes [CapturePolicy] through a [GuardedSender] before it reaches
 * the adapter; refused commands get "?" (like an adapter that doesn't know them) and are logged.
 * Android-free: the app supplies the client's socket streams and the connected adapter.
 */
class CaptureRelay(
    private val adapter: Transport,
    private val log: TrafficLog,
    policy: CommandPolicy = CapturePolicy,
) {
    data class Stats(
        val clientConnected: Boolean = false,
        val relayed: Int = 0,
        val blocked: Int = 0,
        val recent: List<String> = emptyList(),
    )

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    private val sender = GuardedSender(adapter, policy) { cmd, reason ->
        log.add(Direction.INFO, "BLOCKED $cmd ($reason)")
    }

    @Volatile private var client: OutputStream? = null
    private var forwarder: Job? = null

    /** Starts forwarding adapter replies to whichever client is connected. Adapter must be connected. */
    fun start(scope: CoroutineScope) {
        forwarder = scope.launch(Dispatchers.IO) {
            adapter.responses.collect { chunk ->
                val out = client ?: return@collect // no client: the reply is only logged
                try {
                    out.write(chunk.toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                } catch (_: IOException) {
                    client = null
                }
            }
        }
    }

    /** Serves one client until it disconnects (blocking reads on the IO dispatcher). */
    suspend fun serve(input: InputStream, output: OutputStream) = withContext(Dispatchers.IO) {
        client = output
        _stats.update { it.copy(clientConnected = true) }
        val line = StringBuilder()
        val buffer = ByteArray(512)
        try {
            while (true) {
                val n = try { input.read(buffer) } catch (_: IOException) { -1 }
                if (n < 0) break
                for (i in 0 until n) {
                    val c = (buffer[i].toInt() and 0xFF).toChar()
                    if (c == '\r' || c == '\n') {
                        if (line.isNotBlank()) handle(line.toString(), output)
                        line.clear()
                    } else {
                        line.append(c)
                    }
                }
            }
        } finally {
            client = null
            _stats.update { it.copy(clientConnected = false) }
        }
    }

    private suspend fun handle(raw: String, output: OutputStream) {
        val cmd = CommandPolicy.normalize(raw)
        val verdict = try {
            sender.send(raw)
        } catch (e: IOException) {
            log.add(Direction.INFO, "Adapter link lost: ${e.message}")
            throw e
        }
        val allowed = verdict == Verdict.Allowed
        _stats.update {
            it.copy(
                relayed = it.relayed + if (allowed) 1 else 0,
                blocked = it.blocked + if (allowed) 0 else 1,
                recent = (it.recent + (if (allowed) cmd else "BLOCKED $cmd")).takeLast(RECENT),
            )
        }
        if (!allowed) {
            try {
                output.write("?\r\r>".toByteArray(Charsets.ISO_8859_1))
                output.flush()
            } catch (_: IOException) {
                // client gone; serve() ends on its next read
            }
        }
    }

    fun stop() {
        forwarder?.cancel()
        runBlocking { runCatching { adapter.close() } }
    }

    companion object {
        const val RECENT = 30
    }
}
