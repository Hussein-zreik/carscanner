package com.crvdiag.obd

import kotlin.math.abs

/** Result of one guided test: a headline plus findings (a guide, not a diagnosis). */
data class TestVerdict(
    val headline: String,
    val findings: List<DiagFinding>,
    /** Not a fault, but not finished either (e.g. self-tests still to run): shown neutral, not green. */
    val pending: Boolean = false,
) {
    val needsAttention: Boolean get() = findings.any { it.level == Finding.Watch }
}

private fun fmt1(v: Double) = "%.1f".format(v)

/** Battery, starting and charging test (adapter voltage at the OBD port, approximate on clones). */
object BatteryAnalysis {
    fun evaluate(
        resting: List<Double>,
        restingWithIgnitionOn: Boolean,
        cranking: List<Double>,
        idle: List<Double>,
        loaded: List<Double>,
        loadedRpm: Double?,
    ): TestVerdict {
        val f = mutableListOf<DiagFinding>()
        val rest = resting.takeIf { it.isNotEmpty() }?.average()
        rest?.let { v ->
            val note = if (restingWithIgnitionOn) " Measured with the ignition ON, which reads lower than true resting voltage." else ""
            f += when {
                v >= 12.6 -> DiagFinding("Battery charge", Finding.Good, "${fmt1(v)} V at rest: fully charged.$note")
                v >= 12.4 -> DiagFinding("Battery charge", Finding.Good, "${fmt1(v)} V at rest: about 75 % charged.$note")
                v >= 12.0 -> DiagFinding("Battery charge", Finding.Watch, "${fmt1(v)} V at rest: partly discharged (about 25–50 %). Charge it and re-test.$note")
                else -> DiagFinding("Battery charge", Finding.Watch, "${fmt1(v)} V at rest: discharged. Charge it, then have it load-tested.$note")
            }
        }
        val crankMin = cranking.minOrNull()
        if (crankMin != null) {
            val dipped = rest == null || crankMin < rest - 0.8
            f += when {
                !dipped -> DiagFinding("Cranking", Finding.Info, "No cranking dip was seen. Start the engine within 5 seconds of tapping Start, then repeat.")
                crankMin >= 10.0 -> DiagFinding("Cranking", Finding.Good, "Lowest ${fmt1(crankMin)} V while starting: battery and starter are strong.")
                crankMin >= 9.6 -> DiagFinding("Cranking", Finding.Watch, "Lowest ${fmt1(crankMin)} V while starting: borderline. A battery near the end of its life often shows this.")
                else -> DiagFinding("Cranking", Finding.Watch, "Lowest ${fmt1(crankMin)} V while starting: below 9.6 V, so the battery is weak (or the cables/starter draw too much).")
            }
        }
        val idleV = idle.takeIf { it.isNotEmpty() }?.average()
        val loadV = loaded.takeIf { it.isNotEmpty() }?.average()
        idleV?.let { v ->
            f += when {
                v > 14.8 -> DiagFinding("Charging at idle", Finding.Watch, "${fmt1(v)} V: higher than normal (overcharging). Have the charging system checked.")
                v >= 13.6 -> DiagFinding("Charging at idle", Finding.Good, "${fmt1(v)} V: the alternator is charging.")
                v >= 12.8 -> DiagFinding("Charging at idle", Finding.Info, "${fmt1(v)} V: lower than classic charging voltage, which can be normal on this Honda (the alternator is controlled and charges less when electrical load is low). The loaded test decides.")
                else -> DiagFinding("Charging at idle", Finding.Watch, "${fmt1(v)} V: the alternator does not seem to charge at idle.")
            }
        }
        loadV?.let { v ->
            val rpmNote = loadedRpm?.takeIf { it < 1800 }?.let { " (engine was only at ${it.toInt()} rpm; aim for about 2,500)" } ?: ""
            f += when {
                v > 14.8 -> DiagFinding("Charging with load", Finding.Watch, "${fmt1(v)} V: overcharging$rpmNote.")
                v >= 13.5 -> DiagFinding("Charging with load", Finding.Good, "${fmt1(v)} V with lights and blower on$rpmNote: the alternator keeps up.")
                v >= 13.0 -> DiagFinding("Charging with load", Finding.Watch, "${fmt1(v)} V with load$rpmNote: on the low side. Re-test; if it stays below 13.5 V, have the alternator tested.")
                else -> DiagFinding("Charging with load", Finding.Watch, "${fmt1(v)} V with load$rpmNote: the alternator can't keep up. Have the alternator and belt checked.")
            }
        }
        val headline = when {
            f.none { it.level != Finding.Info } -> "Not enough measurements"
            f.any { it.level == Finding.Watch } -> "Battery or charging needs attention"
            else -> "Battery and charging look healthy"
        }
        return TestVerdict(headline, f)
    }
}

/** Fuel-trim test: warm idle vs ~2,500 rpm (parked) separates a vacuum leak from fuel delivery / MAF. */
object FuelTrimAnalysis {
    const val LIMIT = 10.0

    data class Phase(val stft: List<Double>, val ltft: List<Double>, val rpm: List<Double>, val coolant: Double?, val maf: List<Double> = emptyList()) {
        val total: Double? get() = if (stft.isEmpty() || ltft.isEmpty()) null else stft.average() + ltft.average()
    }

    fun evaluate(idle: Phase, high: Phase): TestVerdict {
        val f = mutableListOf<DiagFinding>()
        if ((idle.coolant ?: 0.0) < 75) f += DiagFinding("Engine not warm", Finding.Info, "Trims are only meaningful once the engine is warm (coolant 75 °C+). Repeat after a 10-minute drive.")
        val hiRpm = high.rpm.takeIf { it.isNotEmpty() }?.average()
        if (hiRpm != null && (hiRpm < 2000 || hiRpm > 3200)) {
            f += DiagFinding("Engine speed", Finding.Info, "The second step averaged ${hiRpm.toInt()} rpm; aim for a steady 2,500 rpm.")
        }
        val i = idle.total
        val h = high.total
        if (i == null || h == null) return TestVerdict("Not enough fuel-trim data", f + DiagFinding("Fuel trims", Finding.Info, "The engine computer didn't report both fuel trims."))
        val both = "Idle ${sign(i)} %, 2,500 rpm ${sign(h)} %."
        f += when {
            i > LIMIT && h < i - 5 -> DiagFinding("Lean at idle, better at 2,500 rpm", Finding.Watch,
                "$both The engine adds fuel mostly at idle: the classic sign of a vacuum (air) leak (hoses, intake gasket, PCV, brake booster). A shop confirms with a smoke test.")
            i > LIMIT && h > LIMIT -> DiagFinding("Lean at idle and at 2,500 rpm", Finding.Watch,
                "$both Lean everywhere points to fuel delivery (pump, filter, pressure) or an air-flow (MAF) sensor reading low. Cleaning the MAF sensor is a cheap first step.")
            i < -LIMIT && h > i + 5 -> DiagFinding("Rich at idle, better at 2,500 rpm", Finding.Watch,
                "$both Extra fuel mostly at idle often comes from the EVAP purge valve stuck open (linked to your P145C) or a leaking injector.")
            i < -LIMIT && h < -LIMIT -> DiagFinding("Rich at idle and at 2,500 rpm", Finding.Watch,
                "$both Rich everywhere points to high fuel pressure, leaking injectors, or a sensor misreading (MAF, upstream O2).")
            h > LIMIT -> DiagFinding("Lean at 2,500 rpm", Finding.Watch, "$both Lean under higher load points to fuel delivery or the MAF sensor.")
            h < -LIMIT -> DiagFinding("Rich at 2,500 rpm", Finding.Watch, "$both Rich under higher load: check fuel pressure and injectors.")
            else -> DiagFinding("Fuel mixture", Finding.Good, "$both Both within ±10 %: no vacuum leak or fuel-delivery problem shows in the trims.")
        }
        idle.maf.takeIf { it.isNotEmpty() }?.average()?.let { m ->
            f += DiagFinding("Air flow at idle", Finding.Info,
                "${fmt1(m)} g/s. As a rule of thumb a warm 2.4 L engine idles at roughly 2–4 g/s; much lower can mean a dirty MAF sensor or unmetered air.")
        }
        val headline = if (f.any { it.level == Finding.Watch }) f.first { it.level == Finding.Watch }.title else "Fuel mixture is normal"
        return TestVerdict(headline, f)
    }

    private fun sign(v: Double) = (if (v > 0) "+" else "") + fmt1(v)
}

/**
 * EVAP purge observation at warm idle. Purge vapours add fuel, so when the engine opens the purge
 * valve the short-term trim should go down. Read-only: a shop confirms with a smoke/vacuum test.
 */
object EvapAnalysis {
    data class Sample(val purgePercent: Double?, val stft: Double?, val ltft: Double?)

    fun evaluate(samples: List<Sample>, purgeMonitor: List<MonitorTest>, coolant: Double?): TestVerdict {
        val f = mutableListOf<DiagFinding>()
        if ((coolant ?: 0.0) < 75) f += DiagFinding("Engine not warm", Finding.Info, "The engine only purges once warm. Drive 10+ minutes, then repeat.")
        val withPurge = samples.filter { it.purgePercent != null && it.stft != null }
        val ltft = samples.mapNotNull { it.ltft }.takeIf { it.isNotEmpty() }?.average()
        val stftAll = samples.mapNotNull { it.stft }.takeIf { it.isNotEmpty() }?.average()

        if (withPurge.size >= 10) {
            val closed = withPurge.filter { it.purgePercent!! < 5 }.mapNotNull { it.stft }
            val open = withPurge.filter { it.purgePercent!! >= 20 }.mapNotNull { it.stft }
            val maxPurge = withPurge.maxOf { it.purgePercent!! }
            f += when {
                open.size < 3 -> DiagFinding("Purge not commanded", Finding.Info,
                    "The engine opened the purge valve to at most ${maxPurge.toInt()} % during the test. Purge depends on warm-up time and how full the charcoal canister is: drive 15 minutes (ideally after refuelling) and repeat.")
                closed.size >= 3 && closed.average() - open.average() >= 3 -> DiagFinding("Purge flow seen", Finding.Good,
                    "When purge opened, short-term trim dropped from ${sign(closed.average())} % to ${sign(open.average())} %: fuel vapours reach the engine, so the valve opens and the hose is not blocked right now.")
                closed.size >= 3 -> DiagFinding("No response to purge", Finding.Watch,
                    "Purge was commanded (up to ${maxPurge.toInt()} %) but the fuel trim did not react. Possible: purge valve stuck closed, blocked/disconnected hose, or an empty canister (re-test after refuelling). This fits a purge-flow code such as P145C.")
                else -> DiagFinding("Purge open the whole time", Finding.Info, "Purge stayed open, so there's nothing to compare with. Repeat after the engine idles a few minutes.")
            }
        } else {
            f += DiagFinding("Commanded purge not reported", Finding.Info,
                "This engine computer doesn't report commanded purge, so purge flow can't be watched directly. The fuel trims and the car's own purge-flow self-test below are used instead.")
        }
        if (ltft != null && stftAll != null) {
            val total = ltft + stftAll
            if (total < -FuelTrimAnalysis.LIMIT) {
                f += DiagFinding("Engine running rich at idle", Finding.Watch,
                    "Fuel trims ${sign(total)} %. A purge valve stuck OPEN lets vapours in all the time, which makes the idle rich and rough, often worst right after refuelling (hard start after filling up is a typical sign).")
            }
        }
        val run = purgeMonitor.filter { !Mode06Catalog.notRun(it) }
        f += when {
            purgeMonitor.isEmpty() -> DiagFinding("Car's purge-flow self-test", Finding.Info, "Not reported by the engine computer.")
            run.isEmpty() -> DiagFinding("Car's purge-flow self-test", Finding.Info, "Not run yet since the codes were cleared. It runs during normal driving after a cold start.")
            run.all { it.passed } -> DiagFinding("Car's purge-flow self-test", Finding.Good, "Last result: passed.")
            else -> DiagFinding("Car's purge-flow self-test", Finding.Watch, "Last result: FAILED (outside the car's own limits). This is what sets P145C-type codes.")
        }
        f += DiagFinding("To confirm", Finding.Info,
            "A shop confirms with a smoke test of the EVAP system and by checking that the purge valve holds vacuum when off and opens when powered. Check the fuel cap seal first: it's the cheapest EVAP fix.")
        val headline = when {
            f.any { it.level == Finding.Watch } -> f.first { it.level == Finding.Watch }.title
            f.any { it.title == "Purge flow seen" } -> "Purge valve works right now"
            else -> "No clear purge fault seen"
        }
        return TestVerdict(headline, f)
    }

    private fun sign(v: Double) = (if (v > 0) "+" else "") + fmt1(v)
}

/** Service 06 catalogue: monitor names (SAE J1979), support masks, "not run yet" detection, drive-cycle tips. */
object Mode06Catalog {
    private val names = mapOf(
        0x01 to "O2 sensor (bank 1 sensor 1)", 0x02 to "O2 sensor (bank 1 sensor 2)", 0x03 to "O2 sensor (bank 1 sensor 3)",
        0x05 to "O2 sensor (bank 2 sensor 1)", 0x06 to "O2 sensor (bank 2 sensor 2)",
        0x21 to "Catalyst (bank 1)", 0x22 to "Catalyst (bank 2)", 0x31 to "EGR", 0x35 to "Valve timing (VVT, bank 1)",
        0x39 to "EVAP leak 0.150\"", 0x3A to "EVAP leak 0.090\"", 0x3B to "EVAP leak 0.040\"", 0x3C to "EVAP leak 0.020\"",
        0x3D to "EVAP purge flow", 0x41 to "O2 sensor heater (bank 1 sensor 1)", 0x42 to "O2 sensor heater (bank 1 sensor 2)",
        0x61 to "Heated catalyst (bank 1)", 0x71 to "Secondary air", 0x81 to "Fuel system (bank 1)", 0xA1 to "Misfire (general)",
    ) + (0xA2..0xA9).associateWith { "Misfire cylinder ${it - 0xA1}" }

    fun name(mid: Int): String = names[mid] ?: "Monitor %02X".format(mid)

    /** Support-mask reply for MID 00/20/40/…: 46 mid b1 b2 b3 b4. */
    fun supportedMids(message: EcuMessage, base: Int): Set<Int>? {
        val p = message.payload
        if (p.size < 6 || p[0] != 0x46 || p[1] != base) return null
        return SupportedPids.decode(base, p.subList(2, 6))
    }

    /** All-zero results or impossible limits mean "not run since codes were cleared" (seen on this car). */
    fun notRun(t: MonitorTest): Boolean = (t.value == 0 && t.min == 0 && t.max == 0) || t.min > t.max || t.value == 0x5555

    /** Plain-language drive-cycle hints for readiness monitors (general guidance, not Honda's exact procedure). */
    fun driveCycleTip(monitor: String): String = when (monitor) {
        "Catalyst" -> "After the engine is fully warm, drive at a steady 50–60 mph for 5–10 minutes, then let it idle a minute."
        "Evaporative system" -> "Needs a cold start after the car has sat 8+ hours, with the fuel tank between 1/4 and 3/4. Drive 15–20 minutes mixing city and steady highway, then idle a few minutes. Can take several days."
        "Oxygen sensor" -> "After warm-up, cruise steadily for a few minutes, then coast (foot off the pedal) several times from about 40 mph."
        "Oxygen sensor heater" -> "Usually completes within a few minutes of driving after a cold start."
        "EGR system" -> "Steady cruising at 40–55 mph with several gentle decelerations."
        "Heated catalyst", "Secondary air system", "A/C refrigerant" -> "Not used on this car."
        else -> "Runs continuously during normal driving."
    }
}

/** One monitor's stored results (service 06). */
data class MonitorResults(val mid: Int, val tests: List<MonitorTest>) {
    val name: String get() = Mode06Catalog.name(mid)
    val status: String
        get() = when {
            tests.isEmpty() -> "No data"
            tests.all(Mode06Catalog::notRun) -> "Not run yet"
            tests.filterNot(Mode06Catalog::notRun).all { it.passed } -> "Passed"
            else -> "FAILED"
        }
}

/** Reads every supported service-06 monitor of the engine (read-only: 06 + one MID per request). */
class SelfTestReader(
    private val elm: com.crvdiag.elm.Elm327,
    private val protocol: com.crvdiag.elm.ElmProtocol?,
    private val headersOn: Boolean,
    private val engineResponseId: String?,
) {
    private suspend fun engineMessage(cmd: String): EcuMessage? {
        val r = elm.execute(cmd) as? com.crvdiag.elm.ElmResult.Ok ?: return null
        val msgs = ObdMessages.collect(r.lines, protocol, headersOn)
        return msgs.firstOrNull { it.ecu == engineResponseId } ?: msgs.maxByOrNull { it.payload.size }
    }

    suspend fun supportedMids(): Set<Int> {
        val all = sortedSetOf<Int>()
        var base = 0x00
        while (base <= 0xE0) {
            val mids = engineMessage("06%02X".format(base))?.let { Mode06Catalog.supportedMids(it, base) } ?: break
            all += mids
            if ((base + 0x20) !in mids) break
            base += 0x20
        }
        return all.filter { it % 0x20 != 0 }.toSortedSet()
    }

    suspend fun readAll(onStep: (String) -> Unit = {}): List<MonitorResults> = supportedMids().map { mid ->
        onStep("Self-test ${Mode06Catalog.name(mid)}")
        MonitorResults(mid, engineMessage("06%02X".format(mid))?.let(Mode06::parse).orEmpty().filter { it.mid == mid })
    }
}
