package com.dd3boh.xenowave.ui.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupGateTest {
    @Test
    fun sequenceCanOnlyBeAcquiredOnce() {
        val gate = StartupGate()

        assertTrue(gate.acquire())
        assertFalse(gate.acquire())
    }
}
