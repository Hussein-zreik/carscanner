package com.crvdiag.elm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class AdapterCommandPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = ["ATZ", "ATI", "ATE0", "ATL0", "ATS0", "ATH1", "ATSP0", "ATSP6", "ATDPN", "ATRV", "ATST32", "ATAT1", "ATSH7DF", "ATSH7E0", "ATSH7E7"])
    fun `adapter-only commands are allowed`(cmd: String) {
        assertEquals(Verdict.Allowed, AdapterCommandPolicy.check(cmd))
    }

    @ParameterizedTest
    @ValueSource(strings = ["ATSH7E8", "ATSH7C0", "ATSH7B0", "ATSH18DAF110", "ATSH000", "ATSH7E", "ATPP0CSV0A", "ATPPFFON", "ATSD01", "ATMA", "ATFI", "ATBI", "ATCRA7E8", "ATSPD", "ATXYZ"])
    fun `risky or unknown AT commands are refused`(cmd: String) {
        assertTrue(AdapterCommandPolicy.check(cmd) is Verdict.Refused)
    }

    @Test
    fun `non-AT commands are left to other policies`() {
        assertEquals(Verdict.NotApplicable, AdapterCommandPolicy.check("0100"))
    }

    @Test
    fun `normalize strips spaces and uppercases`() {
        assertEquals("ATSP0", CommandPolicy.normalize(" at sp 0 "))
        assertEquals("010C", CommandPolicy.normalize("01 0c"))
    }

    @Test
    fun `composite refuses commands no policy covers`() {
        val composite = CompositePolicy(AdapterCommandPolicy)
        assertTrue(composite.check("2E1234") is Verdict.Refused)
        assertTrue(composite.check("") is Verdict.Refused)
        assertEquals(Verdict.Allowed, composite.check("ATZ"))
    }
}
