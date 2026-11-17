package com.crvdiag.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class RecordedLogTest {
    private val recording = File("../testdata/2026-11-17_wifi_first-contact.log").readText()

    @Test
    fun `escape and unescape are inverse`() {
        val raw = "\r\rELM327 v1.5\r\r>\u0001"
        assertEquals(raw, RecordedLog.unescape(TrafficLog.escape(raw)))
    }

    @Test
    fun `real recording groups replies under their command`() {
        val exchanges = RecordedLog.exchanges(RecordedLog.parse(recording))
        assertEquals(
            listOf("ATZ\r", "ATI\r", "ATRV\r", "ATDPN\r", "0100\r", "ATZ\r", "ATI\r", "ATRV\r", "ATDPN\r", "0100\r"),
            exchanges.map { it.command },
        )
        assertEquals("0100\rSEARCHING...\r41 00 98 3A 80 03 \r41 00 BE 3F A8 13 \r\r>", exchanges[4].reply)
    }
}
