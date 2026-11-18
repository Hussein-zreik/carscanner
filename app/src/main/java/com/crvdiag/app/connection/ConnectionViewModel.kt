package com.crvdiag.app.connection

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crvdiag.elm.Elm327
import com.crvdiag.elm.ElmInitializer
import com.crvdiag.elm.ElmProtocol
import com.crvdiag.elm.ElmResult
import com.crvdiag.elm.InitOutcome
import com.crvdiag.elm.InitReport
import com.crvdiag.obd.protocolAwareConsolePolicy
import com.crvdiag.obd.EcuDiscovery
import com.crvdiag.obd.EcuInfo
import com.crvdiag.transport.BluetoothDevices
import com.crvdiag.transport.BluetoothOffException
import com.crvdiag.transport.BluetoothSppTransport
import com.crvdiag.transport.LoggingTransport
import com.crvdiag.transport.PairedDevice
import com.crvdiag.transport.TrafficLog
import com.crvdiag.transport.TrafficLog.Direction
import com.crvdiag.transport.Transport
import com.crvdiag.transport.WifiTcpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

sealed interface LinkState {
    data object Disconnected : LinkState
    data class Connecting(val label: String) : LinkState
    data class Connected(val label: String) : LinkState
    /** The link dropped and the app is reconnecting by itself. */
    data class Reconnecting(val label: String, val attempt: Int, val maxAttempts: Int) : LinkState
    data class Failed(val message: String) : LinkState
}

/** Plain-language stage of the startup, for the progress checklist. */
enum class InitPhase { Adapter, Car, Network }

/** Result of the automatic ELM327 startup shown in the "Car link" card. */
sealed interface CarLinkState {
    data object Idle : CarLinkState
    data class Initializing(val step: String, val phase: InitPhase) : CarLinkState
    data class Ready(val report: InitReport, val ecus: List<EcuInfo>, val protocolLocked: Boolean) : CarLinkState
    data class Failed(val step: String, val detail: String, val hint: String) : CarLinkState
}

/**
 * Adapter console. On connect it runs the ELM327 startup ([ElmInitializer]); afterwards raw
 * commands go through the same [Elm327] driver. Every command passes the protocol-aware console allow-list; every
 * byte is recorded in [log] (mirrored to logcat tag "CrvDiag").
 */
class ConnectionViewModel(application: Application) : AndroidViewModel(application) {

    val log = TrafficLog(sink = { Log.d(TAG, it) })
    private val protocolStore = ProtocolStore(application)
    private val linkPrefs = LinkPrefs(application)

    private val _savedLink = MutableStateFlow(linkPrefs.get())
    /** Last method that connected; the dashboard's Connect button reuses it. */
    val savedLink: StateFlow<SavedLink> = _savedLink.asStateFlow()

    private val _state = MutableStateFlow<LinkState>(LinkState.Disconnected)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _carLink = MutableStateFlow<CarLinkState>(CarLinkState.Idle)
    val carLink: StateFlow<CarLinkState> = _carLink.asStateFlow()

    private val _savedProtocol = MutableStateFlow(protocolStore.get())
    val savedProtocol: StateFlow<ElmProtocol?> = _savedProtocol.asStateFlow()

    private val _paired = MutableStateFlow<List<PairedDevice>>(emptyList())
    val paired: StateFlow<List<PairedDevice>> = _paired.asStateFlow()

    private val _bluetoothOff = MutableStateFlow(false)
    /** True when the tablet's Bluetooth radio is off; the screen offers to turn it on. */
    val bluetoothOff: StateFlow<Boolean> = _bluetoothOff.asStateFlow()

    @Volatile private var transport: Transport? = null
    @Volatile private var elm: Elm327? = null
    @Volatile private var userClosed = false
    @Volatile private var knownProtocol: ElmProtocol? = null
    private var reconnectJob: Job? = null

    fun refreshPairedDevices() {
        _paired.value = try {
            BluetoothDevices.paired(getApplication()).also { _bluetoothOff.value = false }
        } catch (e: BluetoothOffException) {
            // Log only on the transition, not on every refresh.
            if (!_bluetoothOff.value) log.add(Direction.INFO, "Tablet Bluetooth is off")
            _bluetoothOff.value = true
            emptyList()
        } catch (e: SecurityException) {
            log.add(Direction.INFO, "Bluetooth permission not granted")
            emptyList()
        } catch (e: IOException) {
            log.add(Direction.INFO, e.message ?: "Bluetooth unavailable")
            emptyList()
        }
    }

    fun connectBluetooth(device: PairedDevice) =
        connect("Bluetooth · ${device.name}", SavedLink.Bluetooth(device.name, device.address)) {
            BluetoothSppTransport(getApplication(), device.address)
        }

    fun connectWifi(host: String, port: Int) =
        connect("Wi-Fi · $host:$port", SavedLink.Wifi(host, port)) { WifiTcpTransport(getApplication(), host, port) }

    /** One-tap connect with the last method that worked (Wi-Fi 192.168.0.10:35000 by default). */
    fun connectLast() = when (val last = _savedLink.value) {
        is SavedLink.Wifi -> connectWifi(last.host, last.port)
        is SavedLink.Bluetooth -> connectBluetooth(PairedDevice(last.name, last.address))
    }

    private fun connect(label: String, saved: SavedLink, attempt: Int = 0, factory: () -> Transport) {
        if (attempt == 0) {
            val s = _state.value
            if (s is LinkState.Connecting || s is LinkState.Connected || s is LinkState.Reconnecting) return
            userClosed = false
        }
        val link = LoggingTransport(factory(), log)
        transport = link
        knownProtocol = protocolStore.get()
        _state.value = if (attempt == 0) LinkState.Connecting(label) else LinkState.Reconnecting(label, attempt, ReconnectPolicy.MAX_ATTEMPTS)
        _carLink.value = CarLinkState.Idle
        log.add(Direction.INFO, if (attempt == 0) "Connecting: $label" else "Reconnecting ($attempt/${ReconnectPolicy.MAX_ATTEMPTS}): $label")

        viewModelScope.launch {
            var wasReady = false
            var error: String? = null
            try {
                link.connect()
                // Protocol-aware allow-list: once the car is known to be 29-bit CAN, ATSH is refused.
                val driver = Elm327(link, protocolAwareConsolePolicy { knownProtocol }) { cmd, reason ->
                    log.add(Direction.INFO, "REFUSED $cmd: $reason")
                }
                driver.start(this)
                elm = driver
                linkPrefs.save(saved)
                _savedLink.value = saved
                _state.value = LinkState.Connected(label)
                log.add(Direction.INFO, "Connected")

                initialize(driver)
                wasReady = _carLink.value is CarLinkState.Ready

                driver.closed.await()?.let { throw it }
                if (!userClosed) error = "The adapter closed the connection"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!userClosed) error = e.message ?: e.javaClass.simpleName
            } finally {
                elm = null
                transport = null
                withContext(NonCancellable) { link.close() }
            }

            when {
                userClosed -> {
                    log.add(Direction.INFO, "Disconnected")
                    _state.value = LinkState.Disconnected
                }
                error == null -> _state.value = LinkState.Disconnected
                // A link that worked (or a reconnect in progress) dropped: try again by itself.
                (wasReady || attempt > 0) && attempt < ReconnectPolicy.MAX_ATTEMPTS -> {
                    log.add(Direction.INFO, "Link lost: $error")
                    val next = attempt + 1
                    _state.value = LinkState.Reconnecting(label, next, ReconnectPolicy.MAX_ATTEMPTS)
                    reconnectJob = viewModelScope.launch {
                        delay(ReconnectPolicy.delayMs(next))
                        if (!userClosed) connect(label, saved, next, factory)
                    }
                }
                else -> {
                    log.add(Direction.INFO, "Link error: $error")
                    val hint = LinkErrorHints.hintFor(error)
                    if (hint != null) log.add(Direction.INFO, "Hint: $hint")
                    val prefix = if (attempt > 0) "Lost the link and couldn't reconnect after $attempt tries. " else ""
                    _state.value = LinkState.Failed(prefix + (hint ?: error))
                }
            }
        }
    }

    /** Runs the CLAUDE.md startup sequence and fills the "Car link" card. */
    private suspend fun initialize(driver: Elm327) {
        val saved = protocolStore.get()
        val protocol = saved ?: ElmProtocol.AUTO
        log.add(Direction.INFO, "Init: start (protocol ${if (saved != null) "${saved.code} saved" else "auto"})")

        when (val outcome = ElmInitializer(driver).run(protocol) { step ->
            val phase = when {
                "(0100)" in step -> InitPhase.Car
                "(ATDPN)" in step -> InitPhase.Network
                else -> InitPhase.Adapter
            }
            _carLink.value = CarLinkState.Initializing(step, phase)
        }) {
            is InitOutcome.Success -> {
                val report = outcome.report
                val detected = report.detected?.protocol
                knownProtocol = detected
                val ecus = EcuDiscovery.fromPid00(report.pid00Lines, if (report.headersOn) detected else null)
                report.warnings.forEach { log.add(Direction.INFO, "Init warning: $it") }
                log.add(Direction.INFO, "Init: ${report.adapterId}, ${report.voltage}, protocol ${detected?.code} ${detected?.label}")
                ecus.forEach { ecu ->
                    log.add(
                        Direction.INFO,
                        "ECU ${ecu.responseId ?: "?"} (request ${ecu.requestId ?: "?"}): PIDs ${ecu.supportedPids.joinToString(",") { "%02X".format(it) }}",
                    )
                }
                // Pin the confirmed CAN protocol so future connections skip auto-search.
                if (detected != null && detected.isCan && saved == null) {
                    protocolStore.save(detected)
                    _savedProtocol.value = detected
                    log.add(Direction.INFO, "Init: protocol ${detected.code} saved; next connect uses ATSP${detected.code}")
                }
                _carLink.value = CarLinkState.Ready(report, ecus, protocolLocked = report.detected?.automatic == false)
            }
            is InitOutcome.Failure -> {
                outcome.warnings.forEach { log.add(Direction.INFO, "Init warning: $it") }
                val detail = describe(outcome.result)
                log.add(Direction.INFO, "Init failed at ${outcome.step}: $detail. ${outcome.hint}")
                _carLink.value = CarLinkState.Failed(outcome.step, detail, outcome.hint)
            }
        }
    }

    /** Forget the saved protocol; the next connect auto-detects again (ATSP0). */
    fun forgetProtocol() {
        protocolStore.clear()
        _savedProtocol.value = null
        log.add(Direction.INFO, "Saved protocol cleared; next connect will auto-detect")
    }

    fun disconnect() {
        userClosed = true
        reconnectJob?.cancel()
        if (_state.value is LinkState.Reconnecting && transport == null) {
            log.add(Direction.INFO, "Reconnect cancelled")
            _state.value = LinkState.Disconnected
            return
        }
        val link = transport ?: return
        viewModelScope.launch { link.close() }
    }

    fun send(raw: String) {
        if (raw.isBlank()) return
        val driver = elm ?: run {
            log.add(Direction.INFO, "Not connected")
            return
        }
        viewModelScope.launch {
            log.add(Direction.INFO, "→ ${describe(driver.execute(raw))}")
        }
    }

    fun clearLog() = log.clear()

    /** Screenshot tests only: show a given state without a car. */
    internal fun showPreviewState(link: LinkState, car: CarLinkState) {
        _state.value = link
        _carLink.value = car
    }

    /** The live driver for other screens (e.g. Trouble codes); null when not connected. */
    fun elmOrNull(): Elm327? = elm

    override fun onCleared() {
        userClosed = true
    }

    private fun describe(result: ElmResult): String = when (result) {
        is ElmResult.Ok -> "OK: " + result.lines.joinToString(" | ")
        ElmResult.NoData -> "NO DATA"
        ElmResult.Timeout -> "no reply (timeout)"
        is ElmResult.Error -> "error: ${result.message}"
    }

    companion object {
        const val TAG = "CrvDiag"
    }
}
