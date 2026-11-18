package com.crvdiag.app.capture

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crvdiag.app.connection.ConnectionViewModel
import com.crvdiag.app.connection.SavedLink
import com.crvdiag.honda.capture.CaptureRelay
import com.crvdiag.honda.capture.CaptureSummary
import com.crvdiag.transport.LoggingTransport
import com.crvdiag.transport.RecordedLog
import com.crvdiag.transport.TrafficLog
import com.crvdiag.transport.TrafficLog.Direction
import com.crvdiag.transport.WifiTcpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

sealed interface CaptureState {
    data object Idle : CaptureState
    data class Starting(val step: String) : CaptureState
    data class Running(
        val tabletIp: String?,
        val port: Int,
        val stats: CaptureRelay.Stats,
        val file: File,
    ) : CaptureState
    data class Stopped(val file: File, val summary: CaptureSummary) : CaptureState
    data class Failed(val message: String) : CaptureState
}

/**
 * Phase 2 capture mode: the tablet stands between Car Scanner (iPhone) and the adapter, relays
 * read requests only (CapturePolicy) and records everything to filesDir/captures/.
 * The app's normal car link is closed first: the adapter accepts one TCP client.
 */
class CaptureViewModel(application: Application) : AndroidViewModel(application) {
    val dir = File(application.filesDir, "captures")

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    private var job: Job? = null
    private var server: ServerSocket? = null
    private var relay: CaptureRelay? = null
    private var writer: BufferedWriter? = null
    private var file: File? = null
    @Volatile private var clientSocket: java.net.Socket? = null
    @Volatile private var stopping = false

    fun start(connection: ConnectionViewModel) {
        if (job?.isActive == true) return
        stopping = false
        job = viewModelScope.launch {
            try {
                _state.value = CaptureState.Starting("Closing the app's own car link")
                connection.disconnect()
                delay(1_500) // the adapter accepts one client: let the old socket close

                val saved = connection.savedLink.value as? SavedLink.Wifi
                val host = saved?.host ?: WifiTcpTransport.DEFAULT_HOST
                val adapterPort = saved?.port ?: WifiTcpTransport.DEFAULT_PORT
                val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
                val out = withContext(Dispatchers.IO) {
                    dir.mkdirs()
                    File(dir, "capture_$stamp.log").also { f ->
                        writer = f.bufferedWriter().apply {
                            write("# CR-V Diag capture (Phase 2) — $stamp\n")
                            write("# Client: another diagnostic app relayed through the tablet. Read-only CapturePolicy.\n")
                            write("# Adapter: Wi-Fi $host:$adapterPort\n")
                            flush()
                        }
                    }
                }
                file = out
                val log = TrafficLog(capacity = 500, sink = ::writeLine)

                _state.value = CaptureState.Starting("Connecting to the adapter ($host)")
                val adapter = LoggingTransport(WifiTcpTransport(getApplication(), host, adapterPort), log)
                adapter.connect()
                log.add(Direction.INFO, "Capture: adapter connected")
                val r = CaptureRelay(adapter, log).also { relay = it }
                r.start(viewModelScope)

                val listen = withContext(Dispatchers.IO) {
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(LISTEN_PORT))
                    }
                }
                server = listen
                val ip = withContext(Dispatchers.IO) { wifiIpv4() }
                log.add(Direction.INFO, "Capture: waiting for the client on ${ip ?: "?"}:$LISTEN_PORT")
                launch { r.stats.collectLatest { s -> _state.value = CaptureState.Running(ip, LISTEN_PORT, s, out) } }

                while (isActive) {
                    val client = withContext(Dispatchers.IO) { listen.accept() }
                    log.add(Direction.INFO, "Capture: client connected from ${client.inetAddress.hostAddress}")
                    client.tcpNoDelay = true
                    clientSocket = client
                    client.use { r.serve(it.getInputStream(), it.getOutputStream()) }
                    clientSocket = null
                    log.add(Direction.INFO, "Capture: client disconnected")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!stopping) {
                    val message = e.message ?: e.javaClass.simpleName
                    finish()
                    _state.value = CaptureState.Failed(
                        "$message. The capture so far is saved under Logs & reports." +
                            if ("ENETUNREACH" in message || "connect" in message) " Is the tablet on the adapter's Wi-Fi?" else "",
                    )
                }
            }
        }
    }

    fun stop() {
        stopping = true
        viewModelScope.launch {
            finish()
            job?.cancel()
        }
    }

    /** Closes everything and appends the summary to the capture file. Safe to call twice. */
    private suspend fun finish() {
        val f = file ?: return
        file = null
        val r = relay
        relay = null
        val summary = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
            runCatching { server?.close() }
            server = null
            runCatching { clientSocket?.close() }
            clientSocket = null
            r?.stop()
            synchronized(this@CaptureViewModel) { runCatching { writer?.flush() } }
            val s = CaptureSummary.of(RecordedLog.parse(f.readText()))
            synchronized(this@CaptureViewModel) {
                runCatching {
                    writer?.apply {
                        s.asComments().forEach { write(it + "\n") }
                        close()
                    }
                }
                writer = null
            }
            s
        }
        _state.value = CaptureState.Stopped(f, summary)
    }

    private fun writeLine(line: String) {
        synchronized(this) {
            runCatching { writer?.apply { write(line + "\n"); flush() } }
        }
    }

    /** Screenshot tests only. */
    internal fun showPreview(state: CaptureState) {
        _state.value = state
    }

    fun reset() {
        if (_state.value is CaptureState.Stopped || _state.value is CaptureState.Failed) _state.value = CaptureState.Idle
    }

    override fun onCleared() {
        runCatching { server?.close() }
        runCatching { clientSocket?.close() }
        relay?.stop()
        synchronized(this) { runCatching { writer?.close() } }
    }

    companion object {
        /** Port the client app is pointed at (same as the adapter's, so only the IP changes). */
        const val LISTEN_PORT = 35000

        /** The tablet's IPv4 address on Wi-Fi (the adapter's network), for the client app. */
        fun wifiIpv4(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && it.name.startsWith("wlan") }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address }?.hostAddress
        }.getOrNull()
    }
}
