package com.dd3boh.xenowave.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.ui.component.EmptyPlaceholder
import com.dd3boh.xenowave.ui.component.items.YouTubeListItem
import com.dd3boh.xenowave.ui.icons.XenoNavigationIcons
import com.dd3boh.xenowave.ui.screens.search.LoadMoreErrorItem
import com.dd3boh.xenowave.ui.theme.XenoWaveTheme
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import java.util.concurrent.atomic.AtomicInteger

object SearchStateProbe {
    val retryCalls = AtomicInteger(0)
    val loadMoreRetryCalls = AtomicInteger(0)

    fun reset() {
        retryCalls.set(0)
        loadMoreRetryCalls.set(0)
    }
}

/**
 * Debug-only host for the result-list states that do not need a player.
 * It renders the production list item and the production empty/error/retry
 * components with synthetic data.
 */
class DebugSearchStateHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            XenoWaveTheme(
                context = LocalContext.current,
                darkTheme = true,
                highContrastCompat = false,
            ) {
                SearchStateGallery()
            }
        }
    }
}

@Composable
private fun SearchStateGallery() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Synthetic search result")
        YouTubeListItem(
            item = SongItem(
                id = "synthetic-song",
                title = "Synthetic Song",
                artists = listOf(Artist("Synthetic Artist", null)),
                thumbnail = "",
            ),
            isActive = false,
            isPlaying = false,
            badges = {},
            trailingContent = {},
            modifier = Modifier.fillMaxWidth(),
        )
        YouTubeListItem(
            item = PlaylistItem(
                id = "synthetic-playlist",
                title = "Synthetic Playlist",
                author = Artist("Synthetic Author", null),
                songCountText = "10 songs",
                thumbnail = "",
                playEndpoint = null,
                shuffleEndpoint = null,
                radioEndpoint = null,
            ),
            isActive = false,
            isPlaying = false,
            badges = {},
            trailingContent = {},
            modifier = Modifier.fillMaxWidth(),
        )

        Box(Modifier.fillMaxWidth().height(220.dp)) {
            EmptyPlaceholder(
                icon = XenoNavigationIcons.Search,
                text = androidx.compose.ui.res.stringResource(R.string.no_results_found),
                showBrandMark = true,
            )
        }

        Box(Modifier.fillMaxWidth().height(280.dp)) {
            EmptyPlaceholder(
                icon = XenoNavigationIcons.Search,
                text = androidx.compose.ui.res.stringResource(R.string.search_load_error),
                showBrandMark = true,
                actionLabel = androidx.compose.ui.res.stringResource(R.string.retry),
                onAction = { SearchStateProbe.retryCalls.incrementAndGet() },
            )
        }

        LoadMoreErrorItem(
            onRetry = { SearchStateProbe.loadMoreRetryCalls.incrementAndGet() },
        )
    }
}
