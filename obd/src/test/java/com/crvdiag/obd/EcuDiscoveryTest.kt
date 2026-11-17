package com.crvdiag.obd

import com.crvdiag.elm.ElmProtocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EcuDiscoveryTest {
    // Real masks from testdata/2026-11-17_wifi_first-contact.log
    private val engine = setOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x13, 0x15, 0x1C, 0x1F, 0x20)
    private val second = setOf(0x01, 0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x0F, 0x11, 0x1F, 0x20)

    @Test
    fun `decodes the real supported-PID masks`() {
        assertEquals(engine, SupportedPids.decode(0x00, listOf(0xBE, 0x3F, 0xA8, 0x13)))
        assertEquals(second, SupportedPids.decode(0x00, listOf(0x98, 0x3A, 0x80, 0x03)))
    }

    @Test
    fun `real headerless reply gives two ECUs without addresses`() {
        val ecus = EcuDiscovery.fromPid00(listOf("41 00 98 3A 80 03", "41 00 BE 3F A8 13"), protocol = null)
        assertEquals(listOf(EcuInfo(null, null, second), EcuInfo(null, null, engine)), ecus)
    }

    @Test
    fun `headered reply gives addresses (synthetic headers)`() {
        // TODO(capture): replace with a real ATH1 recording after the Session 3 car test.
        val ecus = EcuDiscovery.fromPid00(listOf("7E8064100BE3FA813", "7E9 06 41 00 98 3A 80 03"), ElmProtocol.CAN_11_500)
        assertEquals(listOf(EcuInfo("7E8", "7E0", engine), EcuInfo("7E9", "7E1", second)), ecus)
    }

    @Test
    fun `request IDs follow ISO 15765-4`() {
        assertEquals("7E0", EcuDiscovery.requestIdFor("7E8"))
        assertEquals("7E7", EcuDiscovery.requestIdFor("7EF"))
        assertEquals("18DA10F1", EcuDiscovery.requestIdFor("18DAF110"))
        assertEquals(null, EcuDiscovery.requestIdFor("7C8"))
    }
}
