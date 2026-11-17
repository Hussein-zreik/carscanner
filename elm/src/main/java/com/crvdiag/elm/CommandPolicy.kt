package com.crvdiag.elm

/** Outcome of checking one command against an allow-list. */
sealed interface Verdict {
    data object Allowed : Verdict
    data class Refused(val reason: String) : Verdict
    /** This policy does not cover the command; another policy may. */
    data object NotApplicable : Verdict
}

/**
 * SAFETY: an allow-list for commands sent to the adapter. Anything not explicitly allowed
 * is refused before it reaches the transport. Never bypass, even for testing.
 * Commands are checked in normalized form (see [normalize]).
 */
fun interface CommandPolicy {
    fun check(command: String): Verdict

    companion object {
        /** ELM327 ignores spaces and case: "at z" == "ATZ", "01 0c" == "010C". */
        fun normalize(raw: String): String = raw.filterNot { it.isWhitespace() }.uppercase()
    }
}

/** First policy that covers the command decides; if none does, the command is refused. */
class CompositePolicy(private vararg val policies: CommandPolicy) : CommandPolicy {
    override fun check(command: String): Verdict {
        if (command.isEmpty()) return Verdict.Refused("Empty command")
        for (policy in policies) {
            val verdict = policy.check(command)
            if (verdict != Verdict.NotApplicable) return verdict
        }
        return Verdict.Refused("Not on the allow-list")
    }
}

/**
 * AT commands that only change the adapter's own behaviour or read its status, plus ATSH
 * limited to the standard OBD-II CAN request IDs (approved by the owner 2026-11-17):
 * 7DF = functional broadcast, 7E0–7E7 = physical requests to ECU #1–#8.
 * Blocked on purpose: any other ATSH value, ATCRA, ATPP/ATSD (adapter EEPROM writes),
 * ATMA/ATFI/ATBI etc. (bus monitoring/init; added later when a session needs them).
 */
object AdapterCommandPolicy : CommandPolicy {
    private val exact = setOf(
        "ATZ", "ATWS", "ATD", "ATI", "AT@1", "ATRV", "ATDP", "ATDPN", "ATPC",
        "ATE0", "ATE1", "ATL0", "ATL1", "ATS0", "ATS1", "ATH0", "ATH1",
        "ATAT0", "ATAT1", "ATAT2",
    )
    private val patterns = listOf(
        Regex("ATSP[0-9A-C]"), // set protocol (0 = auto)
        Regex("ATTP[0-9A-C]"), // try protocol
        Regex("ATST[0-9A-F]{2}"), // response timeout
        Regex("ATSH7(DF|E[0-7])"), // standard OBD-II request IDs only
    )

    override fun check(command: String): Verdict = when {
        !command.startsWith("AT") -> Verdict.NotApplicable
        command in exact || patterns.any { it.matches(command) } -> Verdict.Allowed
        else -> Verdict.Refused("AT command not on the adapter allow-list")
    }
}
