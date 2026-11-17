package com.crvdiag.elm

import com.crvdiag.transport.RecordedLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

class ElmResponseParserTest {

    /** Frames every exchange of the real 2026-11-17 recording and parses it. */
    private val recorded: List<Pair<String, ElmResult>> by lazy {
        val text = File("../testdata/2026-11-17_wifi_first-contact.log").readText()
        RecordedLog.exchanges(RecordedLog.parse(text)).map { ex ->
            val framer = PromptFramer()
            val frame = ex.replyChunks.firstNotNullOf { framer.append(it) }
            val command = ex.command.removeSuffix("\r")
            command to ElmResponseParser.parse(command, frame)
        }
    }

    @Test
    fun `real car recording parses into clean results`() {
        val version = ElmResult.Ok(listOf("ELM327 v1.5"))
        val twoEcus = ElmResult.Ok(listOf("41 00 98 3A 80 03", "41 00 BE 3F A8 13"))
        assertEquals(
            listOf(
                "ATZ" to version, // echo was off before this reset
                "ATI" to version, // echo on: "ATI" line removed
                "ATRV" to ElmResult.Ok(listOf("12.1V")),
                "ATDPN" to ElmResult.Ok(listOf("A0")),
                "0100" to twoEcus, // echo + SEARCHING... + trailing spaces handled
                "ATZ" to version,
                "ATI" to version,
                "ATRV" to ElmResult.Ok(listOf("11.8V")),
                "ATDPN" to ElmResult.Ok(listOf("A0")),
                "0100" to twoEcus,
            ),
            recorded,
        )
    }

    @Test
    fun `NO DATA becomes NoData`() {
        assertEquals(ElmResult.NoData, ElmResponseParser.parse("0100", "0100\rSEARCHING...\rNO DATA\r\r"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["?", "CAN ERROR", "BUS INIT: ...ERROR", "UNABLE TO CONNECT", "STOPPED", "BUFFER FULL", "ERR94"])
    fun `adapter errors become Error`(reply: String) {
        assertEquals(ElmResult.Error(reply), ElmResponseParser.parse("0100", "$reply\r\r"))
    }

    @Test
    fun `prompt framer joins split chunks and stops at the prompt`() {
        val framer = PromptFramer()
        assertNull(framer.append("0100\r"))
        assertNull(framer.append("SEARCHING...\r"))
        assertEquals("0100\rSEARCHING...\r41 00 BE 3F A8 13 \r\r", framer.append("41 00 BE 3F A8 13 \r\r>"))
    }

    @Test
    fun `ATDPN replies decode`() {
        assertEquals(DetectedProtocol(ElmProtocol.CAN_11_500, automatic = true), ElmProtocol.parseDpn("A6"))
        assertEquals(DetectedProtocol(ElmProtocol.CAN_11_500, automatic = false), ElmProtocol.parseDpn("6"))
        assertEquals(DetectedProtocol(ElmProtocol.AUTO, automatic = true), ElmProtocol.parseDpn("A0"))
        assertNull(ElmProtocol.parseDpn("?"))
    }
}
