package com.crvdiag.obd

import kotlin.math.abs

enum class CheckResult { Good, Watch, Waiting }

data class LiveCheck(val title: String, val result: CheckResult, val detail: String)

/**
 * Plain-language guide checks on recent live samples. A guide, not a diagnosis: each check
 * only runs when its conditions are met (engine running / warm / idling).
 * [history] = recent values per PID, oldest first, in the decoder's base units (°C, V, %, λ).
 */
object LiveChecks {
    fun evaluate(history: Map<Int, List<Double>>): List<LiveCheck> {
        fun last(pid: Int) = history[pid]?.lastOrNull()
        val rpm = last(0x0C)
        val speed = last(0x0D)
        val coolant = last(0x05)
        val running = rpm != null && rpm > 400
        val warm = coolant != null && coolant >= 75
        val idling = running && (speed ?: 0.0) < 1 && rpm!! < 1300

        val waitRunning = "Start the engine."
        val waitWarmIdle = "Needs a warm engine at idle (coolant 75 °C+, parked)."

        val trims = run {
            val st = last(0x06); val lt = last(0x07)
            when {
                st == null || lt == null -> null
                !running || !warm -> LiveCheck("Fuel trims", CheckResult.Waiting, if (!running) waitRunning else waitWarmIdle)
                abs(lt) <= 10 && abs(st + lt) <= 15 -> LiveCheck("Fuel trims", CheckResult.Good,
                    "Normal: long-term ${fmt(lt)} %, short-term ${fmt(st)} %. The engine needs little fuel correction.")
                else -> LiveCheck("Fuel trims", CheckResult.Watch,
                    "Long-term ${fmt(lt)} %, short-term ${fmt(st)} %. " +
                        if (st + lt > 0) "The engine is ADDING fuel (running lean): air leaks and weak fuel delivery are common causes."
                        else "The engine is REMOVING fuel (running rich): leaking injectors or sensor issues are common causes.")
            }
        }

        val charging = last(0x42)?.let { v ->
            when {
                !running -> LiveCheck("Charging voltage", CheckResult.Waiting, "${fmt(v)} V now. $waitRunning Then it should read 13.7–14.7 V.")
                v in 13.5..14.8 -> LiveCheck("Charging voltage", CheckResult.Good, "${fmt(v)} V: the alternator is charging normally.")
                v < 13.5 -> LiveCheck("Charging voltage", CheckResult.Watch, "${fmt(v)} V with the engine running: low. Have the alternator and battery tested.")
                else -> LiveCheck("Charging voltage", CheckResult.Watch, "${fmt(v)} V: higher than normal. Have the charging system checked.")
            }
        }

        val downstream = history[0x15]?.let { values ->
            val recent = values.takeLast(30)
            when {
                !idling || !warm -> LiveCheck("Downstream O2 sensor", CheckResult.Waiting, waitWarmIdle)
                recent.size < 15 -> LiveCheck("Downstream O2 sensor", CheckResult.Waiting, "Collecting readings…")
                else -> {
                    val avg = recent.average(); val swing = recent.max() - recent.min()
                    if (avg in 0.45..0.9 && swing < 0.4) LiveCheck("Downstream O2 sensor", CheckResult.Good,
                        "Steady around ${fmt(avg)} V: the sensor works and the catalytic converter is smoothing the exhaust.")
                    else if (swing >= 0.6) LiveCheck("Downstream O2 sensor", CheckResult.Watch,
                        "Swinging ${fmt(recent.min())}–${fmt(recent.max())} V. A downstream sensor that switches like the upstream one can mean a weak catalytic converter (watch for P0420).")
                    else LiveCheck("Downstream O2 sensor", CheckResult.Watch,
                        "Around ${fmt(avg)} V, swing ${fmt(swing)} V. Re-check after a 10-minute drive.")
                }
            }
        }

        val upstream = (history[0x34] ?: history[0x24])?.let { values ->
            val recent = values.takeLast(20)
            when {
                !idling || !warm || recent.size < 10 -> LiveCheck("Upstream A/F sensor", CheckResult.Waiting, waitWarmIdle)
                recent.average() in 0.97..1.03 -> LiveCheck("Upstream A/F sensor", CheckResult.Good,
                    "Lambda ${fmt(recent.average())}: the mixture is balanced, as it should be at idle.")
                else -> LiveCheck("Upstream A/F sensor", CheckResult.Watch,
                    "Lambda ${fmt(recent.average())} (1.00 = balanced). Compare with fuel trims.")
            }
        }

        val temperature = coolant?.let { c ->
            val runTime = last(0x1F)
            when {
                c > 105 -> LiveCheck("Engine temperature", CheckResult.Watch, "${fmt(c)} °C: running hot. Stop and check the coolant level when cool.")
                warm -> LiveCheck("Engine temperature", CheckResult.Good, "${fmt(c)} °C: normal operating temperature.")
                running && runTime != null && runTime > 900 -> LiveCheck("Engine temperature", CheckResult.Watch,
                    "Only ${fmt(c)} °C after ${(runTime / 60).toInt()} min: slow to warm up. The thermostat may be stuck open (code P0128).")
                else -> LiveCheck("Engine temperature", CheckResult.Waiting, "${fmt(c)} °C: warming up.")
            }
        }
        return listOfNotNull(temperature, trims, charging, upstream, downstream)
    }

    private fun fmt(v: Double) = if (abs(v) >= 10) "%.1f".format(v) else "%.2f".format(v)
}
