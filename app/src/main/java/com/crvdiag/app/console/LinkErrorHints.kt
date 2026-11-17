package com.crvdiag.app.console

/**
 * Turns raw socket/Bluetooth error text into a plain-language next step.
 * Patterns come from errors actually seen on the Tab A9+ (see testdata/ recordings).
 */
object LinkErrorHints {
    fun hintFor(message: String): String? {
        val m = message.lowercase()
        return when {
            "enetunreach" in m || "ehostunreach" in m || "network is unreachable" in m ->
                "Tablet is not on the adapter's Wi-Fi. Join the adapter's network in Settings → Wi-Fi, then retry."
            "econnrefused" in m || "connection refused" in m ->
                "Adapter refused the connection. Check IP and port (usually 192.168.0.10 : 35000)."
            "timed out" in m || "etimedout" in m ->
                "Adapter did not answer. Check ignition is ON and the tablet is on the adapter's Wi-Fi."
            "connection abort" in m || "econnreset" in m || "connection reset" in m || "broken pipe" in m ->
                "Link dropped. On Wi-Fi, Android may leave a network without internet: choose 'Keep Wi-Fi connection' and reconnect."
            "read failed" in m || "socket might closed" in m || "service discovery failed" in m ->
                "Adapter did not accept Bluetooth. Ignition ON? Close other OBD apps (Car Scanner) and retry."
            "bluetooth is off" in m -> "Turn on the tablet's Bluetooth."
            else -> null
        }
    }
}
