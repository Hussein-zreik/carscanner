package com.crvdiag.honda

/**
 * Honda manufacturer-specific code text. Only codes seen on this car, each with its source.
 * Not from Honda service information: treat as "very likely", not certain.
 */
object HondaDtcText {
    private val known = mapOf(
        // Seen pending on 2026-11-17 (Car Scanner). Meaning per aftermarket references
        // (obdadvisor.com, fatboysoffroad.com); TODO: confirm with Honda service information.
        "P145C" to "EVAP purge flow malfunction (per aftermarket sources)",
    )

    fun describe(code: String): String? = known[code]
}
