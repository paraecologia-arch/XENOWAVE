package com.dd3boh.xenowave.backup

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreConsumerGuardTest {
    @After
    fun tearDown() {
        RestoreRecoveryGate.clear()
    }

    @Test
    fun `blocked state returns the default without running the data access`() {
        RestoreRecoveryGate.applyOutcome(RestoreOutcome.UNRESOLVED)
        var ran = false

        val value = RestoreConsumerGuard.ifAllowed("default") {
            ran = true
            "live"
        }

        assertEquals("default", value)
        assertFalse(ran)
        assertFalse(RestoreConsumerGuard.canOpenData())
    }

    @Test
    fun `resolved state allows data access`() {
        RestoreRecoveryGate.applyOutcome(RestoreOutcome.SUCCESS)
        var ran = false

        val value = RestoreConsumerGuard.ifAllowed("default") {
            ran = true
            "live"
        }

        assertEquals("live", value)
        assertTrue(ran)
        assertTrue(RestoreConsumerGuard.canOpenData())
    }
}
