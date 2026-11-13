/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.ui.dialog

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.dd3boh.xenowave.LocalDatabase
import com.dd3boh.xenowave.LocalSnackbarHostState
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.db.entities.PlaylistEntity
import com.dd3boh.xenowave.db.entities.PlaylistSongMap
import com.dd3boh.xenowave.models.MediaMetadata
import com.dd3boh.xenowave.sharing.PlaylistImportCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Confirms a portable playlist before writing anything. The import creates a
 * new local playlist in one transaction and never uploads it automatically.
 */
@Composable
fun ImportSharedPlaylistDialog() {
    val pending by PlaylistImportCoordinator.pending.collectAsState()
    val playlist = pending ?: return
    BlockingDialogEffect("import-shared-playlist")

    val context = LocalContext.current
    val database = LocalDatabase.current
    val snackbarHostState = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {
            if (!saving) PlaylistImportCoordinator.consume()
        },
        title = { Text(stringResource(R.string.share_import_title)) },
        text = {
            Text(
                stringResource(
                    R.string.share_import_summary,
                    playlist.name,
                    playlist.tracks.size,
                    playlist.excludedLocalCount,
                )
            )
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        val imported = withContext(Dispatchers.IO) {
                            runCatching {
                                database.inTransaction {
                                    val entity = PlaylistEntity(
                                        name = playlist.name,
                                        isLocal = true,
                                    )
                                    insert(entity)
                                    playlist.tracks.forEachIndexed { index, track ->
                                        insert(
                                            MediaMetadata(
                                                id = track.videoId,
                                                title = track.title,
                                                artists = track.artists.map {
                                                    MediaMetadata.Artist(id = null, name = it)
                                                },
                                                duration = -1,
                                                genre = null,
                                            )
                                        )
                                        insertPlaylistMapFromSync(
                                            PlaylistSongMap(
                                                playlistId = entity.id,
                                                songId = track.videoId,
                                                position = index,
                                            )
                                        )
                                    }
                                    true
                                }
                            }.isSuccess
                        }
                        saving = false
                        if (imported) {
                            PlaylistImportCoordinator.consume()
                            snackbarHostState.showSnackbar(
                                context.getString(
                                    R.string.share_playlist_imported,
                                    playlist.tracks.size,
                                )
                            )
                        } else {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.share_playlist_import_failed)
                            )
                        }
                    }
                }
            ) {
                Text(stringResource(R.string.share_import_save))
            }
        },
        dismissButton = {
            TextButton(
                enabled = !saving,
                onClick = { PlaylistImportCoordinator.consume() }
            ) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
