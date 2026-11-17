package com.crvdiag.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.IOException
import java.util.UUID

/** The tablet's Bluetooth radio is switched off; the user can enable it from the app. */
class BluetoothOffException : IOException("Bluetooth is off")

/** A bonded (paired) Bluetooth device, as shown in the device picker. */
data class PairedDevice(val name: String, val address: String)

object BluetoothDevices {
    /**
     * Devices already paired in Android Settings. Requires BLUETOOTH_CONNECT on Android 12+;
     * throws SecurityException if it is missing, IOException if Bluetooth is unavailable or off.
     */
    @SuppressLint("MissingPermission") // Caller requests BLUETOOTH_CONNECT first.
    fun paired(context: Context): List<PairedDevice> {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("This device has no Bluetooth")
        if (!adapter.isEnabled) throw BluetoothOffException()
        return adapter.bondedDevices
            .map { PairedDevice(it.name ?: it.address, it.address) }
            .sortedBy { it.name }
    }
}

/**
 * Bluetooth Classic serial (SPP/RFCOMM) link to an ELM327 adapter that is already paired
 * in Android Settings. Discovery is never started (no BLUETOOTH_SCAN needed).
 */
class BluetoothSppTransport(
    context: Context,
    private val address: String,
) : StreamTransport() {

    private val appContext = context.applicationContext

    @Volatile private var socket: BluetoothSocket? = null

    @SuppressLint("MissingPermission") // Caller requests BLUETOOTH_CONNECT first.
    override fun open(): Streams {
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("This device has no Bluetooth")
        if (!adapter.isEnabled) throw BluetoothOffException()
        val device = adapter.getRemoteDevice(address)

        val connected = try {
            connectSocket(device.createRfcommSocketToServiceRecord(SPP_UUID))
        } catch (secure: IOException) {
            // Many ELM327 clones only accept an insecure (unauthenticated) RFCOMM link.
            try {
                connectSocket(device.createInsecureRfcommSocketToServiceRecord(SPP_UUID))
            } catch (insecure: IOException) {
                insecure.addSuppressed(secure)
                throw insecure
            }
        }
        return Streams(connected.inputStream, connected.outputStream)
    }

    @SuppressLint("MissingPermission")
    private fun connectSocket(candidate: BluetoothSocket): BluetoothSocket {
        socket = candidate // Published first so close() can abort a pending connect.
        try {
            candidate.connect()
            return candidate
        } catch (e: IOException) {
            runCatching { candidate.close() }
            throw e
        }
    }

    override fun closeLink() {
        socket?.close()
    }

    companion object {
        /** Standard Serial Port Profile UUID used by ELM327 Bluetooth adapters. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
