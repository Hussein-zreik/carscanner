package com.crvdiag.elm

/** What the startup sequence learned about the adapter and the car. */
data class InitReport(
    /** e.g. "ELM327 v1.5" (clone versions are not trustworthy). */
    val adapterId: String?,
    /** ATRV reading at the OBD port, e.g. "12.1V"; approximate on clones. */
    val voltage: String?,
    val detected: DetectedProtocol?,
    /** Reply lines to 0100, one per answering ECU (headers on when ATH1 worked). */
    val pid00Lines: List<String>,
    val headersOn: Boolean,
    /** Non-fatal problems, e.g. the clone answered "?" to an optional command. */
    val warnings: List<String>,
)

sealed interface InitOutcome {
    data class Success(val report: InitReport) : InitOutcome
    data class Failure(val step: String, val result: ElmResult, val hint: String, val warnings: List<String>) : InitOutcome
}

/**
 * CLAUDE.md baseline: ATZ → ATE0 → ATL0 → ATS0 → ATH1 → ATSP → 0100 → ATDPN (+ ATRV for voltage).
 *
 * [protocol] is AUTO the first time. Once protocol 6 is confirmed it should be passed explicitly
 * so the adapter never auto-searches (auto-search can probe the K-line: SRS / VSA / immobilizer).
 */
class ElmInitializer(private val elm: Elm327) {

    suspend fun run(
        protocol: ElmProtocol = ElmProtocol.AUTO,
        onStep: (String) -> Unit = {},
    ): InitOutcome {
        val warnings = mutableListOf<String>()

        onStep("Resetting adapter (ATZ)")
        val reset = elm.execute("ATZ", timeoutMs = RESET_TIMEOUT_MS)
        if (reset !is ElmResult.Ok) {
            return InitOutcome.Failure("ATZ", reset, "Adapter did not answer the reset. Is it powered (ignition ON)?", warnings)
        }
        val adapterId = reset.lines.lastOrNull { it.startsWith("ELM", ignoreCase = true) } ?: reset.lines.lastOrNull()

        for ((cmd, what) in listOf("ATE0" to "echo off", "ATL0" to "linefeeds off", "ATS0" to "spaces off")) {
            onStep("$what ($cmd)")
            if (!isOk(elm.execute(cmd))) warnings += "$cmd ($what) not accepted"
        }

        onStep("Headers on (ATH1)")
        val headersOn = isOk(elm.execute("ATH1"))
        if (!headersOn) warnings += "ATH1 not accepted: ECU addresses will be unknown"

        val sp = "ATSP${protocol.code}"
        onStep("Protocol: ${protocol.label} ($sp)")
        val setProtocol = elm.execute(sp)
        if (!isOk(setProtocol)) {
            return InitOutcome.Failure(sp, setProtocol, "Adapter rejected the protocol setting.", warnings)
        }

        onStep("Battery voltage (ATRV)")
        val voltage = (elm.execute("ATRV") as? ElmResult.Ok)?.lines?.firstOrNull()

        onStep("Asking the car which data it supports (0100)")
        val pid00 = elm.execute("0100", timeoutMs = SEARCH_TIMEOUT_MS)
        if (pid00 !is ElmResult.Ok || pid00.lines.isEmpty()) {
            val hint = when (pid00) {
                ElmResult.NoData -> "The car did not answer. Is the ignition ON?"
                ElmResult.Timeout -> "No reply in time. Ignition ON? Adapter firmly plugged in?"
                is ElmResult.Error ->
                    if (protocol != ElmProtocol.AUTO) "Car did not answer on the saved protocol. Tap 'Re-detect protocol'."
                    else "Adapter could not talk to the car (${pid00.message})."
                is ElmResult.Ok -> "Empty reply."
            }
            return InitOutcome.Failure("0100", pid00, hint, warnings)
        }

        onStep("Detected protocol (ATDPN)")
        val detected = (elm.execute("ATDPN") as? ElmResult.Ok)?.lines?.firstOrNull()?.let(ElmProtocol::parseDpn)
        if (detected == null) warnings += "Could not read the detected protocol (ATDPN)"

        return InitOutcome.Success(InitReport(adapterId, voltage, detected, pid00.lines, headersOn, warnings))
    }

    private fun isOk(result: ElmResult) =
        result is ElmResult.Ok && result.lines.any { it.equals("OK", ignoreCase = true) }

    companion object {
        const val RESET_TIMEOUT_MS = 3_000L
        /** First request after ATSP0 may search several protocols; clones can be slow. */
        const val SEARCH_TIMEOUT_MS = 12_000L
    }
}
