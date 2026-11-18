package com.crvdiag.obd

import com.crvdiag.elm.Elm327
import com.crvdiag.elm.ElmProtocol
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MisfireTest {
    private fun snap(vararg current: Int?, t: Long = 0) =
        MisfireSnapshot(t, current.withIndex().associate { (i, v) -> i + 1 to v }, emptyMap())

    private val steadyIdle = List(40) { 750.0 + (it % 3) * 10 } // ±~8 rpm
    private val roughIdle = List(40) { if (it % 2 == 0) 680.0 else 820.0 } // ±70 rpm

    @Test
    fun `new misfires per cylinder, counter reset handled`() {
        assertEquals(mapOf(1 to 0, 2 to 5, 3 to 0, 4 to null), MisfireAnalysis.newMisfires(snap(0, 3, 2, null), snap(0, 8, 2, null)))
        // Counter went down = new driving cycle: count from zero.
        assertEquals(4, MisfireAnalysis.newMisfires(snap(10), snap(4))[1])
    }

    @Test
    fun `one cylinder misfiring points to its coil, plug or injector`() {
        val v = MisfireAnalysis.evaluate(snap(0, 0, 0, 0), snap(0, 0, 12, 0), roughIdle, listOf(1.0), -2.3, 88.0, emptyList())
        assertEquals("Misfire found on cylinder 3", v.headline)
        assertTrue(v.findings.any { it.title == "Misfire on cylinder 3" && "swap that coil" in it.detail })
        assertTrue(v.findings.any { it.title == "Idle speed is unsteady" })
    }

    @Test
    fun `random misfires with rich trims point to the purge valve (P145C)`() {
        val v = MisfireAnalysis.evaluate(snap(0, 0, 0, 0), snap(3, 2, 4, 1), roughIdle, listOf(-9.0), -6.0, 88.0, emptyList())
        assertEquals("Misfires found", v.headline)
        assertTrue(v.findings.any { it.title == "Engine running rich" && "purge valve" in it.detail })
    }

    @Test
    fun `lean trims point to an air leak`() {
        val v = MisfireAnalysis.evaluate(snap(0, 0, 0, 0), snap(0, 0, 0, 0), steadyIdle, listOf(6.0), 8.0, 88.0, emptyList())
        assertTrue(v.findings.any { it.title == "Engine running lean" && "vacuum" in it.detail })
        assertEquals("No misfires, but something needs attention", v.headline)
    }

    @Test
    fun `smooth data but shaky feel suggests mounts`() {
        val v = MisfireAnalysis.evaluate(snap(0, 0, 0, 0, t = 0), snap(0, 0, 0, 0, t = 1), steadyIdle, listOf(1.5), -2.3, 88.0, emptyList())
        assertTrue(v.findings.any { it.title == "If it still feels shaky" && "mounts" in it.detail })
        assertTrue(v.findings.any { it.title == "Idle speed is steady" })
    }

    @Test
    fun `cold engine and missing counters are said plainly`() {
        val v = MisfireAnalysis.evaluate(snap(null, null, null, null), snap(null, null, null, null), steadyIdle, emptyList(), null, 40.0, emptyList())
        assertEquals("Misfire counters not available", v.headline)
        assertTrue(v.findings.any { it.title == "Engine not warm yet" })
    }

    @Test
    fun `stored misfire codes always win`() {
        val v = MisfireAnalysis.evaluate(snap(0, 0, 0, 0), snap(0, 0, 0, 0), steadyIdle, listOf(0.0), 0.0, 88.0,
            MisfireAnalysis.misfireCodesIn(listOf("P145C", "P0302")))
        assertEquals("Misfires found", v.headline)
        assertTrue(v.findings.any { it.title == "Misfire codes stored" && "P0302" in it.detail })
    }

    @Test
    fun `reads counters from the engine on 29-bit, read-only`() = runTest {
        // SYNTHETIC 29-bit mode 06 replies (format of the real recording); cylinder 2 has 7 misfires this cycle.
        val car = FakeCar(mapOf(
            "06A2" to "18DAF1111013 46A20B24000000\r18DAF11121 00FFFFA20C2400 00\r18DAF1112200 00FFFF0000000000\r\r",
            "06A3" to "18DAF1111013 46A30B24000200\r18DAF11121 00FFFFA30C2400 07\r18DAF1112200 00FFFF0000000000\r\r",
            "06A4" to "NO DATA\r\r",
            "06A5" to "18DAF1111013 46A50B24000000\r18DAF11121 00FFFFA50C2400 00\r18DAF1112200 00FFFF0000000000\r\r",
        ))
        val elm = Elm327(car, ConsolePolicy).also { it.start(backgroundScope) }
        val s = MisfireReader(elm, ElmProtocol.CAN_29_500, headersOn = true, engineResponseId = "18DAF111", clock = { 5L }).read()
        assertEquals(mapOf(1 to 0, 2 to 7, 3 to null, 4 to 0), s.current)
        assertEquals(2, s.average10[2])
        // A dropped cylinder is asked up to 3 times (the clone drops replies), then reported unknown.
        assertEquals(listOf("06A2", "06A3", "06A4", "06A4", "06A4", "06A5"), car.sent)
    }
}
