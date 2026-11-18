package com.crvdiag.app.screens

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.crvdiag.app.cartest.CarTestScreen
import com.crvdiag.app.cartest.CarTestState
import com.crvdiag.app.cartest.CarTestViewModel
import com.crvdiag.app.cartest.StepResult
import com.crvdiag.app.cartest.StepStatus
import com.crvdiag.app.codes.CodesScreen
import com.crvdiag.app.codes.CodesState
import com.crvdiag.app.codes.CodesViewModel
import com.crvdiag.app.codes.ReportState
import com.crvdiag.app.report.PdfReportWriter
import com.crvdiag.app.report.ReportFormatter
import com.crvdiag.app.report.ReportInput
import com.crvdiag.app.report.ReportType
import com.crvdiag.obd.MisfireCount
import com.crvdiag.obd.ScanData
import java.time.ZonedDateTime
import com.crvdiag.app.connection.CarLinkState
import com.crvdiag.app.connection.ConnectionScreen
import com.crvdiag.app.connection.ConnectionViewModel
import com.crvdiag.app.connection.InitPhase
import com.crvdiag.app.connection.LinkState
import com.crvdiag.app.console.AdvancedScreen
import com.crvdiag.app.dashboard.DashboardScreen
import com.crvdiag.app.dashboard.linkSummary
import com.crvdiag.app.live.LiveDataScreen
import com.crvdiag.app.live.LiveUiState
import com.crvdiag.app.live.RecordingInfo
import com.crvdiag.app.maintenance.MaintenanceScreen
import com.crvdiag.app.misfire.IdleTestState
import com.crvdiag.app.misfire.MisfireScreen
import com.crvdiag.app.misfire.MisfireViewModel
import com.crvdiag.app.tests.GuidedState
import com.crvdiag.app.tests.GuidedTestScreen
import com.crvdiag.app.tests.TestKind
import com.crvdiag.app.tests.TestsHubScreen
import com.crvdiag.app.tests.TestsViewModel
import com.crvdiag.app.maintenance.MaintenanceViewModel
import com.crvdiag.app.recording.LogsScreen
import com.crvdiag.app.recording.LogsViewModel
import com.crvdiag.data.DriveLogEntity
import com.crvdiag.data.MaintenanceDefaults
import com.crvdiag.data.OdometerReadingEntity
import com.crvdiag.data.ServiceRecordEntity
import java.time.LocalDate
import com.crvdiag.app.live.LiveViewModel
import com.crvdiag.obd.LiveChecks
import com.crvdiag.obd.Pids
import com.crvdiag.app.ui.AppShell
import com.crvdiag.app.ui.Destination
import com.crvdiag.app.ui.theme.CrvDiagTheme
import com.crvdiag.elm.DetectedProtocol
import com.crvdiag.elm.ElmProtocol
import com.crvdiag.elm.InitReport
import com.crvdiag.obd.DiagnosticsReport
import com.crvdiag.obd.EcuDiscovery
import com.crvdiag.obd.Readiness
import com.crvdiag.transport.TrafficLog.Direction
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders each screen at the Galaxy Tab A9+ landscape size (1280×800 dp, 1.5×) to PNG files
 * for visual review. Opt-in: ./gradlew :app:testDebugUnitTest -Pscreenshots
 * Data is SYNTHETIC, modelled on this car's 2026-11-17 state.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-hdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var connection: ConnectionViewModel
    private lateinit var codes: CodesViewModel
    private lateinit var service: MaintenanceViewModel

    private val report = InitReport(
        adapterId = "ELM327 v1.5",
        voltage = "12.1V",
        detected = DetectedProtocol(ElmProtocol.CAN_29_500, automatic = true),
        pid00Lines = listOf("18DAF111064100BE3FA813", "18DAF11D064100983A8003"),
        headersOn = true,
        warnings = emptyList(),
    )
    private val ready = CarLinkState.Ready(report, EcuDiscovery.fromPid00(report.pid00Lines, ElmProtocol.CAN_29_500), false)
    private val diagnostics = DiagnosticsReport(
        readiness = mapOf("18DAF111" to Readiness.decode(0x00, 0x07, 0xE5, 0xA5), "18DAF11D" to Readiness.decode(0, 0, 0, 0)),
        stored = mapOf("18DAF111" to emptyList(), "18DAF11D" to emptyList()),
        pending = mapOf("18DAF111" to listOf("P145C"), "18DAF11D" to emptyList()),
        freezeFrames = emptyMap(),
        problems = emptyList(),
    )

    @Before
    fun setUp() {
        assumeTrue("Run with -Pscreenshots", System.getProperty("screenshots") == "true")
        val app = ApplicationProvider.getApplicationContext<Application>()
        connection = ConnectionViewModel(app)
        codes = CodesViewModel(app)
        service = MaintenanceViewModel(app)
        // SYNTHETIC example history: some items known, some not (owner's situation).
        val today = LocalDate.of(2026, 11, 17)
        fun day(daysAgo: Long) = today.minusDays(daysAgo).toEpochDay()
        service.showPreview(
            MaintenanceDefaults.ITEMS,
            listOf(
                ServiceRecordEntity(1, "oil", day(150), 148_900, "Valvoline, 0W-20"),
                ServiceRecordEntity(2, "tire_rotation", day(150), 148_900),
                ServiceRecordEntity(3, "atf", day(1_100), 121_500, "Drain & fill"),
                ServiceRecordEntity(4, "brake_fluid", day(900), null),
                ServiceRecordEntity(5, "air_filter", day(300), 145_200),
            ),
            listOf(OdometerReadingEntity(1, day(150), 148_900), OdometerReadingEntity(2, day(7), 153_480)),
            today,
        )
    }

    private fun shoot(name: String, destination: Destination, recording: Boolean = false, screen: @androidx.compose.runtime.Composable () -> Unit) {
        val status = linkSummary(connection.state.value, connection.carLink.value).second
        compose.setContent {
            CrvDiagTheme {
                AppShell(destination, {}, status, recording) { screen() }
            }
        }
        compose.waitForIdle()
        val dir = File(System.getProperty("screenshotDir")!!).apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun connected() {
        connection.showPreviewState(LinkState.Connected("Wi-Fi · 192.168.0.10:35000"), ready)
        codes.showPreviewState(CodesState.Done(diagnostics))
    }

    @Test fun dashboardConnected() {
        connected()
        shoot("1-dashboard-connected", Destination.Dashboard) { DashboardScreen(connection, codes, service, {}, {}) }
    }

    @Test fun dashboardDisconnected() {
        shoot("2-dashboard-disconnected", Destination.Dashboard) { DashboardScreen(connection, codes, service, {}, {}) }
    }

    @Test fun codes() {
        connected()
        shoot("3-codes", Destination.Codes) { CodesScreen(connection, codes, null) {} }
    }

    @Test fun connectionConnecting() {
        connection.showPreviewState(
            LinkState.Connected("Wi-Fi · 192.168.0.10:35000"),
            CarLinkState.Initializing("Asking the car which data it supports (0100)", InitPhase.Car),
        )
        shoot("4-connection-connecting", Destination.Connection) { ConnectionScreen(connection) }
    }

    @Test fun connectionReady() {
        connected()
        shoot("5-connection-ready", Destination.Connection) { ConnectionScreen(connection) }
    }

    /** Warm idle, values in the range seen on this car (SYNTHETIC). */
    private fun liveState(speedKmh: Int): LiveUiState {
        val raw = mapOf(
            0x0C to listOf(0x0D, 0x48), 0x05 to listOf(0x7F), 0x04 to listOf(0x40), 0x11 to listOf(0x2B),
            0x0D to listOf(speedKmh), 0x06 to listOf(0x82), 0x07 to listOf(0x7D), 0x15 to listOf(0x8C, 0xFF),
            0x34 to listOf(0x80, 0x00, 0x80, 0x00), 0x42 to listOf(0x37, 0x78), 0x0B to listOf(0x1E),
            0x10 to listOf(0x01, 0x2C), 0x0F to listOf(0x48), 0x0E to listOf(0x8C), 0x03 to listOf(0x02, 0x00),
            0x2E to listOf(0x26), 0x2F to listOf(0x9A), 0x46 to listOf(0x3A),
        )
        val values = raw.mapValues { (pid, bytes) -> Pids.decode(pid, bytes)!! }
        val history = values.mapValues { (pid, v) ->
            val base = v.value ?: 0.0
            List(60) { i -> if (pid == 0x15) 0.68 + 0.04 * kotlin.math.sin(i / 4.0) else base * (1 + 0.03 * kotlin.math.sin(i / 5.0)) }
        }
        return LiveUiState(running = true, values = values, history = history, updatesPerSecond = 6.4,
            checks = LiveChecks.evaluate(history))
    }

    @Test fun live() {
        connected()
        val live = LiveViewModel(ApplicationProvider.getApplicationContext())
        live.showPreviewState(liveState(0).copy(recording = RecordingInfo(System.currentTimeMillis() - 754_000, 4_812)))
        shoot("6-live", Destination.Live, recording = true) { LiveDataScreen(connection, live) }
    }

    @Test fun logs() {
        val vm = LogsViewModel(ApplicationProvider.getApplicationContext())
        val day = 1_795_437_000_000L // 2026-11-17 afternoon
        vm.showPreview(listOf(
            DriveLogEntity(2, day + 7_200_000, day + 8_460_000, "drive_b.csv", "drive_b.log", 7_880),
            DriveLogEntity(1, day, day + 1_320_000, "drive_a.csv", "drive_a.log", 8_255),
        ))
        shoot("9-logs", Destination.Logs) { LogsScreen(vm) }
    }

    private fun reportInput() = ReportInput(
        type = ReportType.PreRepair, notes = "Before EVAP purge valve check at the shop.",
        createdAt = ZonedDateTime.now(), appVersion = "0.8.0", adapterId = "ELM327 v1.5",
        protocolLabel = ElmProtocol.CAN_29_500.label, voltage = "12.1V", odometerMiles = 153_704, odometerEstimated = true,
        ecus = ready.ecus,
        data = ScanData(diagnostics, permanent = mapOf("18DAF111" to emptyList()), vin = "5J6RE4H50BL017022",
            calibrationIds = mapOf("18DAF111" to listOf("37805-RZA-A630")), ecuNames = mapOf("18DAF111" to "ECM-EngineControl"),
            misfires = (1..4).map { MisfireCount(it, 0, 0) }),
        describeCode = { com.crvdiag.honda.HondaDtcText.describe(it) ?: com.crvdiag.obd.DtcText.describe(it) },
    )

    @Test fun report() {
        connected()
        val input = reportInput()
        val blocks = ReportFormatter.build(input)
        codes.showPreviewState(CodesState.Done(diagnostics), ReportState.Done(ReportType.PreRepair, blocks, File("report_PreRepair.pdf")))
        shoot("11-report", Destination.Codes) { CodesScreen(connection, codes, null) {} }
        // Also try the real PDF. Robolectric has no native PdfDocument ("document is closed"),
        // so on the JVM this is skipped; the PDF is verified on the tablet.
        val dir = File(System.getProperty("screenshotDir")!!)
        try {
            PdfReportWriter.write(blocks, "CR-V Diag · Pre-repair scan · VIN 5J6RE4H50BL017022", File(dir, "report.pdf"))
        } catch (e: IllegalStateException) {
            println("PDF not rendered under Robolectric: ${e.message}")
        }
    }

    @Test fun codesMilWithoutCode() {
        connected()
        codes.showPreviewState(CodesState.Done(diagnostics.copy(
            readiness = mapOf("18DAF111" to Readiness.decode(0x81, 0x07, 0xE5, 0xA5)),
            stored = emptyMap(), storedRead = false, problems = listOf("Stored codes: no answer from the car"),
        )))
        shoot("12-codes-mil-unread", Destination.Codes) { CodesScreen(connection, codes, null) {} }
    }

    @Test fun carTest() {
        connected()
        val vm = CarTestViewModel(ApplicationProvider.getApplicationContext())
        vm.showPreviewState(CarTestState.Done(listOf(
            StepResult("Connection", StepStatus.Pass, "Wi-Fi · ELM327 v1.5 · battery 12.3V"),
            StepResult("Car network", StepStatus.Pass, "ISO 15765-4 CAN (29 bit, 500 kbaud); 2 unit(s): 18DAF111, 18DAF11D"),
            StepResult("Trouble codes & readiness", StepStatus.Pass, "Light ON · stored: P145C · pending: none"),
            StepResult("VIN", StepStatus.Pass, "5J6RE4H50BL017022 (matches this car, check digit valid)"),
            StepResult("Permanent codes & misfire counters", StepStatus.Warn, "permanent codes not read · misfires: cyl 1=0, cyl 2=0, cyl 3=0, cyl 4=0"),
            StepResult("Live data", StepStatus.Pass, "112 updates (5.6/s), 14 different readings, 100% answered, several readings per request"),
            StepResult("PDF report", StepStatus.Pass, "report_HealthCheck_2026-11-18_21-04-10.pdf (9 KB)"),
        ), File("car-test_2026-11-18_21-03-02.txt"), File("report.pdf")))
        shoot("13-car-test", Destination.CarTest) { CarTestScreen(vm) {} }
    }

    @Test fun misfire() {
        connected()
        val vm = MisfireViewModel(ApplicationProvider.getApplicationContext())
        // SYNTHETIC result: rough feel, random misfires with rich trims (purge-valve pattern).
        val before = com.crvdiag.obd.MisfireSnapshot(0, mapOf(1 to 0, 2 to 0, 3 to 0, 4 to 0), emptyMap())
        val after = com.crvdiag.obd.MisfireSnapshot(1, mapOf(1 to 2, 2 to 0, 3 to 3, 4 to 1), emptyMap())
        val verdict = com.crvdiag.obd.MisfireAnalysis.evaluate(before, after, List(40) { if (it % 2 == 0) 690.0 else 800.0 },
            listOf(-8.5), -4.0, 88.0, emptyList())
        vm.showPreview(IdleTestState(newMisfires = verdict.newMisfires, verdict = verdict),
            DriveLogEntity(3, 0, 1, "a.csv", "a.log", 4800, "Misfires during drive: cyl 1: 0 · cyl 2: 0 · cyl 3: 2 · cyl 4: 0"))
        shoot("14-misfire", Destination.Tests) { MisfireScreen(vm, connected = true, onStart = {}, onOpenLive = {}) }
    }

    @Test fun testsHub() {
        connected()
        shoot("15-tests-hub", Destination.Tests) { TestsHubScreen({}, {}) }
    }

    @Test fun batteryTest() {
        connected()
        val vm = TestsViewModel(ApplicationProvider.getApplicationContext())
        // SYNTHETIC: this car's low key-on reading, a borderline crank, Honda-style low idle charge.
        val verdict = com.crvdiag.obd.BatteryAnalysis.evaluate(List(5) { 12.15 }, false, listOf(12.1, 9.9, 9.8, 11.2, 13.1),
            List(10) { 13.1 }, List(10) { 14.0 }, 2450.0)
        vm.showPreview(GuidedState(kind = TestKind.Battery, doneSteps = 4, verdict = verdict))
        shoot("16-battery-test", Destination.Tests) { GuidedTestScreen(TestKind.Battery, vm, {}, {}) }
    }

    @Test fun selfTests() {
        connected()
        val vm = TestsViewModel(ApplicationProvider.getApplicationContext())
        val monitors = listOf(
            com.crvdiag.obd.MonitorResults(0x01, listOf(com.crvdiag.obd.MonitorTest(0x01, 0x87, 0x01, 38, 0, 250))),
            com.crvdiag.obd.MonitorResults(0x02, listOf(com.crvdiag.obd.MonitorTest(0x02, 0x9A, 0x0B, 104, 0, 1270))),
            com.crvdiag.obd.MonitorResults(0x21, listOf(com.crvdiag.obd.MonitorTest(0x21, 0xA1, 0x0B, 0, 0, 0))),
            com.crvdiag.obd.MonitorResults(0x3D, listOf(com.crvdiag.obd.MonitorTest(0x3D, 0xB9, 0x30, 0, 0, 0))),
        )
        val readiness = Readiness.decode(0x00, 0x07, 0xE5, 0xA5)
        val f = listOf(
            com.crvdiag.obd.DiagFinding("Stored self-tests", com.crvdiag.obd.Finding.Good, "2 passed, 2 not run yet."),
            com.crvdiag.obd.DiagFinding("Catalytic converter self-test", com.crvdiag.obd.Finding.Info, "Not run yet since the codes were cleared."),
        ) + readiness.incomplete.map { com.crvdiag.obd.DiagFinding("To finish: ${it.name}", com.crvdiag.obd.Finding.Info, com.crvdiag.obd.Mode06Catalog.driveCycleTip(it.name)) }
        vm.showPreview(GuidedState(kind = TestKind.SelfTests, doneSteps = 2, selfTests = monitors, readiness = readiness,
            verdict = com.crvdiag.obd.TestVerdict("4 self-test(s) still to finish", f, pending = true)))
        shoot("17-self-tests", Destination.Tests) { GuidedTestScreen(TestKind.SelfTests, vm, {}, {}) }
    }

    @Test fun maintenance() {
        shoot("10-maintenance", Destination.Maintenance) { MaintenanceScreen(service) }
    }

    @Test fun liveMoving() {
        connected()
        val live = LiveViewModel(ApplicationProvider.getApplicationContext())
        live.showPreviewState(liveState(60))
        shoot("8-live-moving", Destination.Live) { LiveDataScreen(connection, live) }
    }

    @Test fun advanced() {
        connected()
        listOf("ATZ\r" to Direction.TX, "\r\rELM327 v1.5\r\r>" to Direction.RX, "0100\r" to Direction.TX,
            "18DAF111064100BE3FA813\r18DAF11D064100983A8003\r\r>" to Direction.RX, "ECU 18DAF111 (request 18DA11F1)" to Direction.INFO)
            .forEach { (t, d) -> connection.log.add(d, t) }
        shoot("7-advanced", Destination.Advanced) { AdvancedScreen(connection) }
    }
}
