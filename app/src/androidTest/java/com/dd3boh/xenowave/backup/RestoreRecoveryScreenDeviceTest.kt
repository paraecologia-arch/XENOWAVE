
package com.dd3boh.xenowave.backup

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.debug.DebugRestoreRecoveryHostActivity
import com.dd3boh.xenowave.debug.RestoreRecoveryProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-level checks for the blocked-restore screen.
 *
 * The test host sets the 2x font scale in `onCreate` and is recreated by a
 * real rotation. Both exits must be reachable (scrolling when needed), must
 * expose a click action, and must actually invoke their callbacks. The
 * callbacks are isolated fakes, so no real database, preference or recovery
 * file is touched.
 *
 * TalkBack speech itself remains a manual pass (see the validation matrix);
 * the semantics asserted here are the ones the screen reader consumes.
 */
@RunWith(AndroidJUnit4::class)
class RestoreRecoveryScreenDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<DebugRestoreRecoveryHostActivity>()

    @Before
    fun resetProbe() {
        RestoreRecoveryProbe.reset()
    }

    @Test
    fun bothExitsAreVisibleAndInteractiveAtLargeFontScale() {
        val retry = compose.onNodeWithText(text(R.string.restore_retry))
        retry.performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        compose.waitForIdle()

        assertEquals(1, RestoreRecoveryProbe.retryCalls.get())
        compose.onNodeWithText(text(R.string.restore_unresolved_still))
            .performScrollTo()
            .assertIsDisplayed()

        val keep = compose.onNodeWithText(text(R.string.restore_keep_current))
        keep.performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        compose.waitForIdle()

        assertEquals(1, RestoreRecoveryProbe.keepCalls.get())
        assertEquals(1, RestoreRecoveryProbe.resolvedCalls.get())
    }

    @Test
    fun recreationAfterRotationKeepsBothChoicesReachable() {
        var before: DebugRestoreRecoveryHostActivity? = null
        compose.activityRule.scenario.onActivity { before = it }

        val current = compose.activity.resources.configuration.orientation
        val requested = if (current == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        val expected = if (requested == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
            Configuration.ORIENTATION_LANDSCAPE
        } else {
            Configuration.ORIENTATION_PORTRAIT
        }

        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = requested
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.activity.resources.configuration.orientation == expected
        }
        compose.waitForIdle()

        compose.activityRule.scenario.onActivity { after ->
            assertNotSame("rotation must recreate the test activity", before, after)
            assertEquals(expected, after.resources.configuration.orientation)
        }

        compose.onNodeWithText(text(R.string.restore_retry))
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        compose.waitForIdle()

        assertEquals(1, RestoreRecoveryProbe.retryCalls.get())
        compose.onNodeWithText(text(R.string.restore_keep_current))
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    private fun text(resource: Int): String =
        compose.activity.getString(resource)
}
