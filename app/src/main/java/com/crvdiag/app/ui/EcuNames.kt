package com.crvdiag.app.ui

import com.crvdiag.obd.EcuInfo

/**
 * Plain names for the car's control units. On this CR-V two units answer (see CLAUDE.md):
 * the one reporting fuel trims + MAF is the engine side; the smaller set is most likely the
 * transmission side. "likely" stays in the label until confirmed.
 */
object EcuNames {
    fun label(ecuId: String, ecus: List<EcuInfo>): String {
        val info = ecus.firstOrNull { it.responseId == ecuId }
        return when {
            info == null -> ecuId.replace("ECU", "Unit")
            0x06 in info.supportedPids && 0x10 in info.supportedPids -> "Engine computer"
            else -> "Transmission computer (likely)"
        }
    }
}
