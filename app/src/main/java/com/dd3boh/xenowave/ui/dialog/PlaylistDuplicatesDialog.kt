package com.dd3boh.xenowave.ui.dialog

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.db.entities.PlaylistSong
import com.dd3boh.xenowave.ui.component.button.IconButton
import com.dd3boh.xenowave.ui.component.items.ListItem
import com.dd3boh.xenowave.ui.icons.XenoActionIcons
import com.dd3boh.xenowave.ui.icons.XenoIcon
import com.dd3boh.xenowave.ui.icons.XenoLibraryIcons
import com.dd3boh.xenowave.ui.screens.playlist.PlaylistDuplicateGroup
import com.dd3boh.xenowave.utils.joinByBullet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun PlaylistDuplicatesDialog(
    groups: List<PlaylistDuplicateGroup>,
    onRemoveOccurrence: suspend (PlaylistSong) -> Boolean,
    onDismiss: () -> Unit,
) {
    var pendingRemoval by remember { mutableStateOf<PlaylistSong?>(null) }
    var isRemoving by remember { mutableStateOf(false) }
    var removeFailed by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(groups) {
        if (groups.isEmpty()) onDismiss()
    }

    DefaultDialog(
        onDismiss = {
            if (!isRemoving) onDismiss()
        },
        title = {
            Text(stringResource(R.string.playlist_duplicates_title))
        },
        buttons = {
            TextButton(
                onClick = onDismiss,
                enabled = !isRemoving,
            ) {
                Text(stringResource(R.string.dismiss))
            }
        },
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
        ) {
            groups.forEach { group ->
                item(key = "group-${group.songId}") {
                    Text(
                        text = group.song.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                items(
                    items = group.occurrences,
                    key = { it.map.id },
                ) { occurrence ->
                    val occurrenceIndex = group.occurrences.indexOf(occurrence)
                    ListItem(
                        title = stringResource(
                            R.string.playlist_duplicate_occurrence,
                            occurrenceIndex + 1,
                            group.occurrences.size,
                        ),
                        subtitle = joinByBullet(
                            group.song.artists.joinToString { it.name },
                            group.song.song.albumName,
                        ),
                        thumbnailContent = {},
                        trailingContent = {
                            IconButton(
                                onClick = {
                                    pendingRemoval = occurrence
                                    removeFailed = false
                                },
                                enabled = !isRemoving,
                            ) {
                                XenoIcon(
                                    icon = XenoLibraryIcons.PlaylistRemove,
                                    contentDescription = stringResource(R.string.remove_from_playlist),
                                )
                            }
                        },
                    )
                }
            }
        }

        if (removeFailed) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.playlist_remove_duplicate_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }

    pendingRemoval?.let { song ->
        DefaultDialog(
            onDismiss = {
                if (!isRemoving) pendingRemoval = null
            },
            content = {
                Text(
                    text = stringResource(R.string.playlist_remove_duplicate_confirm, song.song.title),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            },
            buttons = {
                TextButton(
                    onClick = { pendingRemoval = null },
                    enabled = !isRemoving,
                ) {
                    Text(stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            isRemoving = true
                            val removed = try {
                                onRemoveOccurrence(song)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                false
                            }
                            removeFailed = !removed
                            isRemoving = false
                            pendingRemoval = null
                        }
                    },
                    enabled = !isRemoving,
                ) {
                    Text(stringResource(R.string.remove_from_playlist))
                }
            },
        )
    }
}
