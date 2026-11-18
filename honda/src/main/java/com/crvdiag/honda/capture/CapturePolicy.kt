package com.crvdiag.honda.capture

import com.crvdiag.elm.AdapterCommandPolicy
import com.crvdiag.elm.CommandPolicy
import com.crvdiag.elm.Verdict

/**
 * SAFETY: allow-list for capture mode ONLY (owner-approved 2026-11-18, "Approve (Recommended)").
 *
 * In capture mode the tablet relays another app's requests (Car Scanner on the owner's iPhone)
 * to the adapter so Honda's request IDs can be recorded (CLAUDE.md: never invent them).
 * Only requests that READ pass; everything else is blocked, answered "?" and logged:
 *
 * - Adapter AT commands that only change the adapter's own settings, including headers and
 *   receive filters (ATSH/ATCRA/ATCF/ATCM/flow control) so requests can be addressed to one
 *   unit. Still blocked: ATPP/ATSD (adapter EEPROM writes), ATMA/ATFI/ATSI/ATBI (bus monitor /
 *   forced init), ATBRD/ATLP (baud rate / low power), ATCSM, any AT command not listed.
 * - Read-only diagnostic services:
 *   - SAE J1979: 01, 02, 03, 06, 07, 09, 0A
 *   - KWP2000 (ISO 14230): 12 read freeze frame, 13 read codes, 17/18 read code status,
 *     1A read ECU identification, 21 read data by local identifier
 *   - KWP2000/UDS: 22 read data by identifier; UDS 19 read code information
 * - Blocked services include 04/14 (clear codes), 10 (session control), 11 (reset), 27 (security
 *   access), 2E/3B (write data), 2F/30/31 (actuator tests / routines), 23 (memory read), 34–37
 *   (download/upload), 3D (write memory), 28/85 (communication control), 3E (tester present).
 *
 * This policy is NEVER used for the app's own requests: those keep the normal allow-list
 * (ConsolePolicy) and, on this 29-bit car, never change the header.
 */
object CapturePolicy : CommandPolicy {
    val READ_SERVICES: Set<Int> = setOf(
        0x01, 0x02, 0x03, 0x06, 0x07, 0x09, 0x0A,
        0x12, 0x13, 0x17, 0x18, 0x19, 0x1A, 0x21, 0x22,
    )

    private val extraAt = setOf(
        "ATCAF0", "ATCAF1", "ATCFC0", "ATCFC1", "ATAR", "ATAL", "ATNL", "ATCS", "ATR0", "ATR1",
        "ATV0", "ATV1", "ATD0", "ATD1", "ATM0", "ATM1", "ATKW0", "ATKW1", "ATIB10", "ATIB48", "ATIB96",
        "ATDM1", "ATRD", "ATBD", "ATSS", "ATJE", "ATJS", "ATJHF0", "ATJHF1", "ATPBRESET",
    )
    private val extraPatterns = listOf(
        Regex("ATSH([0-9A-F]{3}|[0-9A-F]{6}|[0-9A-F]{8})"), // request header (11/29-bit)
        Regex("ATCRA([0-9A-FX]{3}|[0-9A-FX]{8})?"), // receive address filter
        Regex("ATCF[0-9A-F]{3}([0-9A-F]{5})?"), // CAN ID filter
        Regex("ATCM[0-9A-F]{3}([0-9A-F]{5})?"), // CAN ID mask
        Regex("ATCP[0-9A-F]{2}"), // CAN priority bits (29-bit)
        Regex("ATCEA([0-9A-F]{2})?"), // CAN extended address
        Regex("ATFCSH[0-9A-F]{3,8}"), Regex("ATFCSD[0-9A-F]{2,10}"), Regex("ATFCSM[0-2]"), // flow control
        Regex("ATSR[0-9A-F]{2}"), Regex("ATTA[0-9A-F]{2}"), // receive / tester address
        Regex("ATSW[0-9A-F]{2}"), Regex("ATWM[0-9A-F]{2,12}"), // K-line wakeup interval / message
        Regex("ATH[01]"), Regex("ATS[01]"),
    )
    private val hex = Regex("([0-9A-F]{2})+")

    override fun check(command: String): Verdict {
        if (command.startsWith("AT")) {
            if (AdapterCommandPolicy.check(command) == Verdict.Allowed) return Verdict.Allowed
            return if (command in extraAt || extraPatterns.any { it.matches(command) }) Verdict.Allowed
            else Verdict.Refused("AT command not allowed in capture mode")
        }
        if (command.startsWith("ST")) return Verdict.Refused("STN commands not allowed in capture mode")
        if (!hex.matches(command)) return Verdict.Refused("Not a hex request")
        val service = command.take(2).toInt(16)
        return if (service in READ_SERVICES) Verdict.Allowed
        else Verdict.Refused("Service %02X can change module state: blocked in capture mode".format(service))
    }
}
