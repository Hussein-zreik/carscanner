package com.crvdiag.app.live

import android.content.Context
import androidx.core.content.edit
import com.crvdiag.obd.PidValue

/** Display units. Defaults match the owner's Car Scanner setup: °C, mph, psi. */
data class UnitPrefs(val fahrenheit: Boolean = false, val mph: Boolean = true, val psi: Boolean = true) {

    /** Converts a decoded value (always °C, km/h, kPa, km) for display. */
    fun convert(v: PidValue): Pair<Double?, String> {
        val x = v.value ?: return null to v.unit
        return when (v.unit) {
            "°C" -> if (fahrenheit) x * 9 / 5 + 32 to "°F" else x to "°C"
            "km/h" -> if (mph) x * 0.621371 to "mph" else x to "km/h"
            "kPa" -> if (psi) x * 0.145038 to "psi" else x to "kPa"
            "km" -> if (mph) x * 0.621371 to "mi" else x to "km"
            else -> x to v.unit
        }
    }

    fun text(v: PidValue): String {
        v.text?.let { return it }
        val (x, unit) = convert(v)
        return "${number(x, v.unit)} $unit".trim()
    }

    fun number(x: Double?, unit: String): String = when {
        x == null -> "—"
        unit in setOf("λ", "V") -> "%.2f".format(x)
        unit == "%" || unit == "°" || unit == "g/s" || unit == "kPa" -> "%.1f".format(x)
        else -> "%.0f".format(x)
    }

    companion object {
        fun load(context: Context): UnitPrefs {
            val p = context.getSharedPreferences("units", Context.MODE_PRIVATE)
            return UnitPrefs(p.getBoolean("f", false), p.getBoolean("mph", true), p.getBoolean("psi", true))
        }

        fun save(context: Context, u: UnitPrefs) = context.getSharedPreferences("units", Context.MODE_PRIVATE).edit {
            putBoolean("f", u.fahrenheit).putBoolean("mph", u.mph).putBoolean("psi", u.psi)
        }
    }
}
