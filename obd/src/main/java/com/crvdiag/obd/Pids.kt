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
            0x1F -> b?.let { PidValue(pid, "Run time since start", 256 * a + it, "s") }
            else -> null
        }
    }

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
