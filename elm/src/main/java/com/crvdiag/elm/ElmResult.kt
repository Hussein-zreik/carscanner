package com.crvdiag.elm

/**
 * Typed outcome of one ELM327 command. The UI never receives raw adapter strings.
 * Parsing of the '>' prompt, SEARCHING..., CAN ERROR etc. arrives in Session 3.
 */
sealed interface ElmResult {
    /** Reply lines with the prompt and empty lines removed. */
    data class Ok(val lines: List<String>) : ElmResult

    /** Adapter answered "NO DATA". */
    data object NoData : ElmResult

    /** No complete reply (no '>' prompt) within the deadline. */
    data object Timeout : ElmResult

    /** Any adapter or bus error ("?", "CAN ERROR", "BUS INIT...ERROR", "STOPPED", ...). */
    data class Error(val message: String) : ElmResult
}
