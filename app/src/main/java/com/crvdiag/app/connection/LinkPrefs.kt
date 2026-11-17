package com.crvdiag.app.connection

import android.content.Context
import androidx.core.content.edit
import com.crvdiag.transport.WifiTcpTransport

/** The last connection method that worked, for one-tap Connect on the dashboard. */
sealed interface SavedLink {
    data class Wifi(val host: String, val port: Int) : SavedLink
    data class Bluetooth(val name: String, val address: String) : SavedLink
}

class LinkPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("link", Context.MODE_PRIVATE)

    fun get(): SavedLink = when (prefs.getString("type", "wifi")) {
        "bt" -> SavedLink.Bluetooth(prefs.getString("name", "") ?: "", prefs.getString("address", "") ?: "")
        else -> SavedLink.Wifi(
            prefs.getString("host", WifiTcpTransport.DEFAULT_HOST) ?: WifiTcpTransport.DEFAULT_HOST,
            prefs.getInt("port", WifiTcpTransport.DEFAULT_PORT),
        )
    }

    fun save(link: SavedLink) = prefs.edit {
        when (link) {
            is SavedLink.Wifi -> putString("type", "wifi").putString("host", link.host).putInt("port", link.port)
            is SavedLink.Bluetooth -> putString("type", "bt").putString("name", link.name).putString("address", link.address)
        }
    }
}
