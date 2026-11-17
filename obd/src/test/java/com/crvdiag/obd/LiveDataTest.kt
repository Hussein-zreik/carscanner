package com.crvdiag.obd

import com.crvdiag.elm.Elm327
import com.crvdiag.elm.ElmProtocol
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LiveDataTest {
    // SYNTHETIC engine replies in the clone's format (headers on, 7E8). Values chosen to match
    // a warm idle: 850 rpm, 0 km/h, 85 °C. TODO(capture): replace with a real recording.
    private val engine = mapOf(
        "ATSH7E0" to "OK\r\r",
        "ATSH7DF" to "OK\r\r",
        "010C0D" to "7E806410C0D480D00\r\r",
        // 8-byte answer → ISO-TP first frame + consecutive frame
        "010C0D05" to "7E81008410C0D480D00\r7E821057D0000000000\r\r",
        "0100" to "7E8064100BE3FA813\r\r",
        "0120" to "7E80641208007B011\r\r",
        "0140" to "7E806414054000000\r\r",
    )

    private fun TestScope.session(replies: Map<String, String>): Pair<FakeCar, LiveDataSession> {
        val fake = FakeCar(replies)
        val elm = Elm327(fake, ConsolePolicy).also { it.start(backgroundScope) }
        return fake to LiveDataSession(elm, ElmProtocol.CAN_11_500, headersOn = true, requestId = "7E0", clock = { 0L })
    }

    @Test
    fun `multi-PID request is split into values, incl multi-frame replies`() = runTest {
        val (fake, live) = session(engine)
        assertTrue(live.begin())
        val sample = live.sample(listOf(0x0C, 0x0D, 0x05))
        assertEquals(listOf("ATSH7E0", "010C0D05"), fake.sent)
        assertEquals(850.0, sample.values.getValue(0x0C).value)
        assertEquals(0.0, sample.values.getValue(0x0D).value)
        assertEquals(85.0, sample.values.getValue(0x05).value)
        assertTrue(sample.missed.isEmpty())
        live.end()
        assertEquals("ATSH7DF", fake.sent.last())
    }

    @Test
    fun `falls back to one PID per request when multi-PID fails`() = runTest {
        val (fake, live) = session(engine + mapOf(
            "010C0D05" to "NO DATA\r\r",
            "010C" to "7E804410C0D48\r\r",
            "010D" to "7E803410D00\r\r",
            "0105" to "7E80341057D\r\r",
        ))
        val sample = live.sample(listOf(0x0C, 0x0D, 0x05))
        assertFalse(live.multiPidWorks)
        assertEquals(listOf("010C0D05", "010C", "010D", "0105"), fake.sent)
        assertEquals(setOf(0x0C, 0x0D, 0x05), sample.values.keys)
    }

    @Test
    fun `supported PIDs follow the 0100 - 0120 - 0140 chain`() = runTest {
        val (_, live) = session(engine)
        val pids = live.supportedPids()
        assertTrue(pids.containsAll(listOf(0x0C, 0x10, 0x15))) // from the real 0100 mask
        assertTrue(pids.containsAll(listOf(0x21, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x3C, 0x40)))
        assertTrue(pids.containsAll(listOf(0x42, 0x44, 0x46)))
    }

    @Test
    fun `missing replies are reported, not invented`() = runTest {
        val (_, live) = session(engine + ("010C0D" to "NO DATA\r\r") + ("010C" to "NO DATA\r\r") + ("010D" to "NO DATA\r\r"))
        val sample = live.sample(listOf(0x0C, 0x0D))
        assertTrue(sample.values.isEmpty())
        assertEquals(setOf(0x0C, 0x0D), sample.missed)
    }

    @Test
    fun `new PID formulas`() {
        assertEquals(14.2, Pids.decode(0x42, listOf(0x37, 0x78))!!.value!!, 0.001) // 14.200 V
        assertEquals(1.0, Pids.decode(0x34, listOf(0x80, 0x00, 0x80, 0x00))!!.value!!, 0.001) // λ 1.00
        assertEquals(0.70, Pids.decode(0x15, listOf(0x8C, 0xFF))!!.value!!, 0.001) // 0.70 V
        assertEquals(400.0, Pids.decode(0x3C, listOf(0x11, 0x30))!!.value!!, 0.001) // 400 °C
        assertEquals(50.2, Pids.decode(0x2E, listOf(0x80))!!.value!!, 0.1) // purge 50 %
    }
}

class LiveChecksTest {
    private fun warmIdle(extra: Map<Int, List<Double>>) =
        mapOf(0x0C to listOf(750.0), 0x0D to listOf(0.0), 0x05 to listOf(88.0)) + extra

    private fun check(history: Map<Int, List<Double>>, title: String) =
        LiveChecks.evaluate(history).first { it.title == title }

    @Test
    fun `fuel trims - this car's -2_34 percent is good`() {
        assertEquals(CheckResult.Good, check(warmIdle(mapOf(0x06 to listOf(1.6), 0x07 to listOf(-2.34))), "Fuel trims").result)
        val lean = check(warmIdle(mapOf(0x06 to listOf(8.0), 0x07 to listOf(14.0))), "Fuel trims")
        assertEquals(CheckResult.Watch, lean.result)
        assertTrue("ADDING" in lean.detail)
    }

    @Test
    fun `checks wait for the right conditions`() {
        val engineOff = mapOf(0x0C to listOf(0.0), 0x05 to listOf(85.0), 0x06 to listOf(0.0), 0x07 to listOf(-2.34), 0x42 to listOf(12.1))
        assertEquals(CheckResult.Waiting, check(engineOff, "Fuel trims").result)
        assertEquals(CheckResult.Waiting, check(engineOff, "Charging voltage").result)
    }

    @Test
    fun `charging voltage`() {
        assertEquals(CheckResult.Good, check(warmIdle(mapOf(0x42 to listOf(14.2))), "Charging voltage").result)
        assertEquals(CheckResult.Watch, check(warmIdle(mapOf(0x42 to listOf(12.6))), "Charging voltage").result)
    }

    @Test
    fun `downstream O2 - steady is good, switching is watch`() {
        val steady = List(20) { 0.70 + (it % 3) * 0.02 }
        val switching = List(20) { if (it % 2 == 0) 0.1 else 0.85 }
        assertEquals(CheckResult.Good, check(warmIdle(mapOf(0x15 to steady)), "Downstream O2 sensor").result)
        assertEquals(CheckResult.Watch, check(warmIdle(mapOf(0x15 to switching)), "Downstream O2 sensor").result)
        assertEquals(CheckResult.Waiting, check(warmIdle(mapOf(0x15 to steady.take(5))), "Downstream O2 sensor").result)
    }

    @Test
    fun `slow warm-up suggests thermostat`() {
        val h = mapOf(0x0C to listOf(1800.0), 0x0D to listOf(60.0), 0x05 to listOf(62.0), 0x1F to listOf(1200.0))
        assertTrue("P0128" in check(h, "Engine temperature").detail)
    }
}
