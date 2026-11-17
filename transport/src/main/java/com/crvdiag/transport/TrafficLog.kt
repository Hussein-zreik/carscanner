package com.crvdiag.transport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Timestamped record of every raw command and reply (plus connection events).
 * Shown on the console/debug screen and exportable as text; the export format is the
 * one recorded sessions in /testdata will use.
 */
class TrafficLog(
    private val capacity: Int = 5_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val sink: ((String) -> Unit)? = null,
) {
    enum class Direction { TX, RX, INFO }

    data class Entry(val timeMs: Long, val direction: Direction, val text: String)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

    fun add(direction: Direction, text: String) {
        val entry = Entry(clock(), direction, text)
        _entries.update { (it + entry).takeLast(capacity) }
        val line = format(entry)
        sink?.invoke(line)
        listeners.forEach { it(line) }
    }

    /** Receives every formatted line from now on (e.g. a drive recording's raw log file). */
    fun addListener(listener: (String) -> Unit) = listeners.add(listener)

    fun removeListener(listener: (String) -> Unit) = listeners.remove(listener)

    fun clear() = _entries.update { emptyList() }

    /** One line per entry, control characters escaped so the raw bytes stay visible. */
    fun export(): String = _entries.value.joinToString("\n") { format(it) }

    fun format(entry: Entry): String =
        "${TIME.format(Instant.ofEpochMilli(entry.timeMs).atZone(zone))} ${entry.direction} ${escape(entry.text)}"

    companion object {
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

        fun escape(text: String): String = buildString {
            for (c in text) when (c) {
                '\r' -> append("\\r")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\x%02X".format(c.code)) else append(c)
            }
        }
    }
}
