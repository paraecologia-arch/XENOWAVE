package com.dd3boh.xenowave.search

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.debug.DebugSearchStateHostActivity
import com.dd3boh.xenowave.debug.SearchStateProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchStateDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<DebugSearchStateHostActivity>()

    @Before
    fun resetProbe() {
        SearchStateProbe.reset()
    }

    @Test
    fun resultRowsEmptyErrorAndRetryActionsAreVisible() {
        compose.onNodeWithText("Synthetic Song")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Synthetic Playlist")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(text(R.string.no_results_found))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(text(R.string.search_load_error))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(text(R.string.search_load_more_error))
            .performScrollTo()
            .assertIsDisplayed()

        val retries = compose.onAllNodesWithText(text(R.string.retry))
        assertTrue(retries.fetchSemanticsNodes().size >= 2)
        retries[0].performScrollTo().performClick()
        retries[1].performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(1, SearchStateProbe.retryCalls.get())
        assertEquals(1, SearchStateProbe.loadMoreRetryCalls.get())
    }

    private fun text(resource: Int): String =
        compose.activity.getString(resource)
}
