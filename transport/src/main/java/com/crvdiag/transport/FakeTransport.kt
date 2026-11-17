package com.crvdiag.transport

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.IOException

/**
 * Replays a recorded session (testdata/ format) as if it were the adapter. Each command gets
 * the reply chunks recorded for it, in recorded order (the last one repeats once exhausted).
 * Commands never recorded get [unknownReply], like the clone answering "?".
 */
class FakeTransport(
    lines: List<RecordedLog.Line>,
    private val unknownReply: String = "?\r\r>",
) : Transport {
    private val replies: Map<String, ArrayDeque<List<String>>> =
        RecordedLog.exchanges(lines).groupBy { it.command }
            .mapValues { (_, list) -> ArrayDeque(list.map { it.replyChunks }) }
    private val out = Channel<String>(Channel.UNLIMITED)
    private var closed = false
    val sent = mutableListOf<String>()

    override suspend fun connect() = Unit

    override suspend fun send(command: String) {
        if (closed) throw IOException("Link closed")
        sent += command
        val queue = replies[command]
        val chunks = when {
            queue == null -> listOf(unknownReply)
            queue.size > 1 -> queue.removeFirst()
            else -> queue.first()
        }
        chunks.forEach { out.send(it) }
    }

    override val responses: Flow<String> = out.receiveAsFlow()

    override suspend fun close() {
        closed = true
        out.close()
    }

    companion object {
        fun fromText(text: String) = FakeTransport(RecordedLog.parse(text))
    }
}
