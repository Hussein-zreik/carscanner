package com.crvdiag.obd

/**
 * Plain-language text for common generic (SAE J2012) codes relevant to a 4-cylinder gasoline
 * engine. Deliberately small: unknown codes say so instead of guessing.
 */
object DtcText {
    private val generic = mapOf(
        "P0101" to "Mass air flow (MAF) sensor range/performance",
        "P0102" to "Mass air flow (MAF) sensor circuit low",
        "P0103" to "Mass air flow (MAF) sensor circuit high",
        "P0106" to "Manifold pressure (MAP) sensor range/performance",
        "P0107" to "Manifold pressure (MAP) sensor circuit low",
        "P0108" to "Manifold pressure (MAP) sensor circuit high",
        "P0111" to "Intake air temperature sensor range/performance",
        "P0112" to "Intake air temperature sensor circuit low",
        "P0113" to "Intake air temperature sensor circuit high",
        "P0116" to "Coolant temperature sensor range/performance",
        "P0117" to "Coolant temperature sensor circuit low",
        "P0118" to "Coolant temperature sensor circuit high",
        "P0122" to "Throttle position sensor A circuit low",
        "P0123" to "Throttle position sensor A circuit high",
        "P0128" to "Coolant below thermostat regulating temperature",
        "P0130" to "O2 sensor circuit (bank 1 sensor 1)",
        "P0131" to "O2 sensor circuit low voltage (bank 1 sensor 1)",
        "P0132" to "O2 sensor circuit high voltage (bank 1 sensor 1)",
        "P0133" to "O2 sensor slow response (bank 1 sensor 1)",
        "P0134" to "O2 sensor no activity (bank 1 sensor 1)",
        "P0135" to "O2 sensor heater circuit (bank 1 sensor 1)",
        "P0136" to "O2 sensor circuit (bank 1 sensor 2)",
        "P0137" to "O2 sensor circuit low voltage (bank 1 sensor 2)",
        "P0138" to "O2 sensor circuit high voltage (bank 1 sensor 2)",
        "P0139" to "O2 sensor slow response (bank 1 sensor 2)",
        "P0141" to "O2 sensor heater circuit (bank 1 sensor 2)",
        "P0171" to "System too lean (bank 1)",
        "P0172" to "System too rich (bank 1)",
        "P0300" to "Random/multiple cylinder misfire",
        "P0301" to "Cylinder 1 misfire",
        "P0302" to "Cylinder 2 misfire",
        "P0303" to "Cylinder 3 misfire",
        "P0304" to "Cylinder 4 misfire",
        "P0325" to "Knock sensor 1 circuit (bank 1)",
        "P0335" to "Crankshaft position sensor A circuit",
        "P0340" to "Camshaft position sensor A circuit",
        "P0420" to "Catalyst efficiency below threshold (bank 1)",
        "P0440" to "EVAP system malfunction",
        "P0441" to "EVAP system incorrect purge flow",
        "P0442" to "EVAP system small leak",
        "P0443" to "EVAP purge control valve circuit",
        "P0446" to "EVAP vent control circuit",
        "P0451" to "EVAP tank pressure sensor range/performance",
        "P0452" to "EVAP tank pressure sensor low",
        "P0453" to "EVAP tank pressure sensor high",
        "P0455" to "EVAP system large leak",
        "P0456" to "EVAP system very small leak",
        "P0457" to "EVAP leak detected (fuel cap loose/off)",
        "P0496" to "EVAP system high purge flow",
        "P0497" to "EVAP system low purge flow",
        "P0500" to "Vehicle speed sensor A",
        "P0505" to "Idle control system",
        "P0562" to "System voltage low",
        "P0563" to "System voltage high",
        "P0700" to "Transmission control system (check transmission codes)",
        "P2195" to "O2 sensor signal stuck lean (bank 1 sensor 1)",
        "P2196" to "O2 sensor signal stuck rich (bank 1 sensor 1)",
        "P2646" to "Rocker arm (VTEC) actuator performance / stuck off (bank 1)",
        "P2647" to "Rocker arm (VTEC) actuator stuck on (bank 1)",
    )

    fun describe(code: String): String = generic[code] ?: when {
        code.length != 5 -> "Unknown code format"
        code[0] == 'P' && (code[1] == '1' || code[1] == '3') -> "Manufacturer-specific (Honda) code: not in the app's list yet"
        code[0] == 'P' -> "Generic powertrain code: not in the app's list yet"
        code[0] == 'C' -> "Chassis code"
        code[0] == 'B' -> "Body code"
        else -> "Network code"
    }
}
