package com.crvdiag.app.tests

/** The guided tests offered in the Tests hub (besides the misfire check, which has its own screen). */
enum class TestKind(val title: String, val summary: String) {
    Battery("Battery & charging", "Resting voltage, cranking dip, alternator at idle and with load."),
    FuelTrim("Fuel / vacuum leak", "Fuel trims at idle vs 2,500 rpm: vacuum leak or fuel delivery."),
    Evap("EVAP purge (P145C)", "Is the purge valve stuck open, stuck closed, or working?"),
    SelfTests("Self-tests & catalyst", "Every stored self-test, catalytic converter check, drive-cycle guide."),
}

/** One step of a guided test. */
data class Phase(
    val key: String,
    val title: String,
    val instruction: String,
    /** Measuring time; 0 = a single automatic action (e.g. reading self-tests). */
    val seconds: Int,
    val pids: List<Int> = emptyList(),
    /** Sample the adapter's ATRV voltage (as fast as possible when [pids] is empty). */
    val voltage: Boolean = false,
    /** Ask the user to tap before this step starts. */
    val waitForUser: Boolean = true,
    /** Usable with the engine off and the ignition off (no OBD answers needed). */
    val worksIgnitionOff: Boolean = false,
)

object TestPlans {
    fun phases(kind: TestKind): List<Phase> = when (kind) {
        TestKind.Battery -> listOf(
            Phase("rest", "Resting voltage",
                "Engine OFF. Best with the ignition OFF too (the adapter still measures). Ideally the car has sat for an hour.",
                seconds = 5, voltage = true, worksIgnitionOff = true),
            Phase("crank", "Cranking",
                "Get ready to start the engine. Tap Start, then start the engine within 5 seconds and let it idle.",
                seconds = 10, voltage = true, worksIgnitionOff = true),
            Phase("idle", "Charging at idle", "Engine idling, lights, A/C and blower OFF. Don't touch the pedal.",
                seconds = 20, pids = listOf(0x0C, 0x0D), voltage = true),
            Phase("load", "Charging with load",
                "Turn ON headlights and the blower on high, then hold the engine at about 2,500 rpm (parked) until the countdown ends.",
                seconds = 20, pids = listOf(0x0C, 0x0D), voltage = true),
        )
        TestKind.FuelTrim -> listOf(
            Phase("idle", "Warm idle", "Warm engine (10+ minute drive), parked, A/C off. Let it idle without touching the pedal.",
                seconds = 60, pids = listOf(0x0C, 0x0D, 0x05, 0x06, 0x07, 0x10)),
            Phase("high", "2,500 rpm", "Hold the engine steady at about 2,500 rpm (parked) until the countdown ends.",
                seconds = 45, pids = listOf(0x0C, 0x0D, 0x05, 0x06, 0x07, 0x10)),
        )
        TestKind.Evap -> listOf(
            Phase("idle", "Warm idle observation",
                "Warm engine (15+ minute drive; right after refuelling is best), parked, A/C off. Let it idle for 3 minutes.",
                seconds = 180, pids = listOf(0x0C, 0x0D, 0x05, 0x06, 0x07, 0x2E, 0x2F)),
        )
        TestKind.SelfTests -> listOf(
            Phase("read", "Read stored self-tests", "Ignition ON is enough. The app reads every self-test result the engine has stored.",
                seconds = 0, waitForUser = false),
            Phase("cat", "Catalytic converter check",
                "Warm engine, parked, idling. The app watches the downstream O2 sensor for 40 seconds.",
                seconds = 40, pids = listOf(0x0C, 0x0D, 0x05, 0x15)),
        )
    }
}
