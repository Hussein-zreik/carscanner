package com.crvdiag.obd

import com.crvdiag.elm.ElmProtocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DecodersTest {
    private val can = ElmProtocol.CAN_11_500

    @Test
    fun `DTC bytes decode per SAE J2012`() {
        assertEquals("P145C", Dtcs.decode(0x14, 0x5C)) // seen on this car (pending)
        assertEquals("P0133", Dtcs.decode(0x01, 0x33))
        assertEquals("P2195", Dtcs.decode(0x21, 0x95))
        assertEquals("C0123", Dtcs.decode(0x41, 0x23))
        assertEquals("B1234", Dtcs.decode(0x92, 0x34))
        assertEquals("U0100", Dtcs.decode(0xC1, 0x00))
        assertEquals(null, Dtcs.decode(0x00, 0x00))
    }

    @Test
    fun `mode 07 single frame with count byte`() {
        val msgs = ObdMessages.collect(listOf("7E8 04 47 01 14 5C"), can, headersOn = true)
        assertEquals(listOf("P145C"), Dtcs.fromMessage(msgs.single(), 0x47))
    }

    @Test
    fun `multi-frame mode 03 reply is reassembled per ECU`() {
        val lines = listOf("7E8 10 0A 43 04 01 33 01 71", "7E9 02 43 00", "7E8 21 04 20 14 5C 00 00 00")
        val msgs = ObdMessages.collect(lines, can, headersOn = true)
        assertEquals(listOf("7E9", "7E8"), msgs.map { it.ecu })
        assertEquals(emptyList<String>(), Dtcs.fromMessage(msgs[0], 0x43))
        assertEquals(listOf("P0133", "P0171", "P0420", "P145C"), Dtcs.fromMessage(msgs[1], 0x43))
    }

    @Test
    fun `headerless multi-frame reply (ELM numbered lines)`() {
        val lines = listOf("00A", "0: 43 04 01 33 01 71", "1: 04 20 14 5C 00 00 00")
        val msg = ObdMessages.collect(lines, can, headersOn = false).single()
        assertEquals(listOf("P0133", "P0171", "P0420", "P145C"), Dtcs.fromMessage(msg, 0x43))
    }

    @Test
    fun `readiness matches the Car Scanner report of 2026-11-17`() {
        // Bytes reconstructed from Car Scanner's decoded readiness list (not a raw recording):
        // MIL off, 0 codes; misfire/fuel/components complete; catalyst, EVAP, O2, EGR incomplete.
        val r = Readiness.decode(0x00, 0x07, 0xE5, 0xA5)
        assertFalse(r.milOn)
        assertEquals(0, r.dtcCount)
        assertFalse(r.compressionIgnition)
        assertEquals(
            listOf("Catalyst", "Evaporative system", "Oxygen sensor", "EGR system"),
            r.incomplete.map { it.name },
        )
        val byName = r.monitors.associateBy { it.name }
        assertTrue(byName.getValue("Misfire").let { it.available && it.complete })
        assertTrue(byName.getValue("Oxygen sensor heater").let { it.available && it.complete })
        assertFalse(byName.getValue("Heated catalyst").available)
        assertFalse(byName.getValue("Secondary air system").available)
    }

    @Test
    fun `PID formulas reproduce Car Scanner values of 2026-11-17`() {
        // Raw bytes back-calculated from Car Scanner's displayed values (engine off, warm).
        assertEquals(85.0, Pids.decode(0x05, listOf(0x7D))!!.value) // coolant 85 °C
        assertEquals(-2.34, Pids.decode(0x07, listOf(0x7D))!!.value!!, 0.01) // LTFT −2.34 %
        assertEquals(100.0, Pids.decode(0x0B, listOf(0x64))!!.value) // MAP 14.5 psi
        assertEquals(36.0, Pids.decode(0x0F, listOf(0x4C))!!.value) // IAT 36 °C
        assertEquals(16.86, Pids.decode(0x11, listOf(0x2B))!!.value!!, 0.01) // throttle 16.86 %
        assertEquals(0.46, Pids.decode(0x10, listOf(0x00, 0x2E))!!.value!!, 0.001) // MAF 0.46 g/s
        assertEquals(0.0, Pids.decode(0x0C, listOf(0x00, 0x00))!!.value) // RPM 0
        assertEquals(850.0, Pids.decode(0x0C, listOf(0x0D, 0x48))!!.value)
    }

    @Test
    fun `code descriptions never guess`() {
        assertEquals("EVAP system incorrect purge flow", DtcText.describe("P0441"))
        assertTrue(DtcText.describe("P145C").startsWith("Manufacturer-specific"))
        assertTrue(DtcText.describe("P0999").startsWith("Generic powertrain"))
    }
}
