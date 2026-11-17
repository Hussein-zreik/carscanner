package com.crvdiag.obd

import com.crvdiag.elm.CommandPolicy
import com.crvdiag.elm.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ConsolePolicyTest {

    @ParameterizedTest
    @ValueSource(strings = ["ATZ", "at i", "0100", "01 0C", "010C0D", "010C1", "020C00", "03", "07", "0902", "ATDPN", "ATSH7E0", "AT SH 7DF", "06A2", "0600", "0A"])
    fun `standard reads and adapter commands are allowed`(raw: String) {
        assertEquals(Verdict.Allowed, ConsolePolicy.check(CommandPolicy.normalize(raw)))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "04", // clear DTCs: only via confirmation flow
            "06", // mode 06 needs a MID
            "0AFF", // mode 0A takes no data bytes
            "22F190", // Honda/UDS read by ID: TODO(capture), not allowed yet
            "2E0101", // UDS write data by ID
            "3101FF00", // routine control
            "1003", // diagnostic session control
            "2701", // security access
            "1101", // ECU reset
            "ATSH7C0", // non-standard header
            "ATSH18DAF110", // 29-bit header
            "03FF", // mode 03 takes no data bytes
            "01", // missing PID
            "HELLO",
            "0",
        ],
    )
    fun `everything else is refused`(raw: String) {
        assertTrue(ConsolePolicy.check(CommandPolicy.normalize(raw)) is Verdict.Refused)
    }
}
