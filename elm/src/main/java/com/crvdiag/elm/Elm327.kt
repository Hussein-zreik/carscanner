package com.crvdiag.elm

import com.crvdiag.transport.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * ELM327 command/response driver. One command at a time; each reply is read until the '>'
 * prompt and returned as a typed [ElmResult]. Every command passes the [policy] allow-list.
 *
 * Owns the transport's single [Transport.responses] collector: call [start] once after connecting.
 */
class Elm327(
    private val transport: Transport,
    policy: CommandPolicy,
    private val onRefused: (command: String, reason: String) -> Unit = { _, _ -> },
) {
    private val sender = GuardedSender(transport, policy, onRefused)
    private val chunks = Channel<String>(Channel.UNLIMITED)
    private val lock = Mutex()
    private val _closed = CompletableDeferred<Throwable?>()

    /** Completes when the link ends: null for a normal close, the error if it dropped. */
    val closed: Deferred<Throwable?> get() = _closed

    fun start(scope: CoroutineScope): Job = scope.launch {
        try {
            transport.responses.collect { chunks.send(it) }
            _closed.complete(null)
        } catch (e: CancellationException) {
            _closed.complete(null)
            throw e
        } catch (e: Exception) {
            _closed.complete(e)
        } finally {
            chunks.close()
        }
    }

    /**
     * Sends [command] and waits for its reply. A [ElmResult.Timeout] (no '>' prompt in time)
     * is retried up to [retries] times; the clone is known to drop replies.
     *
     * [policy] replaces the default allow-list for this one command. Only purpose-built flows
     * use it (e.g. the obd module's internal clear-codes policy); it never bypasses checking.
     */
    suspend fun execute(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        retries: Int = 1,
        policy: CommandPolicy? = null,
    ): ElmResult = lock.withLock {
        val guard = policy?.let { GuardedSender(transport, it, onRefused) } ?: sender
        var result = executeOnce(guard, command, timeoutMs)
        var attempt = 0
        while (result is ElmResult.Timeout && attempt < retries) {
            attempt++
            result = executeOnce(guard, command, timeoutMs)
        }
        result
    }

    private sealed interface Outcome {
        data class Frame(val text: String) : Outcome
        data object LinkClosed : Outcome
    }

    private suspend fun executeOnce(guard: GuardedSender, command: String, timeoutMs: Long): ElmResult {
        // Discard anything left over (e.g. a late reply to a command that timed out).
        while (chunks.tryReceive().isSuccess) {
            // discard
        }

        val verdict = try {
            guard.send(command)
        } catch (e: IOException) {
            return ElmResult.Error("Link closed: ${e.message}")
        }
        when (verdict) {
            Verdict.Allowed -> Unit
            is Verdict.Refused -> return ElmResult.Error("Refused: ${verdict.reason}")
            Verdict.NotApplicable -> return ElmResult.Error("Refused")
        }

        val framer = PromptFramer()
        val outcome = withTimeoutOrNull(timeoutMs) {
            var result: Outcome? = null
            while (result == null) {
                val chunk = chunks.receiveCatching().getOrNull()
                result = if (chunk == null) Outcome.LinkClosed else framer.append(chunk)?.let(Outcome::Frame)
            }
            result
        }
        return when (outcome) {
            null -> ElmResult.Timeout
            Outcome.LinkClosed -> ElmResult.Error("Link closed")
            is Outcome.Frame -> ElmResponseParser.parse(command, outcome.text)
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 2_000L
    }
}
