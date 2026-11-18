package com.crvdiag.obd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuidedTestsTest {
    private fun titles(v: TestVerdict) = v.findings.map { it.title }

    // ---------------- Battery ----------------
    @Test
    fun `healthy battery and alternator`() {
        val v = BatteryAnalysis.evaluate(List(5) { 12.65 }, false, listOf(12.6, 10.4, 10.2, 11.8, 13.9), List(5) { 14.1 }, List(5) { 14.0 }, 2500.0)
        assertEquals("Battery and charging look healthy", v.headline)
    }

    @Test
    fun `this car's low key-on voltage and a weak crank`() {
        // 12.1 V key-on (owner's reading), crank dips to 9.3 V.
        val v = BatteryAnalysis.evaluate(List(5) { 12.1 }, true, listOf(12.1, 9.4, 9.3, 11.0), List(5) { 14.0 }, List(5) { 13.9 }, 2400.0)
        assertTrue(v.needsAttention)
        assertTrue(v.findings.first { it.title == "Battery charge" }.detail.contains("ignition ON"))
        assertEquals(Finding.Watch, v.findings.first { it.title == "Cranking" }.level)
    }

    @Test
    fun `Honda controlled alternator - 13 V at idle is not called a fault`() {
        val v = BatteryAnalysis.evaluate(emptyList(), false, emptyList(), List(5) { 13.0 }, List(5) { 14.1 }, 2500.0)
        assertEquals(Finding.Info, v.findings.first { it.title == "Charging at idle" }.level)
        assertEquals(Finding.Good, v.findings.first { it.title == "Charging with load" }.level)
        assertFalse(v.needsAttention)
    }

    @Test
    fun `no cranking dip seen is explained`() {
        val v = BatteryAnalysis.evaluate(List(3) { 12.5 }, false, listOf(12.5, 12.4, 12.5), emptyList(), emptyList(), null)
        assertTrue(v.findings.first { it.title == "Cranking" }.detail.contains("No cranking dip"))
    }

    // ---------------- Fuel trims ----------------
    private fun phase(st: Double, lt: Double, rpm: Double, coolant: Double = 88.0) =
        FuelTrimAnalysis.Phase(List(20) { st }, List(20) { lt }, List(20) { rpm }, coolant, List(20) { 3.0 })

    @Test
    fun `vacuum leak pattern - lean at idle, better at 2500`() {
        val v = FuelTrimAnalysis.evaluate(phase(6.0, 9.0, 750.0), phase(1.0, 4.0, 2500.0))
        assertEquals("Lean at idle, better at 2,500 rpm", v.headline)
        assertTrue(v.findings.first().detail.contains("vacuum"))
    }

    @Test
    fun `fuel delivery pattern - lean everywhere`() {
        assertEquals("Lean at idle and at 2,500 rpm", FuelTrimAnalysis.evaluate(phase(6.0, 9.0, 750.0), phase(5.0, 9.0, 2500.0)).headline)
    }

    @Test
    fun `purge-valve pattern - rich at idle, better at 2500`() {
        val v = FuelTrimAnalysis.evaluate(phase(-9.0, -5.0, 750.0), phase(-2.0, -3.0, 2500.0))
        assertTrue(v.findings.any { it.detail.contains("purge valve") })
    }

    @Test
    fun `this car's trims are normal`() {
        // LTFT -2.34 % as read by Car Scanner on 2026-11-17.
        val v = FuelTrimAnalysis.evaluate(phase(1.0, -2.34, 750.0), phase(0.5, -2.34, 2500.0))
        assertEquals("Fuel mixture is normal", v.headline)
    }

    // ---------------- EVAP ----------------
    private val purgePassed = listOf(MonitorTest(0x3D, 0xB9, 0x30, 40, 10, 200))
    private val purgeNotRun = listOf(MonitorTest(0x3D, 0xB9, 0x30, 0, 0, 0))

    @Test
    fun `purge flow seen when trims drop with purge`() {
        val s = List(15) { EvapAnalysis.Sample(0.0, 2.0, -2.0) } + List(15) { EvapAnalysis.Sample(40.0, -6.0, -2.0) }
        val v = EvapAnalysis.evaluate(s, purgePassed, 88.0)
        assertEquals("Purge valve works right now", v.headline)
    }

    @Test
    fun `no response to commanded purge points to a stuck-closed valve or blocked hose`() {
        val s = List(15) { EvapAnalysis.Sample(0.0, 1.0, -2.0) } + List(15) { EvapAnalysis.Sample(45.0, 1.0, -2.0) }
        val v = EvapAnalysis.evaluate(s, purgeNotRun, 88.0)
        assertEquals("No response to purge", v.headline)
        assertTrue(v.findings.any { it.title == "Car's purge-flow self-test" && "Not run yet" in it.detail })
    }

    @Test
    fun `without a purge PID - rich idle suggests stuck open, said honestly`() {
        val s = List(30) { EvapAnalysis.Sample(null, -8.0, -6.0) }
        val v = EvapAnalysis.evaluate(s, emptyList(), 88.0)
        assertTrue("Commanded purge not reported" in titles(v))
        assertEquals("Engine running rich at idle", v.headline)
    }

    // ---------------- Mode 06 catalogue ----------------
    @Test
    fun `not-run placeholders exactly as Car Scanner showed on this car`() {
        assertTrue(Mode06Catalog.notRun(MonitorTest(0x21, 0xA1, 0x0B, 0, 0, 0))) // catalyst 0 V (Min 0 Max 0)
        assertTrue(Mode06Catalog.notRun(MonitorTest(0x02, 0x55, 0x01, 21845, 21760, 0))) // 21845 (Min 21760 Max 0)
        assertFalse(Mode06Catalog.notRun(MonitorTest(0x01, 0x87, 0x01, 38, 0, 250))) // a real passed test
        assertEquals("EVAP purge flow", Mode06Catalog.name(0x3D))
        assertEquals("Misfire cylinder 3", Mode06Catalog.name(0xA4))
    }

    @Test
    fun `supported MIDs mask decodes`() {
        val msg = EcuMessage("18DAF111", listOf(0x46, 0x00, 0xC0, 0x00, 0x80, 0x01))
        assertEquals(setOf(0x01, 0x02, 0x11, 0x20), Mode06Catalog.supportedMids(msg, 0x00))
    }
}

class SelfTestReaderTest {
    @Test
    fun `discovers and reads monitors on 29-bit, read-only`() = kotlinx.coroutines.test.runTest {
        // SYNTHETIC 29-bit replies. 0600 → MIDs 01, 02, 20 (more); 0620 → 21, 3D; no further range.
        val car = FakeCar(mapOf(
            "0600" to "18DAF1110646 00C0000001\r\r",
            "0620" to "18DAF111064620 80000008\r\r",
            "0601" to "18DAF111 10 13 46 01 87 01 00 26\r18DAF111 21 00 00 00 FA 01 80 10\r18DAF111 22 30 D4 00 00 9C 40 00\r\r",
            "0602" to "18DAF111 10 13 46 02 9A 0B 00 68\r18DAF111 21 00 00 04 F6 02 55 01\r18DAF111 22 55 55 55 00 00 00 00\r\r",
            "0621" to "18DAF11110 0A46 21A10B0000\r18DAF11121 000000000000 00\r\r",
            "063D" to "18DAF11110 0A46 3DB9300000\r18DAF11121 000000000000 00\r\r",
        ))
        val elm = com.crvdiag.elm.Elm327(car, ConsolePolicy).also { it.start(backgroundScope) }
        val results = SelfTestReader(elm, com.crvdiag.elm.ElmProtocol.CAN_29_500, true, "18DAF111").readAll()
        assertEquals(listOf(0x01, 0x02, 0x21, 0x3D), results.map { it.mid })
        assertEquals("Passed", results[0].status) // 0x26=38 within 0..250 (kOhm-type test seen on this car)
        assertEquals("Not run yet", results.first { it.mid == 0x21 }.status)
        assertEquals("Not run yet", results.first { it.mid == 0x3D }.status)
        assertTrue(car.sent.all { it.startsWith("06") }, "only service 06 requests: ${car.sent}")
    }
}
