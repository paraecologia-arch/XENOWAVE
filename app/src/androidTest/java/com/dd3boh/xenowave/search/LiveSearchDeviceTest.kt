package com.dd3boh.xenowave.search

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dd3boh.xenowave.MainActivity
import com.dd3boh.xenowave.visual.DeviceTestOutput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real, anonymous provider smoke test through the production search screen.
 * It proves the screen offers results for a decade query instead of the old
 * "No results found" state. The deterministic empty/error/retry states are
 * covered by [SearchStateDeviceTest].
 */
@RunWith(AndroidJUnit4::class)
class LiveSearchDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun decadeQueryShowsResultsInAllSongsAndVideos() {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithContentDescription("Search")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithContentDescription("Search")[0]
            .performClick()
            .performTextInput("rock dos anos 70")
        compose.onAllNodesWithContentDescription("Search")[0].performImeAction()

        compose.waitUntil(60_000) {
            compose.onAllNodesWithText("Songs", substring = true)
                .fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Músicas", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
        }
        assertNoResultsAbsent()
        DeviceTestOutput.capture(compose, "search-live-tudo.png")

        val songs = if (compose.onAllNodesWithText("Songs", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        ) {
            "Songs"
        } else {
            "Músicas"
        }
        compose.onAllNodesWithText(songs, substring = true)[0].performClick()
        compose.waitForIdle()
        assertNoResultsAbsent()
        DeviceTestOutput.capture(compose, "search-live-musicas.png")

        val videos = if (compose.onAllNodesWithText("Videos", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        ) {
            "Videos"
        } else {
            "Vídeos"
        }
        compose.onAllNodesWithText(videos, substring = true)[0].performClick()
        compose.waitForIdle()
        assertNoResultsAbsent()
        DeviceTestOutput.capture(compose, "search-live-videos.png")
    }

    private fun assertNoResultsAbsent() {
        compose.onAllNodesWithText("No results found", substring = true)
            .assertCountEquals(0)
        compose.onAllNodesWithText("Nenhum resultado encontrado", substring = true)
            .assertCountEquals(0)
    }
}
