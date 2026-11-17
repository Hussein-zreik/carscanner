package com.crvdiag.app.console

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crvdiag.elm.GuardedSender
import com.crvdiag.obd.ConsolePolicy
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

/**
 * Raw adapter console. Every command goes through [GuardedSender] + [ConsolePolicy];
 * every byte in and out is recorded in [log] (also mirrored to logcat tag "CrvDiag").
 */
class ConsoleViewModel(application: Application) : AndroidViewModel(application) {

    val log = TrafficLog(sink = { Log.d(TAG, it) })

    private val _state = MutableStateFlow<LinkState>(LinkState.Disconnected)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _paired = MutableStateFlow<List<PairedDevice>>(emptyList())
    val paired: StateFlow<List<PairedDevice>> = _paired.asStateFlow()

    private val _bluetoothOff = MutableStateFlow(false)
    /** True when the tablet's Bluetooth radio is off; the screen offers to turn it on. */
    val bluetoothOff: StateFlow<Boolean> = _bluetoothOff.asStateFlow()

    @Volatile private var transport: Transport? = null
    @Volatile private var sender: GuardedSender? = null
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
        log.add(Direction.INFO, "Connecting: $label")

        viewModelScope.launch {
            try {
                link.connect()
                sender = GuardedSender(link, ConsolePolicy) { cmd, reason ->
                    log.add(Direction.INFO, "REFUSED $cmd: $reason")
                }
                _state.value = LinkState.Connected(label)
                log.add(Direction.INFO, "Connected")
                link.responses.collect { /* logged by LoggingTransport */ }
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
                sender = null
                transport = null
                withContext(NonCancellable) { link.close() }
            }
        }
    }

    fun disconnect() {
        val link = transport ?: return
        userClosed = true
        viewModelScope.launch { link.close() }
    }

    fun send(raw: String) {
        if (raw.isBlank()) return
        val s = sender ?: run {
            log.add(Direction.INFO, "Not connected")
            return
        }
        viewModelScope.launch {
            try {
                s.send(raw)
            } catch (e: IOException) {
                log.add(Direction.INFO, "Send failed: ${e.message}")
            }
        }
    }

    fun clearLog() = log.clear()

    override fun onCleared() {
        userClosed = true
    }

    companion object {
        const val TAG = "CrvDiag"
    }
}
