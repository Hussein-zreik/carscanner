package com.crvdiag.obd

data class MonitorStatus(val name: String, val available: Boolean, val complete: Boolean)

/** Service 01 PID 01: check-engine light, stored-code count and readiness monitors. */
data class Readiness(
    val milOn: Boolean,
    val dtcCount: Int,
    val compressionIgnition: Boolean,
    val monitors: List<MonitorStatus>,
) {
    /** Monitors that exist on this car but have not finished since the last code clear. */
    val incomplete: List<MonitorStatus> get() = monitors.filter { it.available && !it.complete }

    companion object {
        private val continuous = listOf("Misfire", "Fuel system", "Components")
        private val spark = listOf(
            "Catalyst", "Heated catalyst", "Evaporative system", "Secondary air system",
            "A/C refrigerant", "Oxygen sensor", "Oxygen sensor heater", "EGR system",
        )
        private val diesel = listOf(
            "NMHC catalyst", "NOx/SCR aftertreatment", "Reserved", "Boost pressure",
            "Reserved", "Exhaust gas sensor", "PM filter", "EGR/VVT system",
        )

        /** Decodes the 4 data bytes A B C D (SAE J1979). In B and D a set bit = NOT complete. */
        fun decode(a: Int, b: Int, c: Int, d: Int): Readiness {
            val ci = b and 0x08 != 0
            val monitors = continuous.mapIndexed { i, name ->
                MonitorStatus(name, available = b and (1 shl i) != 0, complete = b and (1 shl (i + 4)) == 0)
            } + (if (ci) diesel else spark).mapIndexed { i, name ->
                MonitorStatus(name, available = c and (1 shl i) != 0, complete = d and (1 shl i) == 0)
            }.filterNot { it.name == "Reserved" }
            return Readiness(a and 0x80 != 0, a and 0x7F, ci, monitors)
        }

        fun fromMessage(message: EcuMessage): Readiness? {
            val p = message.payload
            if (p.size < 6 || p[0] != 0x41 || p[1] != 0x01) return null
            return decode(p[2], p[3], p[4], p[5])
        }
    }
}
