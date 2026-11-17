package com.crvdiag.obd

import com.crvdiag.elm.CommandPolicy
import com.crvdiag.elm.Verdict

/**
 * SAFETY: standard SAE J1979 *read* services only.
 * - 01 current data (1–6 PIDs, optional trailing ELM response-count digit)
 * - 02 freeze frame (PID + frame pairs)
 * - 03 stored DTCs, 07 pending DTCs (no data bytes)
 * - 09 vehicle info (one PID)
 * Mode 04 (clear DTCs) is refused here; it is only allowed from the Clear Codes flow with a
 * confirmation dialog, engine OFF / ignition ON. Every other service (incl. 22) is refused.
 */
object StandardObdReadPolicy : CommandPolicy {
    private val hex = Regex("[0-9A-F]+")
    private val allowed = listOf(
        Regex("01([0-9A-F]{2}){1,6}[1-9A-F]?"),
        Regex("02([0-9A-F]{4}){1,3}"),
        Regex("03"),
        Regex("07"),
        Regex("09[0-9A-F]{2}"),
    )

    override fun check(command: String): Verdict {
        if (command.startsWith("AT") || !hex.matches(command)) return Verdict.NotApplicable
        if (command.length < 2) return Verdict.Refused("Incomplete OBD request")
        return when {
            allowed.any { it.matches(command) } -> Verdict.Allowed
            command.startsWith("04") ->
                Verdict.Refused("Mode 04 clears codes: only from the Clear Codes screen with confirmation")
            else -> Verdict.Refused("Service ${command.take(2)} / format not on the allow-list")
        }
    }
}

/** Policy for the raw console: adapter-only AT commands + standard OBD reads. Nothing else. */
val ConsolePolicy: CommandPolicy = com.crvdiag.elm.CompositePolicy(
    com.crvdiag.elm.AdapterCommandPolicy,
    StandardObdReadPolicy,
)
