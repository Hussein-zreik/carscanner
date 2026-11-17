package com.crvdiag.elm

import com.crvdiag.transport.Transport

/**
 * The only path from user/feature code to the adapter. Normalizes the command, checks it
 * against [policy], and sends it (with the ELM '\r' terminator) only if allowed.
 */
class GuardedSender(
    private val transport: Transport,
    private val policy: CommandPolicy,
    private val onRefused: (command: String, reason: String) -> Unit = { _, _ -> },
) {
    suspend fun send(raw: String): Verdict {
        val command = CommandPolicy.normalize(raw)
        val verdict = policy.check(command)
        when (verdict) {
            Verdict.Allowed -> transport.send(command + "\r")
            is Verdict.Refused -> onRefused(command, verdict.reason)
            Verdict.NotApplicable -> error("Policies must not return NotApplicable at the top level")
        }
        return verdict
    }
}
