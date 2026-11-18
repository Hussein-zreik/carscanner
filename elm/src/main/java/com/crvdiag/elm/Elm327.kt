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
        filter: ReplyFilter? = null,
    ): ElmResult = lock.withLock {
        val guard = policy?.let { GuardedSender(transport, it, onRefused) } ?: sender
        var result = executeOnce(guard, command, timeoutMs, filter)
        var attempt = 0
        while (result is ElmResult.Timeout && attempt < retries) {
            attempt++
            result = executeOnce(guard, command, timeoutMs, filter)
        }
        result
    }

    private sealed interface Outcome {
        data class Frame(val text: String) : Outcome
        data object LinkClosed : Outcome
    }

    private suspend fun executeOnce(
        guard: GuardedSender,
        command: String,
        timeoutMs: Long,
        filter: ReplyFilter?,
    ): ElmResult {
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

        /** Next complete reply: one already buffered in the framer, else read more chunks. */
        suspend fun nextFrame(): Outcome {
            framer.append("")?.let { return Outcome.Frame(it) }
            while (true) {
                val chunk = chunks.receiveCatching().getOrNull() ?: return Outcome.LinkClosed
                framer.append(chunk)?.let { return Outcome.Frame(it) }
            }
        }

        // Without a filter the first reply is the answer (AT commands, raw console).
        if (filter == null) {
            return when (val outcome = withTimeoutOrNull(timeoutMs) { nextFrame() }) {
                null -> ElmResult.Timeout
                Outcome.LinkClosed -> ElmResult.Error("Link closed")
                is Outcome.Frame -> ElmResponseParser.parse(command, outcome.text)
            }
        }

        // With a filter: the V03H4 clone sometimes answers "NO DATA" early and prints the real
        // answer later, after the prompt - where it used to be taken as the answer to the NEXT
        // request (seen on this car, testdata/2026-11-18_wifi_29bit-late-replies.log).
        // Replies that don't belong to this request are skipped; after a NO DATA (or "?") the late
        // answer gets a short grace period.
        // A NO DATA or "?" may itself be stale (the clone printed one late for an earlier request),
        // so it is held during the grace period and returned only if nothing better arrives.
        var held: ElmResult? = null
        val result = withTimeoutOrNull(timeoutMs) {
            var answer: ElmResult? = null
            while (answer == null) {
                val outcome = if (held != null) {
                    withTimeoutOrNull(LATE_REPLY_GRACE_MS) { nextFrame() } ?: break
                } else {
                    nextFrame()
                }
                answer = when (outcome) {
                    Outcome.LinkClosed -> ElmResult.Error("Link closed")
                    is Outcome.Frame -> when (val parsed = ElmResponseParser.parse(command, outcome.text)) {
                        is ElmResult.Ok -> filter.keep(parsed.lines).takeIf { it.isNotEmpty() }?.let { ElmResult.Ok(it) }
                        ElmResult.NoData -> null.also { if (held == null) held = parsed }
                        is ElmResult.Error -> if (parsed.message == "?") null.also { if (held == null) held = parsed } else parsed
                        ElmResult.Timeout -> parsed
                    }
                }
            }
            answer
        }
        return result ?: held ?: ElmResult.Timeout
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 2_000L

        /** After a NO DATA or "?", how long a late answer to the same request is still accepted. */
        const val LATE_REPLY_GRACE_MS = 300L
    }
}
