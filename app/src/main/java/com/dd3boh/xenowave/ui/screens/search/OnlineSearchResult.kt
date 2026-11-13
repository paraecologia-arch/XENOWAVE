package com.dd3boh.xenowave.ui.screens.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.dd3boh.xenowave.LocalMenuState
import com.dd3boh.xenowave.LocalPlayerAwareWindowInsets
import com.dd3boh.xenowave.LocalPlayerConnection
import com.dd3boh.xenowave.LocalSnackbarHostState
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.constants.AppBarHeight
import com.dd3boh.xenowave.constants.SearchFilterHeight
import com.dd3boh.xenowave.constants.SwipeToQueueKey
import com.dd3boh.xenowave.extensions.toMediaItem
import com.dd3boh.xenowave.extensions.togglePlayPause
import com.dd3boh.xenowave.models.toMediaMetadata
import com.dd3boh.xenowave.playback.queues.ListQueue
import com.dd3boh.xenowave.ui.component.ChipsRow
import com.dd3boh.xenowave.ui.component.EmptyPlaceholder
import com.dd3boh.xenowave.ui.component.LazyColumnScrollbar
import com.dd3boh.xenowave.ui.component.NavigationTitle
import com.dd3boh.xenowave.ui.component.SwipeToQueueBox
import com.dd3boh.xenowave.ui.component.button.IconButton
import com.dd3boh.xenowave.ui.component.items.YouTubeListItem
import com.dd3boh.xenowave.ui.component.shimmer.ListItemPlaceHolder
import com.dd3boh.xenowave.ui.component.shimmer.ShimmerHost
import com.dd3boh.xenowave.ui.menu.YouTubeAlbumMenu
import com.dd3boh.xenowave.ui.menu.YouTubeArtistMenu
import com.dd3boh.xenowave.ui.menu.YouTubePlaylistMenu
import com.dd3boh.xenowave.ui.menu.YouTubeSongMenu
import com.dd3boh.xenowave.utils.rememberPreference
import com.dd3boh.xenowave.viewmodels.OnlineSearchViewModel
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.YouTube.SearchFilter.Companion.FILTER_ALBUM
import com.zionhuang.innertube.YouTube.SearchFilter.Companion.FILTER_ARTIST
import com.zionhuang.innertube.YouTube.SearchFilter.Companion.FILTER_COMMUNITY_PLAYLIST
import com.zionhuang.innertube.YouTube.SearchFilter.Companion.FILTER_FEATURED_PLAYLIST
import com.zionhuang.innertube.YouTube.SearchFilter.Companion.FILTER_SONG
import com.zionhuang.innertube.YouTube.SearchFilter.Companion.FILTER_VIDEO
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.models.stableKey
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnlineSearchResult(
    navController: NavController,
    viewModel: OnlineSearchViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val menuState = LocalMenuState.current
    val playerConnection = LocalPlayerConnection.current ?: return

    val swipeEnabled by rememberPreference(SwipeToQueueKey, true)

    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()
    val snackbarHostState = LocalSnackbarHostState.current

    val searchFilter by viewModel.filter.collectAsState()
    val searchSummary = viewModel.summaryPage
    val summaryLoading = viewModel.isSummaryLoading
    val summaryError = viewModel.summaryError
    val filterKey = searchFilter?.value
    val filterState = filterKey?.let { viewModel.filterStates[it] }
    val filterLoading = filterState?.isLoading == true
    val filterError = filterState?.error
    val filterRelated = filterState?.related == true
    val loadMoreError = filterState?.loadMoreError

    LaunchedEffect(lazyListState) {
        snapshotFlow {
            lazyListState.layoutInfo.visibleItemsInfo.any { it.key == "loading" }
        }.collect { shouldLoadMore ->
            if (!shouldLoadMore) return@collect
            viewModel.loadMore()
        }
    }

    val ytItemContent: @Composable LazyItemScope.(YTItem, List<YTItem>) -> Unit =
        { item: YTItem, collection: List<YTItem> ->
            val content: @Composable () -> Unit = {
                YouTubeListItem(
                    item = item,
                    isActive = when (item) {
                        is SongItem -> mediaMetadata?.id == item.id
                        is AlbumItem -> mediaMetadata?.album?.id == item.id
                        else -> false
                    },
                    isPlaying = isPlaying,
                    trailingContent = {
                        IconButton(
                            onClick = {
                                menuState.show {
                                    when (item) {
                                        is SongItem -> YouTubeSongMenu(
                                            song = item,
                                            navController = navController,
                                            onDismiss = menuState::dismiss
                                        )

                                        is AlbumItem -> YouTubeAlbumMenu(
                                            albumItem = item,
                                            navController = navController,
                                            onDismiss = menuState::dismiss
                                        )

                                        is ArtistItem -> YouTubeArtistMenu(
                                            artist = item,
                                            onDismiss = menuState::dismiss
                                        )

                                        is PlaylistItem -> YouTubePlaylistMenu(
                                            navController = navController,
                                            playlist = item,
                                            coroutineScope = coroutineScope,
                                            onDismiss = menuState::dismiss
                                        )
                                    }
                                }
                            }
                        ) {
                            com.dd3boh.xenowave.ui.icons.XenoIcon(
                                icon = com.dd3boh.xenowave.ui.icons.XenoActionIcons.MoreVert,
                                contentDescription = null
                            )
                        }

                    },
                    modifier = Modifier
                        .combinedClickable(
                            onClick = {
                                when (item) {
                                    is SongItem -> {
                                        if (item.id == mediaMetadata?.id) {
                                            playerConnection.player.togglePlayPause()
                                        } else {
                                            val songSuggestions = collection.filter { it is SongItem }
                                            playerConnection.playQueue(
                                                ListQueue(
                                                    title = "${context.getString(R.string.queue_searched_songs_xenowave)} ${viewModel.query}",
                                                    items = songSuggestions.map { (it as SongItem).toMediaMetadata() },
                                                    startIndex = songSuggestions.indexOf(item)
                                                ),
                                                replace = true,
                                            )
                                        }
                                    }

                                    is AlbumItem -> navController.navigate("album/${item.id}")
                                    is ArtistItem -> navController.navigate("artist/${item.id}")
                                    is PlaylistItem -> navController.navigate("online_playlist/${item.id}")
                                }
                            },
                            onLongClick = {
                                menuState.show {
                                    when (item) {
                                        is SongItem -> YouTubeSongMenu(
                                            song = item,
                                            navController = navController,
                                            onDismiss = menuState::dismiss
                                        )

                                        else -> {}
                                    }
                                }
                            }
                        )
                        .animateItem()
                )
            }

            if (item !is SongItem) content()
            else SwipeToQueueBox(
                item = item.toMediaItem(),
                swipeEnabled = swipeEnabled,
                snackbarHostState = snackbarHostState,
                content = { content() },
            )
        }

    LazyColumn(
        state = lazyListState,
        contentPadding = LocalPlayerAwareWindowInsets.current
            .add(WindowInsets(top = SearchFilterHeight))
            .asPaddingValues()
    ) {
        if (searchFilter == null) {
            val summaries = searchSummary?.summaries.orEmpty()
            if (summaryError != null && searchSummary == null) {
                item {
                    EmptyPlaceholder(
                        icon = com.dd3boh.xenowave.ui.icons.XenoNavigationIcons.Search,
                        text = stringResource(R.string.search_load_error),
                        modifier = Modifier.animateItem(),
                        showBrandMark = true,
                        actionLabel = stringResource(R.string.retry),
                        onAction = viewModel::retry,
                    )
                }
            } else {
                summaries.forEachIndexed { index, summary ->
                    if (summary.related) {
                        item(key = "related-$index") {
                            NavigationTitle(stringResource(R.string.search_results_related))
                        }
                    } else if (summary.title.isNotBlank()) {
                        item(key = "title-$index") {
                            NavigationTitle(summary.title)
                        }
                    }

                    items(
                        items = summary.items,
                        key = { "$index/${it.stableKey}" }
                    ) { item ->
                        ytItemContent(item, summary.items)
                    }
                }

                if (summaries.isEmpty() && !summaryLoading) {
                    item {
                        EmptyPlaceholder(
                            icon = com.dd3boh.xenowave.ui.icons.XenoNavigationIcons.Search,
                            text = stringResource(R.string.no_results_found),
                            modifier = Modifier.animateItem(),
                            showBrandMark = true,
                        )
                    }
                }

                if (searchSummary == null && summaryLoading) {
                    item {
                        ShimmerHost {
                            repeat(8) {
                                ListItemPlaceHolder()
                            }
                        }
                    }
                }
            }
        } else {
            if (filterRelated) {
                item(key = "related-filter") {
                    NavigationTitle(stringResource(R.string.search_results_related))
                }
            }

            items(
                items = filterState?.items.orEmpty(),
                key = { it.stableKey }
            ) { item ->
                ytItemContent(item, filterState?.items.orEmpty())
            }

            if (loadMoreError != null) {
                item(key = "load-more-error") {
                    LoadMoreErrorItem(onRetry = viewModel::retryNextPage)
                }
            } else if (filterState?.continuation != null) {
                item(key = "loading") {
                    ShimmerHost {
                        repeat(3) {
                            ListItemPlaceHolder()
                        }
                    }
                }
            }

            if (filterError != null && filterState.items.isNullOrEmpty()) {
                item {
                    EmptyPlaceholder(
                        icon = com.dd3boh.xenowave.ui.icons.XenoNavigationIcons.Search,
                        text = stringResource(R.string.search_load_error),
                        modifier = Modifier.animateItem(),
                        showBrandMark = true,
                        actionLabel = stringResource(R.string.retry),
                        onAction = viewModel::retry,
                    )
                }
            } else if (filterState?.items?.isEmpty() == true && !filterLoading) {
                item {
                    EmptyPlaceholder(
                        icon = com.dd3boh.xenowave.ui.icons.XenoNavigationIcons.Search,
                        text = stringResource(R.string.no_results_found),
                        modifier = Modifier.animateItem(),
                        showBrandMark = true,
                    )
                }
            }

            if (filterState == null || (filterState.isLoading && filterState.items.isEmpty())) {
                item {
                    ShimmerHost {
                        repeat(8) {
                            ListItemPlaceHolder()
                        }
                    }
                }
            }
        }
    }
    LazyColumnScrollbar(
        state = lazyListState,
    )

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                .align(Alignment.BottomCenter)
        )
    }

    OnlineSearchFilterChips(
        currentValue = searchFilter,
        onValueUpdate = {
            if (viewModel.filter.value != it) {
                viewModel.filter.value = it
            }
            coroutineScope.launch {
                lazyListState.animateScrollToItem(0)
            }
        },
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top).add(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal)))
            .padding(top = AppBarHeight)
    )
}

/** Production filter row, also hosted by the deterministic debug integration test. */
@Composable
internal fun OnlineSearchFilterChips(
    currentValue: YouTube.SearchFilter?,
    onValueUpdate: (YouTube.SearchFilter?) -> Unit,
    modifier: Modifier = Modifier,
) {
    ChipsRow(
        chips = listOf(
            null to stringResource(R.string.filter_all),
            FILTER_SONG to stringResource(R.string.filter_songs),
            FILTER_VIDEO to stringResource(R.string.filter_videos),
            FILTER_ALBUM to stringResource(R.string.filter_albums),
            FILTER_ARTIST to stringResource(R.string.filter_artists),
            FILTER_COMMUNITY_PLAYLIST to stringResource(R.string.filter_community_playlists),
            FILTER_FEATURED_PLAYLIST to stringResource(R.string.filter_featured_playlists)
        ),
        currentValue = currentValue,
        onValueUpdate = onValueUpdate,
        modifier = modifier,
    )
}

@Composable
internal fun LoadMoreErrorItem(onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.search_load_more_error),
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.retry))
        }
    }
}
