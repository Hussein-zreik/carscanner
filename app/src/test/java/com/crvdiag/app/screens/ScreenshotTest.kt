package com.crvdiag.app.screens

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.crvdiag.app.codes.CodesScreen
import com.crvdiag.app.codes.CodesState
import com.crvdiag.app.codes.CodesViewModel
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

    private val report = InitReport(
        adapterId = "ELM327 v1.5",
        voltage = "12.1V",
        detected = DetectedProtocol(ElmProtocol.CAN_11_500, automatic = true),
        pid00Lines = listOf("7E8064100BE3FA813", "7E9064100983A8003"),
        headersOn = true,
        warnings = emptyList(),
    )
    private val ready = CarLinkState.Ready(report, EcuDiscovery.fromPid00(report.pid00Lines, ElmProtocol.CAN_11_500), false)
    private val diagnostics = DiagnosticsReport(
        readiness = mapOf("7E8" to Readiness.decode(0x00, 0x07, 0xE5, 0xA5), "7E9" to Readiness.decode(0, 0, 0, 0)),
        stored = mapOf("7E8" to emptyList(), "7E9" to emptyList()),
        pending = mapOf("7E8" to listOf("P145C"), "7E9" to emptyList()),
        freezeFrames = emptyMap(),
        problems = emptyList(),
    )

    @Before
    fun setUp() {
        assumeTrue("Run with -Pscreenshots", System.getProperty("screenshots") == "true")
        val app = ApplicationProvider.getApplicationContext<Application>()
        connection = ConnectionViewModel(app)
        codes = CodesViewModel()
    }

    private fun shoot(name: String, destination: Destination, screen: @androidx.compose.runtime.Composable () -> Unit) {
        val status = linkSummary(connection.state.value, connection.carLink.value).second
        compose.setContent {
            CrvDiagTheme {
                AppShell(destination, {}, status) { screen() }
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
        shoot("1-dashboard-connected", Destination.Dashboard) { DashboardScreen(connection, codes, {}, {}) }
    }

    @Test fun dashboardDisconnected() {
        shoot("2-dashboard-disconnected", Destination.Dashboard) { DashboardScreen(connection, codes, {}, {}) }
    }

    @Test fun codes() {
        connected()
        shoot("3-codes", Destination.Codes) { CodesScreen(connection, codes) {} }
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
        live.showPreviewState(liveState(0))
        shoot("6-live", Destination.Live) { LiveDataScreen(connection, live) }
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
            "7E8064100BE3FA813 \r7E9064100983A8003 \r\r>" to Direction.RX, "ECU 7E8 (request 7E0)" to Direction.INFO)
            .forEach { (t, d) -> connection.log.add(d, t) }
        shoot("7-advanced", Destination.Advanced) { AdvancedScreen(connection) }
    }
}
