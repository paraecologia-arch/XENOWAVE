package com.dd3boh.xenowave.playback

import com.dd3boh.xenowave.constants.MediaSessionConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaCommandPolicyTest {
    @Test
    fun `custom commands are only announced to the own controller`() {
        assertTrue(MediaCommandPolicy.commandsFor(true).isNotEmpty())
        assertTrue(MediaCommandPolicy.commandsFor(false).isEmpty())
    }

    @Test
    fun `execution matches the announcement policy`() {
        assertTrue(
            MediaCommandPolicy.canExecute(
                isOwnController = true,
                action = MediaSessionConstants.ACTION_TOGGLE_LIKE,
            )
        )
        assertFalse(
            MediaCommandPolicy.canExecute(
                isOwnController = false,
                action = MediaSessionConstants.ACTION_TOGGLE_LIKE,
            )
        )
        assertFalse(MediaCommandPolicy.canExecute(true, "UNKNOWN_ACTION"))
        assertTrue(MediaCommandPolicy.canExposeBinder(true))
        assertFalse(MediaCommandPolicy.canExposeBinder(false))
        assertEquals(5, MediaCommandPolicy.commandsFor(true).size)
    }
}
