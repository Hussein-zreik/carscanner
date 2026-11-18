package com.crvdiag.obd

import com.crvdiag.elm.Elm327
import com.crvdiag.elm.ElmProtocol
import com.crvdiag.elm.ElmResult

/** One polling cycle: decoded values plus PIDs that got no usable answer. */
data class LiveSample(val values: Map<Int, PidValue>, val missed: Set<Int>, val timeMs: Long)

object MultiPid {
    /**
     * Splits a service-01 reply carrying several PIDs: 41 pid data… pid data…
     * Returns null if the payload is not a 0x41 reply; stops at the first unknown PID.
     */
    fun parse(payload: List<Int>): Map<Int, List<Int>>? {
        if (payload.firstOrNull() != 0x41) return null
        val result = linkedMapOf<Int, List<Int>>()
        var i = 1
        while (i < payload.size) {
            val pid = payload[i]
            val len = Pids.LENGTH[pid] ?: break
            if (i + 1 + len > payload.size) break
            result[pid] = payload.subList(i + 1, i + 1 + len)
            i += 1 + len
        }
        return result
    }
}

/**
 * Live data from ONE control unit (the engine, request ID 7E0 on this car). Read-only:
 * only service 01 requests (1–6 PIDs each), plus ATSH to the standard request IDs.
 * Multi-PID requests are used when they work; otherwise it falls back to one PID per request.
 */
class LiveDataSession(
    private val elm: Elm327,
    private val protocol: ElmProtocol?,
    private val headersOn: Boolean,
    /** Physical request ID ("7E0"); null = functional broadcast (all units answer). */
    private val requestId: String?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var multiPidWorks: Boolean = true
        private set

    /**
     * Physical addressing (ATSH to one unit) only on 11-bit CAN, where 7E0–7E7 are allow-listed.
     * On 29-bit CAN requests stay functional and only the target unit's answers are used.
     */
    private val canAddress = protocol?.functionalHeader11 != null && requestId != null

    /** Address the target unit only (11-bit). Returns false if the adapter refused. */
    suspend fun begin(): Boolean = !canAddress || elm.execute("ATSH$requestId").isOkLine()

    /** Back to the functional address so other screens reach every unit again (11-bit only). */
    suspend fun end() {
        if (canAddress) protocol?.functionalHeader11?.let { elm.execute("ATSH$it") }
    }

    /** Supported PIDs 01–0x60 of the target unit (0100, then 0120 / 0140 if announced). */
    suspend fun supportedPids(): Set<Int> {
        val all = sortedSetOf<Int>()
        var base = 0x00
        while (base <= 0x40) {
            val bytes = reply("01%02X".format(base))?.let { MultiPid.parse(it)?.get(base) } ?: break
            val pids = SupportedPids.decode(base, bytes)
            all += pids
            if ((base + 0x20) !in pids) break
            base += 0x20
        }
        return all
    }

    /** One pass over [pids]; values that fail are reported in [LiveSample.missed]. */
    suspend fun sample(pids: List<Int>): LiveSample {
        val values = linkedMapOf<Int, PidValue>()
        val pollable = pids.filter { it in Pids.LENGTH }
        if (multiPidWorks) {
            for (group in pollable.chunked(MAX_PIDS_PER_REQUEST)) {
                val parsed = reply("01" + group.joinToString("") { "%02X".format(it) })?.let(MultiPid::parse)
                if (parsed.isNullOrEmpty() && group.size > 1) {
                    multiPidWorks = false // this ECU/adapter combination can't do it: go single
                    break
                }
                parsed?.forEach { (pid, data) -> Pids.decode(pid, data)?.let { values[pid] = it } }
            }
        }
        if (!multiPidWorks) {
            for (pid in pollable) {
                reply("01%02X".format(pid))?.let(MultiPid::parse)?.get(pid)
                    ?.let { Pids.decode(pid, it) }?.let { values[pid] = it }
            }
        }
        return LiveSample(values, pollable.toSet() - values.keys, clock())
    }

    /** Payload from the target unit (or the largest reply when units can't be told apart). */
    private suspend fun reply(cmd: String): List<Int>? {
        val r = elm.execute(cmd, timeoutMs = 1_500, retries = 0) as? ElmResult.Ok ?: return null
        val messages = ObdMessages.collect(r.lines, protocol, headersOn)
        val expected = requestId?.let { EcuDiscovery.responseIdFor(it) }
        return (messages.firstOrNull { it.ecu == expected } ?: messages.maxByOrNull { it.payload.size })?.payload
    }

    private fun ElmResult.isOkLine() = this is ElmResult.Ok && lines.any { it.equals("OK", ignoreCase = true) }

    companion object {
        /** ISO 15765-4 allows up to 6 PIDs in one service-01 request. */
        const val MAX_PIDS_PER_REQUEST = 6

        /** Polled in this order when supported: most useful first. */
        val DEFAULT_PIDS = listOf(
            0x0C, 0x0D, 0x05, 0x04, 0x11, 0x0B, 0x10, 0x0F, 0x0E, 0x06, 0x07, 0x15, 0x03,
            0x42, 0x44, 0x24, 0x34, 0x2E, 0x2F, 0x46, 0x3C, 0x33, 0x1F, 0x31, 0x30,
        )
    }
}
