package com.crvdiag.obd

import com.crvdiag.elm.CommandPolicy
import com.crvdiag.elm.Elm327
import com.crvdiag.elm.ElmProtocol
import com.crvdiag.elm.Verdict
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodeClearerTest {
    private val engineOff = "7E804410C0000\r7E904410C0000\r\r"
    private val ok = mapOf(
        "010C" to engineOff,
        "ATSH7DF" to "OK\r\r",
        "04" to "7E80144\r7E90144\r\r",
    )

    private suspend fun kotlinx.coroutines.test.TestScope.clear(replies: Map<String, String>): Pair<FakeCar, ClearOutcome> {
        val fake = FakeCar(replies)
        val elm = Elm327(fake, ConsolePolicy).also { it.start(backgroundScope) }
        return fake to CodeClearer(elm, ElmProtocol.CAN_11_500, headersOn = true).clear()
    }

    @Test
    fun `engine off - clears after RPM check, via functional address`() = runTest {
        val (fake, outcome) = clear(ok)
        assertEquals(listOf("010C", "ATSH7DF", "04"), fake.sent)
        assertEquals(ClearOutcome.Cleared(listOf("7E8", "7E9"), emptyMap()), outcome)
    }

    @Test
    fun `engine running - refused and 04 never sent`() = runTest {
        val (fake, outcome) = clear(ok + ("010C" to "7E804410C0D48\r\r"))
        assertTrue(outcome is ClearOutcome.Refused && "850 rpm" in outcome.reason)
        assertTrue("04" !in fake.sent)
    }

    @Test
    fun `no answer to the RPM check - refused`() = runTest {
        val (fake, outcome) = clear(ok + ("010C" to "NO DATA\r\r"))
        assertTrue(outcome is ClearOutcome.Refused)
        assertTrue("04" !in fake.sent)
    }

    @Test
    fun `ECU negative response is reported`() = runTest {
        val (_, outcome) = clear(ok + ("04" to "7E8037F0422\r\r"))
        assertTrue(outcome is ClearOutcome.Failed && "conditions not correct" in outcome.reason)
    }

    @Test
    fun `console policy can never send mode 04`() {
        assertTrue(ConsolePolicy.check(CommandPolicy.normalize("04")) is Verdict.Refused)
    }
}
