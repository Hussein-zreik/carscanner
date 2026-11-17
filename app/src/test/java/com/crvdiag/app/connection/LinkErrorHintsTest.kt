package com.crvdiag.app.connection

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LinkErrorHintsTest {
    @Test
    fun `real Tab A9+ errors get a hint`() {
        // Exact messages from testdata/2026-11-17_wifi_first-contact.log
        val unreachable = "failed to connect to /192.168.0.10 (port 35000) from /:: (port 0) after 4999ms: " +
            "connect failed: ENETUNREACH (Network is unreachable)"
        assertTrue(LinkErrorHints.hintFor(unreachable)!!.contains("adapter's Wi-Fi"))
        assertTrue(LinkErrorHints.hintFor("Software caused connection abort")!!.contains("Link dropped"))
    }

    @Test
    fun `unknown errors get no hint`() {
        assertNull(LinkErrorHints.hintFor("something new"))
    }
}
