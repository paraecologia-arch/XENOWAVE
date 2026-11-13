package com.dd3boh.xenowave.search

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.debug.DebugSearchFilterHostActivity
import com.dd3boh.xenowave.debug.DebugSearchFilterProbe
import com.zionhuang.innertube.YouTube
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchFilterCancellationDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<DebugSearchFilterHostActivity>()

    @Test
    fun songsLoadingThenVideosThenSongsRecoversThroughProductionChips() {
        compose.onNodeWithText(compose.activity.getString(R.string.filter_songs)).performClick()
        compose.waitUntil(5_000) { DebugSearchFilterProbe.songRequests.get() == 1 }
        compose.onNodeWithTag("filter-loading").assertIsDisplayed()

        compose.onNodeWithText(compose.activity.getString(R.string.filter_videos)).performClick()
        compose.waitUntil(5_000) { DebugSearchFilterProbe.videoRequests.get() == 1 }
        compose.onNodeWithText("Video Result 1").assertIsDisplayed()

        compose.onNodeWithText(compose.activity.getString(R.string.filter_songs)).performClick()
        compose.waitUntil(5_000) { DebugSearchFilterProbe.songRequests.get() == 2 }
        compose.onNodeWithText("Song Result 1").assertIsDisplayed()

        val state = compose.activity.viewModel.filterStates.getValue(
            YouTube.SearchFilter.FILTER_SONG.value
        )
        assertEquals(1, DebugSearchFilterProbe.cancellations.get())
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertEquals(listOf("song-result-1", "song-result-2"), state.items.map { it.id })
    }
}
