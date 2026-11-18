package com.crvdiag.app.connection

/**
 * Automatic reconnect after a working link drops (seen on the Tab A9+: Android leaves the
 * adapter's no-internet Wi-Fi, "Software caused connection abort"). Not used for a first
 * connection that fails: that needs the user (e.g. join the adapter's Wi-Fi first).
 */
object ReconnectPolicy {
    const val MAX_ATTEMPTS = 3
    private val DELAYS_MS = longArrayOf(2_000, 5_000, 10_000)

    /** Pause before reconnect attempt [attempt] (1-based). */
    fun delayMs(attempt: Int): Long = DELAYS_MS[(attempt - 1).coerceIn(0, DELAYS_MS.lastIndex)]
}
