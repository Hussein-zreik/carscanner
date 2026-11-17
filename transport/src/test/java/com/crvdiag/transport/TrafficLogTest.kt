package com.crvdiag.transport

import com.crvdiag.transport.TrafficLog.Direction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.ZoneOffset

class TrafficLogTest {
    private var now = 1_000L
    private val log = TrafficLog(capacity = 3, clock = { now }, zone = ZoneOffset.UTC)

    @Test
    fun `export escapes control characters and keeps order`() {
        log.add(Direction.TX, "ATZ\r")
        now = 1_250L
        log.add(Direction.RX, "\r\rELM327 v1.5\r\r>")
        assertEquals(
            "00:00:01.000 TX ATZ\\r\n00:00:01.250 RX \\r\\rELM327 v1.5\\r\\r>",
            log.export(),
        )
    }

    @Test
    fun `oldest entries are dropped beyond capacity`() {
        listOf("a", "b", "c", "d").forEach { log.add(Direction.INFO, it) }
        assertEquals(listOf("b", "c", "d"), log.entries.value.map { it.text })
    }

    @Test
    fun `sink receives every formatted line`() {
        val lines = mutableListOf<String>()
        val withSink = TrafficLog(clock = { 0L }, zone = ZoneOffset.UTC, sink = { lines += it })
        withSink.add(Direction.TX, "0100\r")
        assertEquals(listOf("00:00:00.000 TX 0100\\r"), lines)
    }
}
