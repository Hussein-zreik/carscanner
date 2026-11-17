package com.crvdiag.app.console

import android.content.Context
import androidx.core.content.edit
import com.crvdiag.elm.ElmProtocol

/**
 * Remembers the protocol confirmed on the car so later connections use ATSP<n> directly
 * instead of auto-search (auto-search may probe the K-line: SRS / VSA / immobilizer).
 */
class ProtocolStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("elm", Context.MODE_PRIVATE)

    fun get(): ElmProtocol? = prefs.getString(KEY, null)?.firstOrNull()?.let(ElmProtocol::byCode)

    /** Only CAN protocols are saved; the K-line protocols are never pinned. */
    fun save(protocol: ElmProtocol) {
        if (protocol.isCan) prefs.edit { putString(KEY, protocol.code.toString()) }
    }

    fun clear() = prefs.edit { remove(KEY) }

    private companion object {
        const val KEY = "confirmedProtocol"
    }
}
