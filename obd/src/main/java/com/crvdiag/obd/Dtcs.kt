package com.crvdiag.obd

/** Diagnostic trouble codes per SAE J2012 / J1979. */
object Dtcs {
    private val systems = charArrayOf('P', 'C', 'B', 'U')

    /** Two raw bytes → "P145C". 0x0000 means "no code" and returns null. */
    fun decode(high: Int, low: Int): String? {
        if (high == 0 && low == 0) return null
        val system = systems[high shr 6]
        val first = (high shr 4) and 0x03
        return "%c%d%X%02X".format(system, first, high and 0x0F, low)
    }

    /**
     * Codes in a mode 03 (stored, SID 0x43), 07 (pending, 0x47) or 0A (permanent, 0x4A) reply.
     * On CAN the byte after the SID is the code count.
     */
    fun fromMessage(message: EcuMessage, responseSid: Int): List<String>? {
        val p = message.payload
        if (p.isEmpty() || p[0] != responseSid) return null
        val withCount = p.size >= 2 && (p.size - 2) % 2 == 0 && p[1] == (p.size - 2) / 2
        val start = if (withCount) 2 else 1
        return p.drop(start).chunked(2).filter { it.size == 2 }.mapNotNull { decode(it[0], it[1]) }
    }
}
