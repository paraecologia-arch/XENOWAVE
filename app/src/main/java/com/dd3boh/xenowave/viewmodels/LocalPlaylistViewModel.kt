package com.dd3boh.xenowave.viewmodels

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.xenowave.constants.PlaylistSongSortDescendingKey
import com.dd3boh.xenowave.constants.PlaylistSongSortType
import com.dd3boh.xenowave.constants.PlaylistSongSortTypeKey
import com.dd3boh.xenowave.db.MusicDatabase
import com.dd3boh.xenowave.extensions.toEnum
import com.dd3boh.xenowave.ui.screens.playlist.sortPlaylistSongs
import com.dd3boh.xenowave.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class LocalPlaylistViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val playlistId = savedStateHandle.get<String>("playlistId")!!
    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()
    private val _hasLoadError = MutableStateFlow(false)
    val hasLoadError = _hasLoadError.asStateFlow()

    val playlistWithSongs = combine(
        database.playlist(playlistId),
        database.playlistSongs(playlistId),
        context.dataStore.data
            .map {
                it[PlaylistSongSortTypeKey].toEnum(PlaylistSongSortType.CUSTOM) to
                        (it[PlaylistSongSortDescendingKey] ?: true)
            }
            .distinctUntilChanged()
    ) { playlist, songs, (sortType, sortDescending) ->
        val sortedSongs = sortPlaylistSongs(songs, sortType, sortDescending)

        Pair(playlist, sortedSongs)
    }.catch {
        _hasLoadError.value = true
        emit(Pair(null, emptyList()))
    }.stateIn(viewModelScope, SharingStarted.Lazily, Pair(null, emptyList()))

    init {
        // Fix playlist song order
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sortedSongs = playlistWithSongs.first().second.sortedWith(
                    compareBy({ it.map.position }, { it.map.id })
                )
                database.transaction {
                    sortedSongs.forEachIndexed { index, song ->
                        if (song.map.position != index) {
                            update(song.map.copy(position = index))
                        }
                    }
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    suspend fun removeDuplicateOccurrence(mapId: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            database.removeDuplicateOccurrence(playlistId, mapId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    suspend fun removePlaylistSongs(mapIds: Set<Int>): Boolean = withContext(Dispatchers.IO) {
        try {
            database.removePlaylistSongs(playlistId, mapIds)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }
}
