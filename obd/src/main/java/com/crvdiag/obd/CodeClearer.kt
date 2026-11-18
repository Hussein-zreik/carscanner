package com.crvdiag.obd

import com.crvdiag.elm.AdapterCommandPolicy
import com.crvdiag.elm.CommandPolicy
import com.crvdiag.elm.CompositePolicy
import com.crvdiag.elm.Elm327
import com.crvdiag.elm.ElmProtocol
import com.crvdiag.elm.ElmResult
import com.crvdiag.elm.Verdict
import kotlinx.coroutines.delay

/**
 * SAFETY: the only policy that allows mode 04. Internal to this module so nothing else
 * (console, other screens) can send a clear. Used only by [CodeClearer].
 */
internal val ClearCodesPolicy: CommandPolicy = CompositePolicy(
    CommandPolicy { if (it == "04") Verdict.Allowed else Verdict.NotApplicable },
    AdapterCommandPolicy,
    StandardObdReadPolicy,
)

sealed interface ClearOutcome {
    /**
     * [ecus] acknowledged (positive response 0x44); [rejected] = ECU → reason.
     * [confirmedByReread]: the units only answered "busy, answer follows" (NRC 0x78) and the
     * clear was confirmed by reading readiness again (MIL off, 0 stored codes).
     */
    data class Cleared(
        val ecus: List<String>,
        val rejected: Map<String, String>,
        val confirmedByReread: Boolean = false,
    ) : ClearOutcome
    /** Not sent: a precondition failed. */
    data class Refused(val reason: String) : ClearOutcome
    /** Sent but no ECU confirmed. */
    data class Failed(val reason: String) : ClearOutcome
}

/**
 * Clears emission-related codes (SAE J1979 mode 04). The UI must show a confirmation dialog
 * first; this class additionally enforces CLAUDE.md rule 4: engine OFF, ignition ON — checked
 * by reading RPM (010C), which must answer and be 0 from every ECU.
 */
class CodeClearer(
    private val elm: Elm327,
    private val protocol: ElmProtocol?,
    private val headersOn: Boolean,
) {
    suspend fun clear(): ClearOutcome {
        val rpmReply = elm.obdRequest("010C", protocol, headersOn, policy = ClearCodesPolicy)
        if (rpmReply !is ElmResult.Ok) {
            return ClearOutcome.Refused("Car did not answer the engine-off check. Ignition ON (engine OFF), then retry.")
        }
        val rpms = ObdMessages.collect(rpmReply.lines, protocol, headersOn).mapNotNull { m ->
            val p = m.payload
            if (p.size >= 4 && p[0] == 0x41 && p[1] == 0x0C) Pids.decode(0x0C, p.drop(2))?.value else null
        }
        if (rpms.isEmpty()) return ClearOutcome.Refused("Could not read engine RPM. Not clearing.")
        rpms.maxOrNull()?.takeIf { it > 0.0 }?.let {
            return ClearOutcome.Refused("Engine is running (${it.toInt()} rpm). Turn the engine OFF, keep ignition ON.")
        }

        // Functional address so every emissions ECU receives the clear. On 29-bit CAN the default
        // header after ATZ is already functional and is never changed by the app.
        protocol?.functionalHeader11?.let { h ->
            val header = elm.execute("ATSH$h", policy = ClearCodesPolicy)
            if (header !is ElmResult.Ok) return ClearOutcome.Refused("Adapter rejected ATSH$h. Not clearing.")
        }

        // SAFETY: 04 is sent once, never retried (also not after "busy").
        val reply = elm.obdRequest("04", protocol, headersOn, timeoutMs = 5_000, retries = 0, policy = ClearCodesPolicy)
        if (reply !is ElmResult.Ok) {
            return ClearOutcome.Failed("No confirmation from the car ($reply). Read codes again to check.")
        }
        val messages = ObdMessages.collect(reply.lines, protocol, headersOn)
        val cleared = messages.filter { it.payload.firstOrNull() == 0x44 }.map { it.ecu }
        val negative = messages.filter { it.payload.size >= 3 && it.payload[0] == 0x7F && it.payload[1] == 0x04 }
        val busy = negative.filter { it.payload[2] == NRC_RESPONSE_PENDING }.map { it.ecu }
        val rejected = negative.filterNot { it.ecu in busy }.associate { it.ecu to negativeReason(it.payload[2]) }
        if (cleared.isNotEmpty()) return ClearOutcome.Cleared(cleared, rejected)

        // NRC 0x78 = "request received, answer follows" (not a refusal). On this car both units
        // answered only that and the clear had worked (2026-11-18). Confirm by reading again.
        if (busy.isNotEmpty()) {
            delay(VERIFY_DELAY_MS)
            val (_, after) = elm.obdRead("0101", protocol, headersOn)
            val readiness = after.mapNotNull { m -> Readiness.fromMessage(m)?.let { m.ecu to it } }.toMap()
            val stillSet = readiness.filterValues { it.milOn || it.dtcCount > 0 }
            return when {
                readiness.isEmpty() -> ClearOutcome.Failed(
                    "The car answered 'busy, wait' and then could not be read. Read codes again to check.",
                )
                stillSet.isEmpty() -> ClearOutcome.Cleared(busy, rejected, confirmedByReread = true)
                else -> ClearOutcome.Failed(
                    "The car answered 'busy, wait' and codes are still stored (" +
                        stillSet.entries.joinToString { "${it.key}: ${it.value.dtcCount} code(s)" } + ").",
                )
            }
        }
        return ClearOutcome.Failed(rejected.entries.joinToString { "${it.key}: ${it.value}" }.ifEmpty { "No ECU confirmed" })
    }

    companion object {
        /** ISO 14229 negative response code "requestCorrectlyReceived-ResponsePending". */
        const val NRC_RESPONSE_PENDING = 0x78
        const val VERIFY_DELAY_MS = 2_000L
    }

    private fun negativeReason(nrc: Int): String = when (nrc) {
        0x22 -> "conditions not correct (engine running?)"
        0x11 -> "service not supported"
        0x31 -> "request out of range"
        NRC_RESPONSE_PENDING -> "busy, answer pending"
        else -> "refused (code 0x%02X)".format(nrc)
    }
}
