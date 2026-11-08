package com.dd3boh.xenowave.insights.musicalage.ui.visual

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicalAgeMotionPolicyTest {
    @Test
    fun `reduced or disabled system animation skips the reveal`() {
        assertFalse(shouldReduceMotion(1f, 1f))
        assertTrue(shouldReduceMotion(0f, 1f))
        assertTrue(shouldReduceMotion(1f, 0f))
        assertTrue(shouldReduceMotion(0.5f, 1f))
        assertTrue(shouldReduceMotion(1f, 0.5f))
    }
}
