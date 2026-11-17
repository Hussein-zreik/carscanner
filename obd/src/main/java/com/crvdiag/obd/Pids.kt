package com.crvdiag.obd

import kotlin.math.roundToInt

/** A decoded value. [text] is set for non-numeric PIDs (e.g. fuel system status). */
data class PidValue(val pid: Int, val name: String, val value: Double?, val unit: String, val text: String? = null) {
    fun display(): String = text ?: "${format(value)} $unit".trim()

    private fun format(v: Double?): String = when {
        v == null -> "?"
        v == v.roundToInt().toDouble() -> v.roundToInt().toString()
        else -> "%.2f".format(v)
    }
}

/** Standard SAE J1979 service 01/02 PID formulas (A = first data byte, B = second). */
object Pids {
    fun decode(pid: Int, data: List<Int>): PidValue? {
        val a = data.getOrNull(0)?.toDouble() ?: return null
        val b = data.getOrNull(1)?.toDouble()
        return when (pid) {
            0x03 -> PidValue(pid, "Fuel system status", null, "", fuelStatus(data[0]))
            0x04 -> PidValue(pid, "Engine load", a * 100 / 255, "%")
            0x05 -> PidValue(pid, "Coolant temperature", a - 40, "°C")
            0x06 -> PidValue(pid, "Short-term fuel trim B1", (a - 128) * 100 / 128, "%")
            0x07 -> PidValue(pid, "Long-term fuel trim B1", (a - 128) * 100 / 128, "%")
            0x0B -> PidValue(pid, "Intake manifold pressure", a, "kPa")
            0x0C -> b?.let { PidValue(pid, "Engine RPM", (256 * a + it) / 4, "rpm") }
            0x0D -> PidValue(pid, "Vehicle speed", a, "km/h")
            0x0E -> PidValue(pid, "Timing advance", a / 2 - 64, "°")
            0x0F -> PidValue(pid, "Intake air temperature", a - 40, "°C")
            0x10 -> b?.let { PidValue(pid, "MAF air flow", (256 * a + it) / 100, "g/s") }
            0x11 -> PidValue(pid, "Throttle position", a * 100 / 255, "%")
            0x15 -> PidValue(pid, "Downstream O2 sensor (B1S2)", a / 200, "V")
            0x1F -> b?.let { PidValue(pid, "Run time since start", 256 * a + it, "s") }
            0x21 -> b?.let { PidValue(pid, "Distance with check-engine light on", 256 * a + it, "km") }
            0x24, 0x34 -> b?.let { PidValue(pid, "Upstream A/F sensor (lambda)", (256 * a + it) * 2 / 65536, "λ") }
            0x2C -> PidValue(pid, "Commanded EGR", a * 100 / 255, "%")
            0x2E -> PidValue(pid, "EVAP purge (commanded)", a * 100 / 255, "%")
            0x2F -> PidValue(pid, "Fuel level", a * 100 / 255, "%")
            0x30 -> PidValue(pid, "Warm-ups since codes cleared", a, "")
            0x31 -> b?.let { PidValue(pid, "Distance since codes cleared", 256 * a + it, "km") }
            0x33 -> PidValue(pid, "Barometric pressure", a, "kPa")
            0x3C -> b?.let { PidValue(pid, "Catalyst temperature (B1S1)", (256 * a + it) / 10 - 40, "°C") }
            0x42 -> b?.let { PidValue(pid, "Control module voltage", (256 * a + it) / 1000, "V") }
            0x43 -> b?.let { PidValue(pid, "Absolute load", (256 * a + it) * 100 / 255, "%") }
            0x44 -> b?.let { PidValue(pid, "Commanded air-fuel ratio (lambda)", (256 * a + it) * 2 / 65536, "λ") }
            0x45 -> PidValue(pid, "Relative throttle", a * 100 / 255, "%")
            0x46 -> PidValue(pid, "Ambient air temperature", a - 40, "°C")
            else -> null
        }
    }

    /** Data bytes per PID (SAE J1979), needed to split multi-PID replies. Unknown → not polled. */
    val LENGTH: Map<Int, Int> = mapOf(
        0x00 to 4, 0x01 to 4, 0x03 to 2, 0x04 to 1, 0x05 to 1, 0x06 to 1, 0x07 to 1, 0x0B to 1, 0x0C to 2,
        0x0D to 1, 0x0E to 1, 0x0F to 1, 0x10 to 2, 0x11 to 1, 0x13 to 1, 0x15 to 2, 0x1C to 1,
        0x1F to 2, 0x20 to 4, 0x21 to 2, 0x24 to 4, 0x2C to 1, 0x2E to 1, 0x2F to 1, 0x30 to 1,
        0x31 to 2, 0x33 to 1, 0x34 to 4, 0x3C to 2, 0x40 to 4, 0x42 to 2, 0x43 to 2, 0x44 to 2,
        0x45 to 1, 0x46 to 1,
    )

    private fun fuelStatus(value: Int): String = when (value) {
        0x00 -> "Engine off / not reported"
        0x01 -> "Open loop: not warm yet"
        0x02 -> "Closed loop (normal)"
        0x04 -> "Open loop: load or deceleration"
        0x08 -> "Open loop: system fault"
        0x10 -> "Closed loop: feedback fault"
        else -> "Unknown (0x%02X)".format(value)
    }
}
