package com.dd3boh.xenowave.ui.menu

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import com.dd3boh.xenowave.LocalDatabase
import com.dd3boh.xenowave.LocalDownloadUtil
import com.dd3boh.xenowave.LocalNetworkConnected
import com.dd3boh.xenowave.LocalPlayerConnection
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.db.entities.Playlist
import com.dd3boh.xenowave.db.entities.PlaylistSong
import com.dd3boh.xenowave.db.entities.Song
import com.dd3boh.xenowave.extensions.toMediaItem
import com.dd3boh.xenowave.models.toMediaMetadata
import com.dd3boh.xenowave.playback.ExoDownloadService
import com.dd3boh.xenowave.playback.queues.ListQueue
import com.dd3boh.xenowave.playback.queues.YouTubeQueue
import com.dd3boh.xenowave.ui.component.button.IconButton
import com.dd3boh.xenowave.ui.component.items.PlaylistListItem
import com.dd3boh.xenowave.ui.dialog.AddToPlaylistDialog
import com.dd3boh.xenowave.ui.dialog.AddToQueueDialog
import com.dd3boh.xenowave.ui.dialog.DefaultDialog
import com.dd3boh.xenowave.ui.dialog.PlaylistSuggestionsDialog
import com.dd3boh.xenowave.ui.dialog.TextFieldDialog
import com.dd3boh.xenowave.models.MediaMetadata
import com.dd3boh.xenowave.utils.getDownloadState
import com.dd3boh.xenowave.utils.lmScannerCoroutine
import com.dd3boh.xenowave.utils.reportException
import com.dd3boh.xenowave.utils.syncCoroutine
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.WatchEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

@Composable
fun PlaylistMenu(
    navController: NavController,
    playlist: Playlist,
    coroutineScope: CoroutineScope,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val downloadUtil = LocalDownloadUtil.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val isNetworkConnected = LocalNetworkConnected.current
    val dbPlaylist by database.playlist(playlist.id).collectAsState(initial = playlist)
    var songs by remember {
        mutableStateOf(emptyList<Song>())
    }

    val m3uLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri: Uri? ->
        uri?.let {
            CoroutineScope(lmScannerCoroutine).launch {
                try {
                    var result = "#EXTM3U\n"
                    songs.forEach { s ->
                        val se = s.song
                        result += "#EXTINF:${se.duration},${s.artists.joinToString(";") { it.name }} - ${s.title}\n"
                        result += if (se.isLocal) "${se.id}, ${se.localPath}" else "https://youtube.com/watch?v=${se.id}"
                        result += "\n"
                    }
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(result.toByteArray(Charsets.UTF_8))
                    }
                } catch (e: IOException) {
                    reportException(e)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        database.playlistSongs(playlist.id).collect {
            songs = it.map(PlaylistSong::song)
        }
    }

    var downloadState by remember {
        mutableIntStateOf(Download.STATE_STOPPED)
    }

    val editable: Boolean = playlist.playlist.isEditable

    var showEditDialog by remember {
        mutableStateOf(false)
    }
    var showRemoveDownloadDialog by remember {
        mutableStateOf(false)
    }
    var showDeletePlaylistDialog by remember {
        mutableStateOf(false)
    }
    var showChooseQueueDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var showChoosePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var showSuggestionsDialog by remember {
        mutableStateOf(false)
    }
    var suggestionSeed by remember {
        mutableStateOf<MediaMetadata?>(null)
    }
    var suggestionEndpoint by remember {
        mutableStateOf<WatchEndpoint?>(null)
    }
    var suggestionSourceLabel by remember {
        mutableStateOf("")
    }

    LaunchedEffect(songs) {
        val songs = songs.filterNot { it.song.isLocal }
        if (songs.isEmpty()) return@LaunchedEffect
        downloadUtil.downloads.collect { downloads ->
            downloadState = getDownloadState(songs.map { downloads[it.id] })
        }
    }


    PlaylistListItem(
        playlist = playlist,
        trailingContent = {
            if (!playlist.playlist.isEditable) {
                IconButton(
                    onClick = {
                        database.query {
                            dbPlaylist?.playlist?.toggleLike()?.let { update(it) }
                        }
                    }
                ) {
                    com.dd3boh.xenowave.ui.icons.XenoIcon(
                        icon = (if (dbPlaylist?.playlist?.bookmarkedAt != null) R.drawable.favorite else R.drawable.favorite_border),
                        tint = if (dbPlaylist?.playlist?.bookmarkedAt != null) MaterialTheme.colorScheme.error else LocalContentColor.current,
                        contentDescription = null
                    )
                }
            }
        },
        showBadges = true
    )

    HorizontalDivider()

    GridMenu(
        contentPadding = PaddingValues(
            start = 8.dp,
            top = 8.dp,
            end = 8.dp,
            bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
        )
    ) {
        GridMenuItem(
            icon = com.dd3boh.xenowave.ui.icons.XenoPlayerIcons.PlayArrow,
            title = R.string.play
        ) {
            onDismiss()
            playerConnection.playQueue(
                ListQueue(
                    title = playlist.playlist.name,
                    items = songs.map { it.toMediaMetadata() },
                    playlistId = playlist.playlist.browseId
                )
            )
        }

        GridMenuItem(
            icon = com.dd3boh.xenowave.ui.icons.XenoPlayerIcons.Shuffle,
            title = R.string.shuffle
        ) {
            onDismiss()
            playerConnection.playQueue(
                ListQueue(
                    title = playlist.playlist.name,
                    items = songs.map { it.toMediaMetadata() },
                    startShuffled = true,
                    playlistId = playlist.playlist.browseId
                )
            )
        }

        if (isNetworkConnected) {
            playlist.playlist.browseId?.let { browseId ->
                playlist.playlist.radioEndpointParams?.let { radioEndpointParams ->
                    GridMenuItem(
                        icon = com.dd3boh.xenowave.ui.icons.XenoPlayerIcons.Radio,
                        title = R.string.start_radio
                    ) {
                        playerConnection.playQueue(
                            YouTubeQueue(
                                WatchEndpoint(
                                    playlistId = "RDAMPL$browseId",
                                    params = radioEndpointParams,
                                )
                            ), isRadio = true
                        )
                        onDismiss()
                    }
                }
            }
        }

        val playlistRadioEndpoint = playlist.playlist.browseId?.let { browseId ->
            playlist.playlist.radioEndpointParams?.let { radioEndpointParams ->
                WatchEndpoint(
                    playlistId = "RDAMPL$browseId",
                    params = radioEndpointParams,
                )
            }
        }
        val localSuggestionSeed = songs.firstOrNull { !it.song.isLocal }?.toMediaMetadata()
        if (
            isNetworkConnected &&
            playlist.playlist.isLocal &&
            (playlistRadioEndpoint != null || localSuggestionSeed != null)
        ) {
            GridMenuItem(
                icon = com.dd3boh.xenowave.ui.icons.XenoLibraryIcons.Interests,
                title = R.string.suggest_songs
            ) {
                suggestionEndpoint = playlistRadioEndpoint
                suggestionSeed = localSuggestionSeed
                suggestionSourceLabel = if (playlistRadioEndpoint != null) {
                    playlist.playlist.name
                } else {
                    localSuggestionSeed?.title?.takeIf { it.isNotBlank() }
                        ?: playlist.playlist.name
                }
                showSuggestionsDialog = true
            }
        }

        GridMenuItem(
            icon = com.dd3boh.xenowave.ui.icons.XenoIcons.AutoMirrored.PlaylistPlay,
            title = R.string.play_next
        ) {
            coroutineScope.launch {
                playerConnection.enqueueNext(songs.map { it.toMediaItem() })
            }
            onDismiss()
        }

        GridMenuItem(
            icon = com.dd3boh.xenowave.ui.icons.XenoIcons.AutoMirrored.QueueMusic,
            title = R.string.add_to_queue
        ) {
            showChooseQueueDialog = true
        }

        GridMenuItem(
            icon = com.dd3boh.xenowave.ui.icons.XenoIcons.AutoMirrored.PlaylistAdd,
            title = R.string.add_to_playlist
        ) {
            showChoosePlaylistDialog = true
        }

        if (songs.fastAny { !it.song.isLocal }) {
            DownloadGridMenu(
                state = downloadState,
                onDownload = {
                    val _songs = songs.filterNot { it.song.isLocal }.map { it.toMediaMetadata() }
                    downloadUtil.download(_songs)
                },
                onRemoveDownload = {
                    showRemoveDownloadDialog = true
                }
            )
        }

        if (editable) {
            GridMenuItem(
                icon = com.dd3boh.xenowave.ui.icons.XenoActionIcons.Edit,
                title = R.string.edit
            ) {
                showEditDialog = true
            }
        }
        GridMenuItem(
            icon = com.dd3boh.xenowave.ui.icons.XenoLibraryIcons.PlaylistRemove,
            title = R.string.delete
        ) {
            showDeletePlaylistDialog = true
        }

        playlist.playlist.shareLink?.let { shareLink ->
            GridMenuItem(
                icon = com.dd3boh.xenowave.ui.icons.XenoActionIcons.Share,
                title = R.string.share
            ) {
                val intent = Intent().apply {
                    action = Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, shareLink)
                }
                context.startActivity(Intent.createChooser(intent, null))
                onDismiss()
            }
        }
        GridMenuItem(
            icon = com.dd3boh.xenowave.ui.icons.XenoSystemIcons.Output,
            title = R.string.m3u_export
        ) {
            m3uLauncher.launch("playlist.m3u")
        }
    }

    if (showEditDialog) {
        TextFieldDialog(
            icon = { com.dd3boh.xenowave.ui.icons.XenoIcon(icon = com.dd3boh.xenowave.ui.icons.XenoActionIcons.Edit, contentDescription = null) },
            title = { Text(text = stringResource(R.string.edit_playlist)) },
            onDismiss = { showEditDialog = false },
            initialTextFieldValue = TextFieldValue(
                playlist.playlist.name,
                TextRange(playlist.playlist.name.length)
            ),
            onDone = { name ->
                onDismiss()
                database.query {
                    update(playlist.playlist.copy(name = name))
                }

                coroutineScope.launch(syncCoroutine) {
                    playlist.playlist.browseId?.let { YouTube.renamePlaylist(it, name) }
                }
            }
        )
    }

    /**
     * ---------------------------
     * Dialogs
     * ---------------------------
     */

    if (showRemoveDownloadDialog) {
        DefaultDialog(
            onDismiss = { showRemoveDownloadDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.remove_download_playlist_confirm, playlist.playlist.name),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
            },
            buttons = {
                TextButton(
                    onClick = {
                        showRemoveDownloadDialog = false
                    }
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        showRemoveDownloadDialog = false
                        songs.forEach { song ->
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

    if (showDeletePlaylistDialog) {
        DefaultDialog(
            onDismiss = { showDeletePlaylistDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.delete_playlist_confirm, playlist.playlist.name),
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
                        onDismiss()
                        database.query {
                            delete(playlist.playlist)
                        }

                        if (!playlist.playlist.isLocal) {
                            coroutineScope.launch(syncCoroutine) {
                                playlist.playlist.browseId?.let { YouTube.deletePlaylist(it) }
                            }
                        }
                    }
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            }
        )
    }

    if (showChooseQueueDialog) {
        AddToQueueDialog(

            onAdd = { queueName ->
                val q = playerConnection.service.queueBoard.addQueue(
                    queueName, songs.map { it.toMediaMetadata() },
                    forceInsert = true, delta = false
                )
                q?.let {
                    playerConnection.service.queueBoard.setCurrQueue(it)
                }
            },
            onDismiss = {
                showChooseQueueDialog = false
            }
        )
    }

    if (showChoosePlaylistDialog) {
        AddToPlaylistDialog(
            navController = navController,
            songIds = songs.map { it.id },
            onPreAdd = { playlist ->
                // add songs to playlist and push to ytm
                songs.let { playlist.playlist.browseId?.let { YouTube.addPlaylistToPlaylist(it, playlist.id) } }

                playlist.playlist.browseId?.let { playlistId ->
                    YouTube.addPlaylistToPlaylist(playlistId, playlist.id)
                }
                songs.map { it.id }
            },
            onDismiss = { showChoosePlaylistDialog = false }
        )
    }

    if (showSuggestionsDialog) {
        if (suggestionSourceLabel.isNotBlank()) {
            PlaylistSuggestionsDialog(
                seed = suggestionSeed,
                playlistRadioEndpoint = suggestionEndpoint,
                sourceLabel = suggestionSourceLabel,
                existingSongIds = songs.map { it.id }.toSet(),
                onAdd = { items ->
                    withContext(Dispatchers.IO) {
                        try {
                            database.addSuggestedSongsToPlaylist(playlist, items)
                            true
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            false
                        }
                    }
                },
                onDismiss = {
                    showSuggestionsDialog = false
                    suggestionSeed = null
                    suggestionEndpoint = null
                    suggestionSourceLabel = ""
                }
            )
        }
    }
}
