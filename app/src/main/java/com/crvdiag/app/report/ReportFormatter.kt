package com.crvdiag.app.report

import com.crvdiag.app.ui.EcuNames
import com.crvdiag.honda.CarProfile
import com.crvdiag.obd.EcuInfo
import com.crvdiag.obd.ScanData
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

enum class ReportType(val label: String, val purpose: String) {
    PreRepair("Pre-repair scan", "State of the car before repair work."),
    PostRepair("Post-repair scan", "State of the car after repair work, to compare with the pre-repair scan."),
    HealthCheck("Health check", "General condition check."),
}

enum class Tone { Normal, Good, Warn, Bad, Muted }

/** Device-independent report content, rendered both on screen and to PDF. */
sealed interface Block {
    data class Title(val title: String, val subtitle: String) : Block
    data class Heading(val text: String) : Block
    data class Fields(val rows: List<Field>) : Block
    data class Table(val headers: List<String>, val rows: List<Row>, val weights: List<Float>) : Block
    data class Text(val text: String, val tone: Tone = Tone.Normal) : Block
}

data class Field(val label: String, val value: String, val tone: Tone = Tone.Normal)
data class Row(val cells: List<String>, val tone: Tone = Tone.Normal)

data class ReportInput(
    val type: ReportType,
    val notes: String?,
    val createdAt: ZonedDateTime,
    val appVersion: String,
    val adapterId: String?,
    val protocolLabel: String?,
    val voltage: String?,
    val odometerMiles: Int?,
    val odometerEstimated: Boolean,
    val ecus: List<EcuInfo>,
    val data: ScanData,
    val describeCode: (String) -> String,
)

/** Builds the scan report. Systems that were not scanned are always listed as NOT SCANNED. */
object ReportFormatter {
    private val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm")

    fun summary(input: ReportInput): String {
        val d = input.data.diagnostics
        val mil = d.readiness.values.any { it.milOn }
        val main = d.readiness.values.maxByOrNull { r -> r.monitors.count { it.available } }
        val available = main?.monitors?.filter { it.available }.orEmpty()
        return listOfNotNull(
            if (mil) "MIL ON" else "MIL off",
            "${d.stored.values.flatten().size} stored",
            "${d.pending.values.flatten().size} pending",
            "${input.data.permanent.values.flatten().size} permanent",
            main?.let { "${available.count { it.complete }}/${available.size} self-tests" },
        ).joinToString(" · ")
    }

    fun build(input: ReportInput): List<Block> {
        val d = input.data.diagnostics
        val label = { ecu: String -> EcuNames.label(ecu, input.ecus) }
        val blocks = mutableListOf<Block>()

        blocks += Block.Title("CR-V Diag · ${input.type.label}", "${CarProfile.DESCRIPTION} · ${WHEN.format(input.createdAt)}")
        blocks += Block.Text(input.type.purpose + " Read-only scan: nothing in the car was changed.", Tone.Muted)

        // Vehicle
        blocks += Block.Heading("Vehicle")
        val vin = input.data.vin
        blocks += Block.Fields(listOfNotNull(
            Field("VIN reported by the car", vin ?: "Not reported", if (vin == null) Tone.Warn else Tone.Normal),
            vin?.let {
                if (com.crvdiag.obd.VehicleInfo.vinCheckDigitValid(it)) Field("VIN check digit", "Valid", Tone.Good)
                else Field("VIN check digit", "INVALID: re-read the VIN", Tone.Bad)
            },
            vin?.let {
                if (it == CarProfile.VIN) Field("Matches this car's VIN", "Yes", Tone.Good)
                else Field("Matches this car's VIN", "NO: car profile is ${CarProfile.VIN}. The engine computer may have been replaced or reprogrammed.", Tone.Bad)
            },
            Field(
                "Odometer",
                input.odometerMiles?.let { "%,d mi".format(it) + if (input.odometerEstimated) " (owner's reading, estimated for today)" else " (owner's reading)" }
                    ?: "Not recorded in the app",
            ),
            Field("Odometer note", "Title-branded \"not actual\"; mileage is the owner's own record.", Tone.Muted),
            Field("Battery at connection", input.voltage ?: "Not measured"),
            Field("Adapter / protocol", listOfNotNull(input.adapterId, input.protocolLabel).joinToString(" · ").ifEmpty { "Unknown" }),
        ))

        // Coverage
        blocks += Block.Heading("Systems covered by this scan")
        val scanned = input.ecus.ifEmpty { null }?.map { ecu ->
            Row(listOf(label(ecu.responseId ?: "?") + (ecu.responseId?.let { " ($it)" } ?: ""), "Scanned (generic OBD-II)"), Tone.Good)
        } ?: listOf(Row(listOf("Engine / transmission", "Scanned (generic OBD-II)"), Tone.Good))
        val notScanned = listOf("Airbags (SRS)", "ABS / stability control (VSA)", "Tire pressure (TPMS)").map {
            Row(listOf(it, "NOT SCANNED: needs OBDLink MX+ (K-line). Codes here are unknown, not absent."), Tone.Warn)
        } + Row(listOf("Immobilizer, audio", "Not scanned (outside this app's scope)"), Tone.Muted)
        blocks += Block.Table(listOf("System", "Status"), scanned + notScanned, listOf(0.38f, 0.62f))

        // Codes
        val mil = d.readiness.values.any { it.milOn }
        blocks += Block.Heading("Trouble codes")
        blocks += Block.Fields(listOf(Field("Check-engine light (MIL)", if (mil) "ON" else "OFF", if (mil) Tone.Bad else Tone.Good)))
        val codeRows = buildList {
            d.stored.forEach { (ecu, codes) -> codes.forEach { add(Row(listOf(it, "Stored", input.describeCode(it), label(ecu)), Tone.Bad)) } }
            input.data.permanent.forEach { (ecu, codes) -> codes.forEach { add(Row(listOf(it, "Permanent", input.describeCode(it), label(ecu)), Tone.Bad)) } }
            d.pending.forEach { (ecu, codes) -> codes.forEach { add(Row(listOf(it, "Pending", input.describeCode(it), label(ecu)), Tone.Warn)) } }
        }
        if (codeRows.isEmpty()) blocks += Block.Text("No stored, pending or permanent codes in the scanned systems.", Tone.Good)
        else blocks += Block.Table(listOf("Code", "Type", "Description", "Module"), codeRows, listOf(0.12f, 0.14f, 0.48f, 0.26f))
        blocks += Block.Text(
            "Stored = confirmed fault. Pending = seen once, not confirmed. Permanent = confirmed fault that clearing codes " +
                "cannot erase; it disappears only after the car re-tests the system and it passes.",
            Tone.Muted,
        )

        // Freeze frames
        d.freezeFrames.forEach { (ecu, frame) ->
            blocks += Block.Heading("Snapshot when ${frame.dtc} was set (${label(ecu)})")
            blocks += Block.Fields(frame.values.map { Field(it.name, it.display()) })
        }

        // Readiness
        d.readiness.values.maxByOrNull { r -> r.monitors.count { it.available } }?.let { r ->
            val available = r.monitors.filter { it.available }
            blocks += Block.Heading("Emission self-tests (readiness): ${available.count { it.complete }} of ${available.size} complete")
            blocks += Block.Table(
                listOf("Self-test", "Status"),
                available.map { Row(listOf(it.name, if (it.complete) "Complete" else "Not complete"), if (it.complete) Tone.Good else Tone.Warn) },
                listOf(0.6f, 0.4f),
            )
            if (r.incomplete.isNotEmpty()) {
                blocks += Block.Text("Incomplete self-tests usually mean codes were cleared or the battery was disconnected recently. " +
                    "An emissions inspection may reject the car until they complete.", Tone.Muted)
            }
        }

        // Misfires
        if (input.data.misfires.isNotEmpty()) {
            blocks += Block.Heading("Misfire counters (engine self-test results)")
            blocks += Block.Table(
                listOf("Cylinder", "This drive", "Average, last 10 drives"),
                input.data.misfires.map { m ->
                    val bad = (m.thisCycle ?: 0) > 0 || (m.average10Cycles ?: 0) > 0
                    Row(listOf("${m.cylinder}", m.thisCycle?.toString() ?: "—", m.average10Cycles?.toString() ?: "—"), if (bad) Tone.Warn else Tone.Good)
                },
                listOf(0.3f, 0.3f, 0.4f),
            )
        }

        // Module identification
        if (input.data.calibrationIds.isNotEmpty() || input.data.ecuNames.isNotEmpty()) {
            blocks += Block.Heading("Module identification")
            blocks += Block.Fields(
                input.data.ecuNames.map { (ecu, name) -> Field("${label(ecu)} name", name) } +
                    input.data.calibrationIds.map { (ecu, ids) -> Field("${label(ecu)} software (CAL ID)", ids.joinToString(", ")) },
            )
        }

        input.notes?.takeIf { it.isNotBlank() }?.let {
            blocks += Block.Heading("Notes")
            blocks += Block.Text(it)
        }
        if (d.problems.isNotEmpty()) {
            blocks += Block.Heading("Data that could not be read")
            d.problems.forEach { blocks += Block.Text("• $it", Tone.Warn) }
        }
        blocks += Block.Text(
            "Generated by CR-V Diag ${input.appVersion} using a generic ELM327-compatible adapter. " +
                "Generic OBD-II data only; verify critical findings with professional equipment (e.g. Honda HDS).",
            Tone.Muted,
        )
        return blocks
    }
}
