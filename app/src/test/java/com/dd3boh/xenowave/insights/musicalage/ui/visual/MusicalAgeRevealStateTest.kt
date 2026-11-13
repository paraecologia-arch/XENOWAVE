package com.dd3boh.xenowave.insights.musicalage.ui.visual

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicalAgeRevealStateTest {
    @Test
    fun `reduced motion during an intermediate reveal lands on the complete card`() {
        assertFalse(MusicalAgeRevealState.initialFinished(reduceMotion = false))
        assertTrue(MusicalAgeRevealState.shouldAnimate(finished = false, reduceMotion = false))

        // The preference flips while progress is mid-flight.
        assertTrue(
            MusicalAgeRevealState.shouldFinishImmediately(
                finished = false,
                reduceMotion = true,
            ),
        )
        assertEquals(
            1f,
            MusicalAgeRevealState.reveal(
                reduceMotion = true,
                finished = false,
                animatedProgress = 0.42f,
            ),
            0f,
        )
        // The LaunchedEffect then marks it finished, so actions are visible.
        assertEquals(
            1f,
            MusicalAgeRevealState.reveal(
                reduceMotion = true,
                finished = true,
                animatedProgress = 0.42f,
            ),
            0f,
        )
    }

    @Test
    fun `skip and background finish immediately and do not loop`() {
        assertTrue(MusicalAgeRevealState.skip())
        assertTrue(MusicalAgeRevealState.onStopped())
        assertEquals(
            1f,
            MusicalAgeRevealState.reveal(
                reduceMotion = false,
                finished = MusicalAgeRevealState.onStopped(),
                animatedProgress = 0.2f,
            ),
            0f,
        )
    }

    @Test
    fun `a recomposition of an already finished session does not restart it`() {
        assertFalse(
            MusicalAgeRevealState.shouldAnimate(
                finished = true,
                reduceMotion = false,
            ),
        )
        // An explicit debug replay starts a fresh non-finished session.
        assertFalse(MusicalAgeRevealState.initialFinished(reduceMotion = false))
        // A static replay remains complete.
        assertTrue(MusicalAgeRevealState.initialFinished(reduceMotion = true))
    }
}
