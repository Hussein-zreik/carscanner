package com.crvdiag.app.console

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
import com.crvdiag.obd.ConsolePolicy
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
    data class Failed(val message: String) : LinkState
}

/** Result of the automatic ELM327 startup shown in the "Car link" card. */
sealed interface CarLinkState {
    data object Idle : CarLinkState
    data class Initializing(val step: String) : CarLinkState
    data class Ready(val report: InitReport, val ecus: List<EcuInfo>, val protocolLocked: Boolean) : CarLinkState
    data class Failed(val step: String, val detail: String, val hint: String) : CarLinkState
}

/**
 * Adapter console. On connect it runs the ELM327 startup ([ElmInitializer]); afterwards raw
 * commands go through the same [Elm327] driver. Every command passes [ConsolePolicy]; every
 * byte is recorded in [log] (mirrored to logcat tag "CrvDiag").
 */
class ConsoleViewModel(application: Application) : AndroidViewModel(application) {

    val log = TrafficLog(sink = { Log.d(TAG, it) })
    private val protocolStore = ProtocolStore(application)

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
        connect("Bluetooth · ${device.name}") { BluetoothSppTransport(getApplication(), device.address) }

    fun connectWifi(host: String, port: Int) =
        connect("Wi-Fi · $host:$port") { WifiTcpTransport(getApplication(), host, port) }

    private fun connect(label: String, factory: () -> Transport) {
        if (_state.value is LinkState.Connecting || _state.value is LinkState.Connected) return
        val link = LoggingTransport(factory(), log)
        transport = link
        userClosed = false
        _state.value = LinkState.Connecting(label)
        _carLink.value = CarLinkState.Idle
        log.add(Direction.INFO, "Connecting: $label")

        viewModelScope.launch {
            try {
                link.connect()
                val driver = Elm327(link, ConsolePolicy) { cmd, reason ->
                    log.add(Direction.INFO, "REFUSED $cmd: $reason")
                }
                driver.start(this)
                elm = driver
                _state.value = LinkState.Connected(label)
                log.add(Direction.INFO, "Connected")

                initialize(driver)

                driver.closed.await()?.let { throw it }
                log.add(Direction.INFO, "Link closed")
                _state.value = LinkState.Disconnected
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (userClosed) {
                    log.add(Direction.INFO, "Disconnected")
                    _state.value = LinkState.Disconnected
                } else {
                    val msg = e.message ?: e.javaClass.simpleName
                    log.add(Direction.INFO, "Link error: $msg")
                    val hint = LinkErrorHints.hintFor(msg)
                    if (hint != null) log.add(Direction.INFO, "Hint: $hint")
                    _state.value = LinkState.Failed(hint ?: msg)
                }
            } finally {
                elm = null
                transport = null
                withContext(NonCancellable) { link.close() }
            }
        }
    }

    /** Runs the CLAUDE.md startup sequence and fills the "Car link" card. */
    private suspend fun initialize(driver: Elm327) {
        val saved = protocolStore.get()
        val protocol = saved ?: ElmProtocol.AUTO
        log.add(Direction.INFO, "Init: start (protocol ${if (saved != null) "${saved.code} saved" else "auto"})")

        when (val outcome = ElmInitializer(driver).run(protocol) { step ->
            _carLink.value = CarLinkState.Initializing(step)
        }) {
            is InitOutcome.Success -> {
                val report = outcome.report
                val detected = report.detected?.protocol
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
        val link = transport ?: return
        userClosed = true
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
