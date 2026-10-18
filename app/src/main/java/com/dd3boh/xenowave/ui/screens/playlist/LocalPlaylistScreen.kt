package com.dd3boh.xenowave.ui.screens.playlist

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import com.dd3boh.xenowave.LocalDatabase
import com.dd3boh.xenowave.LocalDownloadUtil
import com.dd3boh.xenowave.LocalMenuState
import com.dd3boh.xenowave.LocalNetworkConnected
import com.dd3boh.xenowave.LocalPlayerAwareWindowInsets
import com.dd3boh.xenowave.LocalPlayerConnection
import com.dd3boh.xenowave.LocalSnackbarHostState
import com.dd3boh.xenowave.LocalSyncUtils
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.constants.AlbumCornerRadius
import com.dd3boh.xenowave.constants.AlbumThumbnailSize
import com.dd3boh.xenowave.constants.CONTENT_TYPE_HEADER
import com.dd3boh.xenowave.constants.CONTENT_TYPE_SONG
import com.dd3boh.xenowave.constants.ListThumbnailSize
import com.dd3boh.xenowave.constants.PlaylistEditLockKey
import com.dd3boh.xenowave.constants.PlaylistSongSortDescendingKey
import com.dd3boh.xenowave.constants.PlaylistSongSortType
import com.dd3boh.xenowave.constants.PlaylistSongSortTypeKey
import com.dd3boh.xenowave.constants.SwipeToQueueKey
import com.dd3boh.xenowave.constants.SyncMode
import com.dd3boh.xenowave.constants.YtmSyncModeKey
import com.dd3boh.xenowave.db.entities.Playlist
import com.dd3boh.xenowave.db.entities.PlaylistSong
import com.dd3boh.xenowave.extensions.move
import com.dd3boh.xenowave.extensions.toMediaItem
import com.dd3boh.xenowave.models.toMediaMetadata
import com.dd3boh.xenowave.playback.ExoDownloadService
import com.dd3boh.xenowave.playback.queues.ListQueue
import com.dd3boh.xenowave.ui.component.AutoResizeText
import com.dd3boh.xenowave.ui.component.EmptyPlaceholder
import com.dd3boh.xenowave.ui.component.FloatingFooter
import com.dd3boh.xenowave.ui.component.FontSizeRange
import com.dd3boh.xenowave.ui.component.LazyColumnScrollbar
import com.dd3boh.xenowave.ui.component.ScrollToTopManager
import com.dd3boh.xenowave.ui.component.SelectHeader
import com.dd3boh.xenowave.ui.component.SortHeader
import com.dd3boh.xenowave.ui.component.button.IconButton
import com.dd3boh.xenowave.ui.component.items.PlaylistThumbnail
import com.dd3boh.xenowave.ui.component.items.SongListItem
import com.dd3boh.xenowave.ui.dialog.DefaultDialog
import com.dd3boh.xenowave.ui.dialog.PlaylistDuplicatesDialog
import com.dd3boh.xenowave.ui.dialog.TextFieldDialog
import com.dd3boh.xenowave.ui.menu.PlaylistMenu
import com.dd3boh.xenowave.ui.utils.backToMain
import com.dd3boh.xenowave.utils.makeTimeString
import com.dd3boh.xenowave.utils.getDownloadState
import com.dd3boh.xenowave.utils.rememberEnumPreference
import com.dd3boh.xenowave.utils.rememberPreference
import com.dd3boh.xenowave.utils.syncCoroutine
import com.dd3boh.xenowave.viewmodels.LocalPlaylistViewModel
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun LocalPlaylistScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: LocalPlaylistViewModel = hiltViewModel(),
) {
    Log.v("LocalPlaylistScreen", "P_RC-1")
    val context = LocalContext.current
    val density = LocalDensity.current
    val menuState = LocalMenuState.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val snackbarHostState = LocalSnackbarHostState.current

    val playlistWithSongs by viewModel.playlistWithSongs.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val hasLoadError by viewModel.hasLoadError.collectAsState()

    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val mutableSongs = remember { mutableStateListOf<PlaylistSong>() }
    val insights = remember(playlistWithSongs.second) {
        analyzePlaylist(playlistWithSongs.second)
    }

    val (sortType, onSortTypeChange) = rememberEnumPreference(PlaylistSongSortTypeKey, PlaylistSongSortType.CUSTOM)
    val (sortDescending, onSortDescendingChange) = rememberPreference(PlaylistSongSortDescendingKey, true)
    var locked by rememberPreference(PlaylistEditLockKey, defaultValue = false)
    val swipeEnabled by rememberPreference(SwipeToQueueKey, true)
    val syncMode by rememberEnumPreference(key = YtmSyncModeKey, defaultValue = SyncMode.RW)

    var inSelectMode by rememberSaveable { mutableStateOf(false) }
    val selection = rememberSaveable(
        saver = listSaver<MutableList<Int>, Int>(
            save = { it.toList() },
            restore = { it.toMutableStateList() }
        )
    ) { mutableStateListOf() }
    val onExitSelectionMode = {
        inSelectMode = false
        selection.clear()
    }

    // search
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }
    var debouncedSearch by rememberSaveable {
        mutableStateOf("")
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(isSearching) {
        if (isSearching) {
            focusRequester.requestFocus()
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { query.text }.debounce(250L).collectLatest {
            debouncedSearch = it
        }
    }

    val visibleSongs by remember {
        derivedStateOf {
            if (debouncedSearch.isBlank()) {
                mutableSongs
            } else {
                filterPlaylistSongs(playlistWithSongs.second, debouncedSearch)
            }
        }
    }

    LaunchedEffect(visibleSongs) {
        val visibleMapIds = visibleSongs.map { it.map.id }.toSet()
        selection.retainAll(visibleMapIds)
        if (selection.isEmpty()) {
            inSelectMode = false
        }
    }

    val closeSearch = {
        isSearching = false
        query = TextFieldValue()
        debouncedSearch = ""
    }

    if (inSelectMode) {
        BackHandler(onBack = onExitSelectionMode)
    } else if (isSearching) {
        BackHandler(onBack = closeSearch)
    }

    val editable: Boolean =
        playlistWithSongs.first?.playlist?.isLocal == true || (playlistWithSongs.first?.playlist?.isEditable == true && syncMode == SyncMode.RW)

    LaunchedEffect(playlistWithSongs.second, isSearching) {
        if (!isSearching) {
            mutableSongs.apply {
                clear()
                addAll(playlistWithSongs.second)
            }
        }
    }

    var showEditDialog by remember {
        mutableStateOf(false)
    }

    if (showEditDialog) {
        playlistWithSongs.first?.playlist?.let { playlistEntity ->
            TextFieldDialog(
                icon = { com.dd3boh.xenowave.ui.icons.XenoIcon(icon = com.dd3boh.xenowave.ui.icons.XenoActionIcons.Edit, contentDescription = null) },
                title = { Text(text = stringResource(R.string.edit_playlist)) },
                onDismiss = { showEditDialog = false },
                initialTextFieldValue = TextFieldValue(playlistEntity.name, TextRange(playlistEntity.name.length)),
                onDone = { name ->
                    database.query {
                        update(playlistEntity.copy(name = name))
                    }

                    viewModel.viewModelScope.launch(syncCoroutine) {
                        playlistEntity.browseId?.let { YouTube.renamePlaylist(it, name) }
                    }
                }
            )
        }
    }

    var showRemoveDownloadDialog by remember {
        mutableStateOf(false)
    }

    if (showRemoveDownloadDialog) {
        DefaultDialog(
            onDismiss = { showRemoveDownloadDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.remove_download_playlist_confirm, playlistWithSongs.first?.playlist!!.name),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
            },
            buttons = {
                TextButton(
                    onClick = { showRemoveDownloadDialog = false }
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        showRemoveDownloadDialog = false
                        if (!editable) {
                            database.transaction {
                                playlistWithSongs.first?.id?.let { clearPlaylist(it) }
                            }
                        }

                        playlistWithSongs.second.filterNot { it.song.song.isLocal }.forEach { song ->
                            DownloadService.sendRemoveDownload(
                                context,
                                ExoDownloadService::class.java,
                                song.song.id,
                                false
                            )
                        }
                    }
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            }
        )
    }

    var showDeletePlaylistDialog by remember {
        mutableStateOf(false)
    }

    if (showDeletePlaylistDialog) {
        DefaultDialog(
            onDismiss = { showDeletePlaylistDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.delete_playlist_confirm, playlistWithSongs.first?.playlist!!.name),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
            },
            buttons = {
                TextButton(
                    onClick = {
                        showDeletePlaylistDialog = false
                    }
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        showDeletePlaylistDialog = false
                        database.query {
                            playlistWithSongs.first?.let { delete(it.playlist) }
                        }

                        viewModel.viewModelScope.launch(Dispatchers.IO) {
                            playlistWithSongs.first?.playlist?.browseId?.let { YouTube.deletePlaylist(it) }
                        }

                        navController.popBackStack()
                    }
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            }
        )
    }

    var showDuplicatesDialog by remember {
        mutableStateOf(false)
    }
    var pendingBulkRemoval by remember {
        mutableStateOf<Set<Int>?>(null)
    }
    var showBulkRemoveDialog by remember {
        mutableStateOf(false)
    }
    var isRemoving by remember {
        mutableStateOf(false)
    }
    val coroutineScope = rememberCoroutineScope()
    val isLocalPlaylist = playlistWithSongs.first?.playlist?.isLocal == true

    if (showBulkRemoveDialog) {
        val mapIds = pendingBulkRemoval.orEmpty()
        val successMessage = pluralStringResource(
            R.plurals.playlist_remove_selected_success,
            mapIds.size,
            mapIds.size,
        )
        val failureMessage = stringResource(R.string.playlist_remove_selected_failed)
        DefaultDialog(
            onDismiss = {
                if (!isRemoving) {
                    showBulkRemoveDialog = false
                    pendingBulkRemoval = null
                }
            },
            content = {
                Text(
                    text = pluralStringResource(
                        R.plurals.playlist_remove_selected_confirm,
                        mapIds.size,
                        mapIds.size,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
            },
            buttons = {
                TextButton(
                    onClick = {
                        if (!isRemoving) {
                            showBulkRemoveDialog = false
                            pendingBulkRemoval = null
                        }
                    },
                    enabled = !isRemoving,
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        if (mapIds.isEmpty() || isRemoving) return@TextButton
                        coroutineScope.launch {
                            isRemoving = true
                            val removed = viewModel.removePlaylistSongs(mapIds)
                            isRemoving = false
                            showBulkRemoveDialog = false
                            pendingBulkRemoval = null
                            if (removed) {
                                selection.clear()
                                inSelectMode = false
                                snackbarHostState.showSnackbar(
                                    message = successMessage
                                )
                            } else {
                                snackbarHostState.showSnackbar(
                                    message = failureMessage
                                )
                            }
                        }
                    },
                    enabled = !isRemoving,
                ) {
                    Text(text = stringResource(R.string.remove_from_playlist))
                }
            }
        )
    }

    if (showDuplicatesDialog) {
        val duplicateSuccessMessage = stringResource(R.string.playlist_remove_duplicate_success)
        val duplicateFailureMessage = stringResource(R.string.playlist_remove_duplicate_failed)
        PlaylistDuplicatesDialog(
            groups = insights.duplicateGroups,
            onRemoveOccurrence = { occurrence ->
                val removed = viewModel.removeDuplicateOccurrence(occurrence.map.id)
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(
                        message = if (removed) duplicateSuccessMessage else duplicateFailureMessage
                    )
                }
                removed
            },
            onDismiss = { showDuplicatesDialog = false },
        )
    }

    val headerItems = 2
    val lazyListState = rememberLazyListState()
    var dragInfo by remember {
        mutableStateOf<Pair<Int, Int>?>(null)
    }
    val reorderableState = rememberReorderableLazyListState(
        lazyListState = lazyListState,
//        scrollThresholdPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    ) { from, to ->
        if (to.index >= headerItems && from.index >= headerItems) {
            val currentDragInfo = dragInfo
            dragInfo = if (currentDragInfo == null) {
                (from.index - headerItems) to (to.index - headerItems)
            } else {
                currentDragInfo.first to (to.index - headerItems)
            }

            mutableSongs.move(from.index - headerItems, to.index - headerItems)
        }
    }

    LaunchedEffect(reorderableState.isAnyItemDragging) {
        if (!reorderableState.isAnyItemDragging) {
            dragInfo?.let { (from, to) ->
                database.transaction {
                    move(viewModel.playlistId, from, to)
                }
                if (playlistWithSongs.first?.playlist?.isLocal == false) {
                    viewModel.viewModelScope.launch(Dispatchers.IO) {
                        val from = from
                        val to = to
                        val playlistSongMap = database.songMapsToPlaylist(viewModel.playlistId, 0)

                        var fromIndex = from //- headerItems
                        val toIndex = to //- headerItems

                        var successorIndex = if (fromIndex > toIndex) toIndex else toIndex + 1

                        /*
                        * Because of how YouTube Music handles playlist changes, you necessarily need to
                        * have the SetVideoId of the successor when trying to move a song inside of a
                        * playlist.
                        * For this reason, if we are trying to move a song to the last element of a playlist,
                        * we need to first move it as penultimate and then move the last element before it.
                        */
                        if (successorIndex >= playlistSongMap.size) {
                            playlistSongMap[fromIndex].setVideoId?.let { setVideoId ->
                                playlistSongMap[toIndex].setVideoId?.let { successorSetVideoId ->
                                    playlistWithSongs.first?.playlist?.browseId?.let { browseId ->
                                        YouTube.moveSongPlaylist(browseId, setVideoId, successorSetVideoId)
                                    }
                                }
                            }

                            successorIndex = fromIndex
                            fromIndex = toIndex
                        }

                        playlistSongMap[fromIndex].setVideoId?.let { setVideoId ->
                            playlistSongMap[successorIndex].setVideoId?.let { successorSetVideoId ->
                                playlistWithSongs.first?.playlist?.browseId?.let { browseId ->
                                    YouTube.moveSongPlaylist(browseId, setVideoId, successorSetVideoId)
                                }
                            }
                        }
                    }
                }
                dragInfo = null
            }
        }
    }

    val showTopBarTitle by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex > 0
        }
    }
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Log.v("LocalPlaylistScreen", "P_RC-2.1")
        ScrollToTopManager(navController, lazyListState)
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (hasLoadError) {
            EmptyPlaceholder(
                icon = com.dd3boh.xenowave.ui.icons.XenoSystemIcons.ErrorOutline,
                text = stringResource(R.string.playlist_load_failed),
                modifier = Modifier.fillMaxSize(),
            )
        } else if (playlistWithSongs.first == null) {
            EmptyPlaceholder(
                icon = com.dd3boh.xenowave.ui.icons.XenoSystemIcons.ErrorOutline,
                text = stringResource(R.string.playlist_not_found),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current.union(WindowInsets.ime).asPaddingValues(),
            modifier = Modifier.padding(bottom = if (inSelectMode) 64.dp else 0.dp)
        ) {
            Log.v("LocalPlaylistScreen", "P_RC-2.2")
            playlistWithSongs.first?.let { playlist ->
                if (playlist.songCount == 0) {
                    item {
                        EmptyPlaceholder(
                            icon = com.dd3boh.xenowave.ui.icons.XenoLibraryIcons.MusicNote,
                            text = stringResource(R.string.playlist_is_empty),
                            modifier = Modifier.animateItem(),
                            showBrandMark = true,
                        )
                    }
                } else {
                    // playlist header
                    if (!isSearching) {
                        item(
                            key = "playlist header",
                            contentType = CONTENT_TYPE_HEADER
                        ) {
                            LocalPlaylistHeader(
                                playlist = playlist,
                                songs = playlistWithSongs.second,
                                insights = insights,
                                onShowEditDialog = { showEditDialog = true },
                                onShowRemoveDownloadDialog = { showRemoveDownloadDialog = true },
                                onShowDuplicates = {
                                    if (isLocalPlaylist) showDuplicatesDialog = true
                                },
                                snackbarHostState = snackbarHostState,
                                modifier = Modifier // .animateItem()
                            )
                        }
                    }

                    item(
                        key = "action header",
                        contentType = CONTENT_TYPE_HEADER
                    ) {
                        Column(
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SortHeader(
                                    sortType = sortType,
                                    sortDescending = sortDescending,
                                    onSortTypeChange = onSortTypeChange,
                                    onSortDescendingChange = onSortDescendingChange,
                                    sortTypeText = { sortType ->
                                        when (sortType) {
                                            PlaylistSongSortType.CUSTOM -> R.string.sort_by_custom
                                            PlaylistSongSortType.NAME -> R.string.sort_by_name
                                            PlaylistSongSortType.ARTIST -> R.string.sort_by_artist
                                            PlaylistSongSortType.ALBUM -> R.string.sort_by_album
                                            PlaylistSongSortType.DURATION -> R.string.sort_by_length
                                            PlaylistSongSortType.ADDED_DATE -> R.string.sort_by_create_date
                                            PlaylistSongSortType.MODIFIED_DATE -> R.string.sort_by_date_modified
                                            PlaylistSongSortType.RELEASE_DATE -> R.string.sort_by_date_released
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                )

                                if (editable && !(inSelectMode || isSearching)) {
                                    IconButton(
                                        onClick = { locked = !locked },
                                        modifier = Modifier.padding(horizontal = 6.dp)
                                    ) {
                                        com.dd3boh.xenowave.ui.icons.XenoIcon(
                                            icon = if (locked) com.dd3boh.xenowave.ui.icons.XenoSystemIcons.Lock else com.dd3boh.xenowave.ui.icons.XenoSystemIcons.LockOpen,
                                            contentDescription = stringResource(
                                                if (locked) R.string.playlist_unlock_order else R.string.playlist_lock_order
                                            )
                                        )
                                    }
                                }
                            }

                            if (sortType != PlaylistSongSortType.CUSTOM) {
                                Text(
                                    text = stringResource(R.string.playlist_visual_order_note),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(
                                    onClick = {
                                        onSortTypeChange(PlaylistSongSortType.CUSTOM)
                                    }
                                ) {
                                    Text(stringResource(R.string.playlist_restore_manual_order))
                                }
                            }
                        }
                    }
                }
            }

            // songs
            if (
                debouncedSearch.isNotBlank() &&
                visibleSongs.isEmpty() &&
                playlistWithSongs.first?.songCount != 0
            ) {
                item(key = "no search results") {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.no_results_found),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            val thumbnailSize = (ListThumbnailSize.value * density.density).roundToInt()
            itemsIndexed(
                items = visibleSongs,
                key = { _, song -> song.map.id },
                contentType = { _, song -> CONTENT_TYPE_SONG },
            ) { index, song ->
                ReorderableItem(
                    state = reorderableState,
                    key = song.map.id,
                    enabled = editable &&
                        sortType == PlaylistSongSortType.CUSTOM &&
                        !isSearching &&
                        !locked
                ) {
                    SongListItem(
                        song = song.song,
                        thumbnailSize = thumbnailSize,
                        playlistSong = song,
                        playlist =  playlistWithSongs.first,
                        navController = navController,
                        snackbarHostState = snackbarHostState,

                        isActive = song.song.id == mediaMetadata?.id,
                        isPlaying = isPlaying,
                        swipeEnabled = swipeEnabled,
                        onSelectedChange = {
                            if (it) {
                                selection.add(song.map.id)
                            } else {
                                selection.remove(song.map.id)
                            }
                            inSelectMode = selection.isNotEmpty()
                        },
                        inSelectMode = inSelectMode,
                        isSelected = selection.contains(song.map.id),

                        onPlay = {
                            playerConnection.playQueue(
                                ListQueue(
                                    title = playlistWithSongs.first!!.playlist.name,
                                    items = visibleSongs.map { it.song.toMediaMetadata() },
                                    startIndex = index,
                                    playlistId = playlistWithSongs.first?.playlist?.browseId
                                )
                            )
                        },
                        dragHandleModifier = if (sortType == PlaylistSongSortType.CUSTOM && !locked && !isSearching && editable) Modifier.draggableHandle() else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background),
                    )
                }
            }
        }

        LazyColumnScrollbar(
            state = lazyListState,
        )
        }

        TopAppBar(
            title = {
                if (isSearching) {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = {
                            Text(
                                text = stringResource(R.string.search),
                                style = MaterialTheme.typography.titleLarge
                            )
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleLarge,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        trailingIcon = {
                            if (query.text.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        query = TextFieldValue()
                                        debouncedSearch = ""
                                    }
                                ) {
                                    com.dd3boh.xenowave.ui.icons.XenoIcon(
                                        icon = com.dd3boh.xenowave.ui.icons.XenoActionIcons.Close,
                                        contentDescription = stringResource(R.string.clear_search)
                                    )
                                }
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    )
                } else if (showTopBarTitle) {
                    Text( playlistWithSongs.first?.playlist?.name.orEmpty())
                }
            },
            actions = {
                if (!isSearching && playlistWithSongs.first != null) {
                    IconButton(
                        onClick = {
                            isSearching = true
                        }
                    ) {
                        com.dd3boh.xenowave.ui.icons.XenoIcon(
                            icon = com.dd3boh.xenowave.ui.icons.XenoNavigationIcons.Search,
                            contentDescription = stringResource(R.string.search)
                        )
                    }

                    IconButton(
                        onClick = {
                            playlistWithSongs.first?.let { playlist ->
                                menuState.show {
                                    PlaylistMenu(
                                        navController = navController,
                                        playlist = playlist,
                                        coroutineScope = viewModel.viewModelScope,
                                        onDismiss = menuState::dismiss,
                                    )
                                }
                            }
                        }
                    ) {
                        com.dd3boh.xenowave.ui.icons.XenoIcon(
                            icon = com.dd3boh.xenowave.ui.icons.XenoActionIcons.MoreVert,
                            contentDescription = stringResource(R.string.options)
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(
                    onClick = {
                        if (isSearching) {
                            closeSearch()
                        } else {
                            navController.navigateUp()
                        }
                    },
                    onLongClick = {
                        if (!isSearching) {
                            navController.backToMain()
                        }
                    }
                ) {
                    com.dd3boh.xenowave.ui.icons.XenoIcon(
                        icon = com.dd3boh.xenowave.ui.icons.XenoIcons.AutoMirrored.ArrowBack,
                        contentDescription = stringResource(R.string.navigate_back)
                    )
                }
            },
//            windowInsets = TopBarInsets,
            scrollBehavior = scrollBehavior
        )

        FloatingFooter(inSelectMode) {
            SelectHeader(
                navController = navController,
                selectedItems = selectedPlaylistSongs(visibleSongs, selection.toSet())
                    .map { it.song.toMediaMetadata() },
                totalItemCount = visibleSongs.size,
                onSelectAll = {
                    selection.clear()
                    selection.addAll(visibleSongs.map { it.map.id })
                    inSelectMode = visibleSongs.isNotEmpty()
                },
                onDeselectAll = { selection.clear() },
                menuState = menuState,
                onDismiss = onExitSelectionMode,
                onRemoveFromPlaylist = if (isLocalPlaylist) {
                    {
                        pendingBulkRemoval = selection.toSet()
                        showBulkRemoveDialog = true
                    }
                } else {
                    null
                },
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
//                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.union(WindowInsets.ime))
                .align(Alignment.BottomCenter)
        )
    }
}


@Composable
fun LocalPlaylistHeader(
    playlist: Playlist,
    songs: List<PlaylistSong>,
    insights: PlaylistInsights,
    onShowEditDialog: () -> Unit,
    onShowRemoveDownloadDialog: () -> Unit,
    onShowDuplicates: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier,
) {
    Log.v("LocalPlaylistScreen", "P_H_RC-1")
    val playerConnection = LocalPlayerConnection.current ?: return
    val context = LocalContext.current
    val database = LocalDatabase.current
    val isNetworkConnected = LocalNetworkConnected.current
    val scope = rememberCoroutineScope()
    val syncUtils = LocalSyncUtils.current

    val downloadUtil = LocalDownloadUtil.current
    var downloadState by remember {
        mutableIntStateOf(Download.STATE_STOPPED)
    }

    LaunchedEffect(songs) {
        val downloadableSongs = songs.filterNot { it.song.song.isLocal }
        if (downloadableSongs.isEmpty()) {
            downloadState = Download.STATE_STOPPED
            return@LaunchedEffect
        }

        downloadUtil.downloads.collect { downloads ->
            downloadState = getDownloadState(
                downloadableSongs.map { downloads[it.song.id] }
            )
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.padding(12.dp)
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            itemVerticalAlignment = Alignment.CenterVertically
        ) {
            PlaylistThumbnail(
                playlist = playlist.playlist,
                thumbnails = playlist.thumbnails,
                size = AlbumThumbnailSize,
                shape = RoundedCornerShape(AlbumCornerRadius),
                iconPadding = AlbumThumbnailSize / 16,
                iconTint = LocalContentColor.current.copy(alpha = 0.8f),
            )

            Column(
                verticalArrangement = Arrangement.Center,
            ) {
                AutoResizeText(
                    text = playlist.playlist.name,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSizeRange = FontSizeRange(16.sp, 22.sp)
                )

                Text(
                    text = pluralStringResource(
                        R.plurals.n_song,
                        insights.songCount,
                        insights.songCount,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Normal
                )

                if (insights.offlineCount > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        com.dd3boh.xenowave.ui.icons.XenoIcon(
                            icon = com.dd3boh.xenowave.ui.icons.XenoSystemIcons.OfflinePin,
                            contentDescription = null,
                            modifier = Modifier
                                .size(18.dp)
                                .padding(end = 2.dp)
                        )
                        Text(
                            text = pluralStringResource(
                                R.plurals.playlist_offline_count,
                                insights.offlineCount,
                                insights.offlineCount,
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Normal
                        )
                    }
                }

                if (insights.songCount > 0 && insights.knownDurationCount == insights.songCount) {
                    Text(
                        text = makeTimeString(insights.totalDurationSeconds * 1000L),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Normal
                    )
                }

                if (playlist.playlist.isLocal && insights.duplicateCount > 0) {
                    TextButton(
                        onClick = onShowDuplicates,
                    ) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.playlist_duplicate_count,
                                insights.duplicateCount,
                                insights.duplicateCount,
                            )
                        )
                    }
                }

                Row {
                    IconButton(
                        onClick = onShowEditDialog
                    ) {
                        com.dd3boh.xenowave.ui.icons.XenoIcon(
                            icon = com.dd3boh.xenowave.ui.icons.XenoActionIcons.Edit,
                            contentDescription = stringResource(R.string.edit)
                        )
                    }

                    if (playlist.playlist.browseId != null) {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    syncUtils.syncPlaylist(playlist.playlist.browseId, playlist.id)
                                    snackbarHostState.showSnackbar(
                                        message = context.getString(R.string.playlist_synced),
                                        withDismissAction = true
                                    )
                                }
                            },
                            enabled = isNetworkConnected
                        ) {
                            com.dd3boh.xenowave.ui.icons.XenoIcon(
                                icon = com.dd3boh.xenowave.ui.icons.XenoSystemIcons.Sync,
                                contentDescription = stringResource(R.string.action_sync)
                            )
                        }
                    }

                    if (songs.any { !it.song.song.isLocal }) {
                        when (downloadState) {
                            Download.STATE_COMPLETED -> {
                                IconButton(
                                    onClick = onShowRemoveDownloadDialog
                                ) {
                                    com.dd3boh.xenowave.ui.icons.XenoIcon(
                                        icon = com.dd3boh.xenowave.ui.icons.XenoSystemIcons.OfflinePin,
                                        contentDescription = stringResource(R.string.remove_download)
                                    )
                                }
                            }

                            Download.STATE_DOWNLOADING -> {
                                IconButton(
                                    onClick = {
                                        songs.filterNot { it.song.song.isLocal }.forEach { song ->
                                            DownloadService.sendRemoveDownload(
                                                context,
                                                ExoDownloadService::class.java,
                                                song.song.id,
                                                false
                                            )
                                        }
                                    },
                                    modifier = Modifier.semantics {
                                        contentDescription = context.getString(R.string.remove_download)
                                    }
                                ) {
                                    CircularProgressIndicator(
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }

                            else -> {
                                IconButton(
                                    onClick = {
                                        downloadUtil.download(
                                            songs
                                                .filterNot { it.song.song.isLocal }
                                                .map { it.song.toMediaMetadata() }
                                        )
                                    }
                                ) {
                                    com.dd3boh.xenowave.ui.icons.XenoIcon(
                                        icon = com.dd3boh.xenowave.ui.icons.XenoSystemIcons.Download,
                                        contentDescription = stringResource(R.string.action_download)
                                    )
                                }
                            }
                        }

                        IconButton(
                            onClick = {
                                playerConnection.enqueueEnd(
                                    items = songs.map { it.song.toMediaItem() }
                                )
                            }
                        ) {
                            com.dd3boh.xenowave.ui.icons.XenoIcon(
                                icon = com.dd3boh.xenowave.ui.icons.XenoIcons.AutoMirrored.QueueMusic,
                                contentDescription = stringResource(R.string.add_to_queue)
                            )
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    playerConnection.playQueue(
                        ListQueue(
                            title = playlist.playlist.name,
                            items = songs.map { it.song.toMediaMetadata() }.toList()
                        )
                    )
                },
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                modifier = Modifier.weight(1f)
            ) {
                com.dd3boh.xenowave.ui.icons.XenoIcon(
                    icon = com.dd3boh.xenowave.ui.icons.XenoPlayerIcons.PlayArrow,
                    contentDescription = stringResource(R.string.play),
                    modifier = Modifier.size(ButtonDefaults.IconSize)
                )
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.play))
            }

            OutlinedButton(
                onClick = {
                    playerConnection.playQueue(
                        ListQueue(
                            title = playlist.playlist.name,
                            items = songs.map { it.song.toMediaMetadata() },
                            startShuffled = true,
                        )
                    )
                },
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                modifier = Modifier.weight(1f)
            ) {
                com.dd3boh.xenowave.ui.icons.XenoIcon(
                    icon = com.dd3boh.xenowave.ui.icons.XenoPlayerIcons.Shuffle,
                    contentDescription = stringResource(R.string.shuffle),
                    modifier = Modifier.size(ButtonDefaults.IconSize)
                )
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.shuffle))
            }
        }
    }
}
