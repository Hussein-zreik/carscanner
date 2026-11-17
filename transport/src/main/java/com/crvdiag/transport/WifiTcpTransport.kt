package com.crvdiag.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * TCP link to a Wi-Fi ELM327 adapter (tablet joined to the adapter's own hotspot,
 * usually 192.168.0.10:35000). The socket is bound to the Wi-Fi network so Android
 * does not route it over another network because the hotspot has no internet.
 * Only ever connects to the adapter address the user entered.
 */
class WifiTcpTransport(
    context: Context,
    private val host: String = DEFAULT_HOST,
    private val port: Int = DEFAULT_PORT,
    private val connectTimeoutMs: Int = 5_000,
) : StreamTransport() {

    private val appContext = context.applicationContext

    @Volatile private var socket: Socket? = null

    override fun open(): Streams {
        val s = Socket()
        socket = s // Published first so close() can abort a pending connect.
        try {
            findWifiNetwork()?.bindSocket(s)
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            return Streams(s.getInputStream(), s.getOutputStream())
        } catch (e: Exception) {
            runCatching { s.close() }
            throw e
        }
    }

    override fun closeLink() {
        socket?.close()
    }

    /** Finds a connected Wi-Fi network, including ones without internet access. */
    private fun findWifiNetwork(): Network? {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return null
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val found = CountDownLatch(1)
        var network: Network? = null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                network = n
                found.countDown()
            }
        }
        cm.registerNetworkCallback(request, callback)
        try {
            found.await(2, TimeUnit.SECONDS)
        } finally {
            cm.unregisterNetworkCallback(callback)
        }
        return network
    }

    companion object {
        const val DEFAULT_HOST = "192.168.0.10"
        const val DEFAULT_PORT = 35000
    }
}
