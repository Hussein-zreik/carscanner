package com.crvdiag.obd

import com.crvdiag.elm.Elm327
import com.crvdiag.elm.ElmResult
import com.crvdiag.transport.FakeTransport
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/** End-to-end on REAL car data: the 2026-11-17 recording replayed through the driver. */
class ReplayTest {
    private val recording = File("../testdata/2026-11-17_wifi_first-contact.log").readText()

    @Test
    fun `real recording replays through Elm327 into typed results and two ECUs`() = runTest {
        val car = FakeTransport.fromText(recording)
        val elm = Elm327(car, ConsolePolicy).also { it.start(backgroundScope) }

        assertEquals(ElmResult.Ok(listOf("ELM327 v1.5")), elm.execute("ATI"))
        assertEquals(ElmResult.Ok(listOf("12.1V")), elm.execute("ATRV"))
        assertEquals(ElmResult.Ok(listOf("11.8V")), elm.execute("ATRV")) // second recorded reply

        val pid00 = elm.execute("0100") as ElmResult.Ok
        val ecus = EcuDiscovery.fromPid00(pid00.lines, protocol = null)
        assertEquals(2, ecus.size)
        assertEquals(18, ecus.maxOf { it.supportedPids.size }) // engine side
        assertEquals(10, ecus.minOf { it.supportedPids.size }) // second unit

        assertEquals(ElmResult.Error("?"), elm.execute("ATE0")) // never recorded → clone-style "?"
    }
}
