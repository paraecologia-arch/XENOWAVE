package com.dd3boh.xenowave.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.models.MediaMetadata
import com.dd3boh.xenowave.playback.queues.YouTubeQueue
import com.dd3boh.xenowave.ui.component.button.IconButton
import com.dd3boh.xenowave.ui.component.items.ListItem
import com.dd3boh.xenowave.ui.icons.XenoActionIcons
import com.dd3boh.xenowave.ui.icons.XenoIcon
import com.dd3boh.xenowave.ui.screens.playlist.filterPlaylistSuggestions
import com.dd3boh.xenowave.utils.joinByBullet
import com.zionhuang.innertube.models.WatchEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private sealed interface SuggestionsState {
    data object Loading : SuggestionsState
    data class Loaded(val items: List<MediaMetadata>) : SuggestionsState
    data object Empty : SuggestionsState
    data object Error : SuggestionsState
}

@Composable
fun PlaylistSuggestionsDialog(
    seed: MediaMetadata?,
    playlistRadioEndpoint: WatchEndpoint? = null,
    sourceLabel: String,
    existingSongIds: Set<String>,
    onAdd: suspend (List<MediaMetadata>) -> Boolean,
    onDismiss: () -> Unit,
) {
    var reloadKey by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<SuggestionsState>(SuggestionsState.Loading) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var isAdding by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(seed?.id, playlistRadioEndpoint?.playlistId, reloadKey) {
        state = SuggestionsState.Loading
        selectedIds = emptySet()
        addError = false

        try {
            val queue = playlistRadioEndpoint?.let { YouTubeQueue(it) }
                ?: seed?.let(YouTubeQueue::radio)
            if (queue == null) {
                state = SuggestionsState.Empty
                return@LaunchedEffect
            }
            val status = queue.getInitialStatus()
            if (!isActive) return@LaunchedEffect
            val suggestions = filterPlaylistSuggestions(status.items, existingSongIds)
            state = if (suggestions.isEmpty()) {
                SuggestionsState.Empty
            } else {
                SuggestionsState.Loaded(suggestions)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (isActive) {
                state = SuggestionsState.Error
            }
        }
    }

    fun addSuggestions(items: List<MediaMetadata>) {
        if (isAdding || items.isEmpty()) return
        coroutineScope.launch {
            isAdding = true
            addError = false
            val added = try {
                onAdd(items)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
            if (added) {
                val addedIds = items.map { it.id }.toSet()
                val remaining = (state as? SuggestionsState.Loaded)
                    ?.items
                    .orEmpty()
                    .filterNot { it.id in addedIds }
                state = if (remaining.isEmpty()) {
                    SuggestionsState.Empty
                } else {
                    SuggestionsState.Loaded(remaining)
                }
                selectedIds = selectedIds - addedIds
            } else {
                addError = true
            }
            isAdding = false
        }
    }

    DefaultDialog(
        onDismiss = {
            if (!isAdding) onDismiss()
        },
        modifier = Modifier.widthIn(max = 520.dp),
        title = {
            Text(stringResource(R.string.suggest_songs))
        },
        buttons = {
            TextButton(
                onClick = onDismiss,
                enabled = !isAdding,
            ) {
                Text(stringResource(android.R.string.cancel))
            }

            val loadedItems = (state as? SuggestionsState.Loaded)?.items.orEmpty()
            TextButton(
                onClick = {
                    addSuggestions(loadedItems.filter { it.id in selectedIds })
                },
                enabled = selectedIds.isNotEmpty() && !isAdding,
            ) {
                Text(stringResource(R.string.add_selected))
            }
        },
    ) {
        Text(
            text = stringResource(R.string.suggested_from, sourceLabel),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))

        when (val currentState = state) {
            SuggestionsState.Loading -> Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 24.dp),
            ) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(22.dp),
                )
                Text(stringResource(R.string.loading))
            }

            SuggestionsState.Empty -> Text(
                text = stringResource(R.string.suggested_songs_empty),
                modifier = Modifier.padding(vertical = 24.dp),
            )

            SuggestionsState.Error -> Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.suggested_songs_error),
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = { reloadKey++ }) {
                    Text(stringResource(R.string.retry))
                }
            }

            is SuggestionsState.Loaded -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
            ) {
                items(
                    items = currentState.items,
                    key = { it.id },
                ) { suggestion ->
                    val selected = suggestion.id in selectedIds
                    ListItem(
                        title = suggestion.title,
                        subtitle = joinByBullet(
                            suggestion.artists.joinToString { it.name },
                            suggestion.album?.title,
                        ),
                        thumbnailContent = {
                            Checkbox(
                                checked = selected,
                                onCheckedChange = {
                                    selectedIds = if (selected) {
                                        selectedIds - suggestion.id
                                    } else {
                                        selectedIds + suggestion.id
                                    }
                                },
                                modifier = Modifier.semantics {
                                    contentDescription = suggestion.title
                                },
                            )
                        },
                        trailingContent = {
                            IconButton(
                                onClick = { addSuggestions(listOf(suggestion)) },
                                enabled = !isAdding,
                            ) {
                                XenoIcon(
                                    icon = XenoActionIcons.Add,
                                    contentDescription = stringResource(R.string.add_to_playlist),
                                )
                            }
                        },
                    )
                }
            }
        }

        if (addError) {
            Text(
                text = stringResource(R.string.suggestion_add_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
