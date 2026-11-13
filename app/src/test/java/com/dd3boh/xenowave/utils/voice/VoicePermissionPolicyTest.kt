package com.dd3boh.xenowave.utils.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePermissionPolicyTest {

    @Test
    fun `granted permission opens voice directly`() {
        assertEquals(
            VoicePermissionAction.OPEN_VOICE,
            VoicePermissionPolicy.action(hasPermission = true, requestInFlight = false),
        )
    }

    @Test
    fun `rapid taps do not duplicate an in-flight request`() {
        assertEquals(
            VoicePermissionAction.IGNORE_REQUEST_IN_FLIGHT,
            VoicePermissionPolicy.action(hasPermission = false, requestInFlight = true),
        )
    }

    @Test
    fun `missing permission requests once`() {
        assertEquals(
            VoicePermissionAction.REQUEST_PERMISSION,
            VoicePermissionPolicy.action(hasPermission = false, requestInFlight = false),
        )
    }

    @Test
    fun `settings shortcut is only offered after a denial`() {
        assertFalse(VoicePermissionPolicy.shouldOfferSettings(false, false))
        assertFalse(VoicePermissionPolicy.shouldOfferSettings(true, true))
        assertTrue(VoicePermissionPolicy.shouldOfferSettings(true, false))
    }

    @Test
    fun `rapid retaps during the system transition are ignored`() {
        assertTrue(VoicePermissionPolicy.isCooldownActive(1_000L, 1_500L))
        assertFalse(
            VoicePermissionPolicy.isCooldownActive(
                1_000L,
                1_000L + VoicePermissionPolicy.REQUEST_COOLDOWN_MILLIS,
            )
        )
        assertFalse(VoicePermissionPolicy.isCooldownActive(0L, 1_000L))
    }
}
