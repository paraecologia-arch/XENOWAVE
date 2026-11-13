package com.dd3boh.xenowave.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import com.dd3boh.xenowave.ui.component.items.YouTubeListItem
import com.dd3boh.xenowave.ui.screens.search.OnlineSearchFilterChips
import com.dd3boh.xenowave.ui.theme.XenoWaveTheme
import com.dd3boh.xenowave.utils.search.OnlineSearchSource
import com.dd3boh.xenowave.viewmodels.OnlineSearchViewModel
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.pages.SearchResult
import com.zionhuang.innertube.pages.SearchSummary
import com.zionhuang.innertube.pages.SearchSummaryPage
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.atomic.AtomicInteger

object DebugSearchFilterProbe {
    val songRequests = AtomicInteger(0)
    val videoRequests = AtomicInteger(0)
    val cancellations = AtomicInteger(0)

    fun reset() {
        songRequests.set(0)
        videoRequests.set(0)
        cancellations.set(0)
    }
}

/**
 * Deterministic integration host: the real [OnlineSearchViewModel], production
 * search chips and production result rows, backed only by a debug source.
 */
class DebugSearchFilterHostActivity : ComponentActivity() {
    lateinit var viewModel: OnlineSearchViewModel
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugSearchFilterProbe.reset()
        viewModel = OnlineSearchViewModel(
            savedStateHandle = SavedStateHandle(mapOf("query" to "debug filters")),
            searchSource = DebugFilterSearchSource(),
        )
        setContent {
            XenoWaveTheme(
                context = LocalContext.current,
                darkTheme = true,
                highContrastCompat = false,
            ) {
                IntegratedFilterSearch(viewModel)
            }
        }
    }
}

@Composable
private fun IntegratedFilterSearch(viewModel: OnlineSearchViewModel) {
    val selected by viewModel.filter.collectAsState()
    val state = selected?.value?.let { viewModel.filterStates[it] }

    Column(Modifier.fillMaxSize().padding(top = 16.dp)) {
        OnlineSearchFilterChips(
            currentValue = selected,
            onValueUpdate = { viewModel.filter.value = it },
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            state?.isLoading == true -> Text(
                text = "DEBUG_FILTER_LOADING",
                modifier = Modifier.testTag("filter-loading").padding(16.dp),
            )

            state?.error != null -> Text(
                text = "DEBUG_FILTER_ERROR",
                modifier = Modifier.testTag("filter-error").padding(16.dp),
            )

            else -> state?.items.orEmpty().forEach { item ->
                YouTubeListItem(
                    item = item,
                    isActive = false,
                    isPlaying = false,
                    badges = {},
                    trailingContent = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private class DebugFilterSearchSource : OnlineSearchSource {
    override suspend fun searchSummary(query: String): Result<SearchSummaryPage> =
        Result.success(SearchSummaryPage(listOf(SearchSummary("", emptyList()))))

    override suspend fun search(
        query: String,
        filter: YouTube.SearchFilter,
    ): Result<SearchResult> = when (filter) {
        YouTube.SearchFilter.FILTER_SONG -> {
            val request = DebugSearchFilterProbe.songRequests.incrementAndGet()
            if (request == 1) {
                try {
                    awaitCancellation()
                } finally {
                    DebugSearchFilterProbe.cancellations.incrementAndGet()
                }
            } else {
                Result.success(
                    SearchResult(listOf(song("song-result-1", "Song Result 1"), song("song-result-2", "Song Result 2")))
                )
            }
        }

        YouTube.SearchFilter.FILTER_VIDEO -> {
            DebugSearchFilterProbe.videoRequests.incrementAndGet()
            Result.success(
                SearchResult(listOf(song("video-result-1", "Video Result 1"), song("video-result-2", "Video Result 2")))
            )
        }

        else -> Result.success(SearchResult(emptyList()))
    }

    override suspend fun searchContinuation(continuation: String): Result<SearchResult> =
        Result.failure(IllegalStateException("No continuation in debug filter host"))

    private fun song(id: String, title: String) = SongItem(
        id = id,
        title = title,
        artists = emptyList(),
        thumbnail = "",
    )
}
