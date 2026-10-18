package com.dd3boh.xenowave.ui.menu

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import com.dd3boh.xenowave.LocalDatabase
import com.dd3boh.xenowave.LocalDownloadUtil
import com.dd3boh.xenowave.LocalPlayerConnection
import com.dd3boh.xenowave.LocalSyncUtils
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.extensions.toMediaItem
import com.dd3boh.xenowave.models.MediaMetadata
import com.dd3boh.xenowave.playback.DownloadUtil
import com.dd3boh.xenowave.playback.ExoDownloadService
import com.dd3boh.xenowave.playback.queues.ListQueue
import com.dd3boh.xenowave.ui.dialog.AddToPlaylistDialog
import com.dd3boh.xenowave.ui.dialog.AddToQueueDialog
import com.dd3boh.xenowave.ui.dialog.DefaultDialog
import com.dd3boh.xenowave.utils.getDownloadState
import java.time.LocalDateTime

/**
 * Generic song menu
 */
@Composable
fun SelectionMediaMetadataMenu(
    navController: NavController,
    selection: List<MediaMetadata>,
    onDismiss: () -> Unit,
    clearAction: () -> Unit,
    onRemoveFromHistory: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val downloadUtil = LocalDownloadUtil.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val syncUtils = LocalSyncUtils.current

    val allInLibrary by remember(selection) { // exclude local songs
        mutableStateOf(selection.isNotEmpty() && selection.all { !it.isLocal && it.inLibrary != null })
    }
    val allLocal by remember(selection) { // if only local songs in this selection
        mutableStateOf(selection.isNotEmpty() && selection.all { it.isLocal })
    }

    val allLiked by remember(selection) {
        mutableStateOf(selection.isNotEmpty() && selection.all { it.liked })
    }

    var downloadState by remember {
        mutableIntStateOf(Download.STATE_STOPPED)
    }

    var showChooseQueueDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var showChoosePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var showRemoveDownloadDialog by remember {
        mutableStateOf(false)
    }

    LaunchedEffect(selection) {
        if (selection.isEmpty()) {
            onDismiss()
        } else {
            val selection = selection.filterNot { it.isLocal }
            if (selection.isEmpty()) return@LaunchedEffect
            downloadUtil.downloads.collect { downloads ->
                downloadState = getDownloadState(selection.map { downloads[it.id] })
            }
        }
    }


    GridMenu(
        contentPadding = PaddingValues(
            start = 8.dp,
            top = 8.dp,
            end = 8.dp,
            bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
        )
    ) {
        GridMenuItem(
            icon = R.drawable.play,
            title = R.string.play
        ) {
            onDismiss()
            playerConnection.playQueue(
                ListQueue(
                    title = "Selection",
                    items = selection
                )
            )
            clearAction()
        }

        GridMenuItem(
            icon = com.dd3boh.xenowave.ui.icons.XenoIcons.AutoMirrored.PlaylistPlay,
            title = R.string.play_next,
        ) {
            onDismiss()
            playerConnection.enqueueNext(selection.map { it.toMediaItem() })
            clearAction()
        }

        GridMenuItem(
            icon = R.drawable.shuffle_on,
            title = R.string.shuffle
        ) {
            onDismiss()
            playerConnection.playQueue(
                ListQueue(
                    title = "Selection",
                    items = selection,
                    startShuffled = true,
                )
            )
            clearAction()
        }

        GridMenuItem(
            icon = R.drawable.queue_music,
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

        if (onRemoveFromPlaylist != null) {
            GridMenuItem(
                icon = com.dd3boh.xenowave.ui.icons.XenoLibraryIcons.PlaylistRemove,
                title = R.string.remove_from_playlist
            ) {
                onRemoveFromPlaylist()
                onDismiss()
            }
        }

        if (!allLocal) {
            if (allInLibrary) {
                GridMenuItem(
                    icon = com.dd3boh.xenowave.ui.icons.XenoLibraryIcons.LibraryAddCheck,
                    title = R.string.remove_all_from_library
                ) {
                    database.transaction {
                        selection.forEach { song ->
                            toggleInLibrary(song.id, null)
                        }
                    }
                }
            } else {
                GridMenuItem(
                    icon = com.dd3boh.xenowave.ui.icons.XenoLibraryIcons.LibraryAdd,
                    title = R.string.add_all_to_library
                ) {
                    database.transaction {
                        selection.forEach { song ->
                            if (!song.isLocal) {
                                toggleInLibrary(song.id, LocalDateTime.now())
                            }
                        }
                    }
                }
            }
        }

        GridMenuItem(
            icon = if (allLiked) com.dd3boh.xenowave.ui.icons.XenoPlayerIcons.Favorite else com.dd3boh.xenowave.ui.icons.XenoPlayerIcons.FavoriteBorder,
            tint = { if (allLiked) MaterialTheme.colorScheme.error else LocalContentColor.current },
            title = if (allLiked) R.string.action_remove_like_all else R.string.action_like_all,
        ) {
            database.query {
                if (allLiked) {
                    selection.forEach { song ->
                        val s = song.toSongEntity().toggleLike()
                        update(s)
                        if (!s.isLocal) {
                            syncUtils.likeSong(s)
                        }
                    }
                } else {
                    selection.filter { !it.liked }.forEach { song ->
                        val s = song.toSongEntity().toggleLike()
                        update(s)
                        if (!s.isLocal) {
                            syncUtils.likeSong(s)
                        }
                    }
                }
            }
        }

        DownloadGridMenu(
            state = downloadState,
            onDownload = {
                val songs = selection.filterNot { it.isLocal }
                downloadUtil.download(songs)
                onDismiss()
                clearAction()
            },
            onRemoveDownload = {
                showRemoveDownloadDialog = true
            }
        )

        if (onRemoveFromHistory != null) {
            GridMenuItem(
                icon = com.dd3boh.xenowave.ui.icons.XenoActionIcons.Delete,
                title = R.string.remove_from_history,
            ) {
                onRemoveFromHistory()
                onDismiss()
                clearAction()
            }
        }
    }

    /**
     * ---------------------------
     * Dialogs
     * ---------------------------
     */

    if (showChooseQueueDialog) {
        AddToQueueDialog(
            onAdd = { queueName ->
                val q = playerConnection.service.queueBoard.addQueue(
                    queueName,
                    selection,
                    forceInsert = true,
                    delta = false
                )
                q?.let {
                    playerConnection.service.queueBoard.setCurrQueue(it)
                }
                onDismiss()
                clearAction()
            },
            onDismiss = {
                showChooseQueueDialog = false
            }
        )
    }


    if (showChoosePlaylistDialog) {
        AddToPlaylistDialog(
            navController = navController,
            songIds = selection.map { it.id },
            onPreAdd = {
                selection.forEach { s ->
                    database.insert(s)
                }
                emptyList()
            },
            onDismiss = {
                showChoosePlaylistDialog = false
                onDismiss()
                clearAction()
            }
        )
    }

    if (showRemoveDownloadDialog) {
        DefaultDialog(
            onDismiss = { showRemoveDownloadDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.remove_download_playlist_confirm, "selection"),
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
                        selection.forEach { song ->
                            DownloadService.sendRemoveDownload(
                                context,
                                ExoDownloadService::class.java,
                                song.id,
                                false
                            )
                        }
                        onDismiss()
                        clearAction()
                    }
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            }
        )
    }
}
