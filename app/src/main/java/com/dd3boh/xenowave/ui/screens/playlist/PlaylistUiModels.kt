package com.dd3boh.xenowave.ui.screens.playlist

import com.dd3boh.xenowave.constants.PlaylistSongSortType
import com.dd3boh.xenowave.db.entities.PlaylistSong
import com.dd3boh.xenowave.db.entities.Song
import com.dd3boh.xenowave.models.MediaMetadata
import com.dd3boh.xenowave.playback.isCompletedDownload

data class PlaylistDuplicateGroup(
    val songId: String,
    val song: Song,
    val occurrences: List<PlaylistSong>,
)

data class PlaylistInsights(
    val songCount: Int,
    val totalDurationSeconds: Long,
    val knownDurationCount: Int,
    val offlineCount: Int,
    val duplicateGroups: List<PlaylistDuplicateGroup>,
) {
    val duplicateCount: Int
        get() = duplicateGroups.sumOf { it.occurrences.size - 1 }
}

internal fun analyzePlaylist(songs: List<PlaylistSong>): PlaylistInsights {
    val knownDurations = songs.mapNotNull { song ->
        song.song.song.duration.takeIf { it > 0 }?.toLong()
    }
    val duplicateGroups = songs
        .groupBy { it.song.id }
        .values
        .filter { it.size > 1 }
        .map { occurrences ->
            PlaylistDuplicateGroup(
                songId = occurrences.first().song.id,
                song = occurrences.first().song,
                occurrences = occurrences,
            )
        }

    return PlaylistInsights(
        songCount = songs.size,
        totalDurationSeconds = knownDurations.sum(),
        knownDurationCount = knownDurations.size,
        offlineCount = songs.count {
            it.song.song.isLocal || it.song.song.dateDownload.isCompletedDownload()
        },
        duplicateGroups = duplicateGroups,
    )
}

internal fun filterPlaylistSongs(
    songs: List<PlaylistSong>,
    query: String,
): List<PlaylistSong> {
    val needle = query.trim()
    if (needle.isEmpty()) return songs

    return songs.filter { playlistSong ->
        val song = playlistSong.song
        song.song.title.contains(needle, ignoreCase = true) ||
            song.artists.any { it.name.contains(needle, ignoreCase = true) } ||
            song.song.albumName?.contains(needle, ignoreCase = true) == true ||
            song.album?.title?.contains(needle, ignoreCase = true) == true
    }
}

internal fun sortPlaylistSongs(
    songs: List<PlaylistSong>,
    sortType: PlaylistSongSortType,
    descending: Boolean,
): List<PlaylistSong> {
    if (sortType == PlaylistSongSortType.CUSTOM) return songs

    val comparator = when (sortType) {
        PlaylistSongSortType.CUSTOM -> return songs
        PlaylistSongSortType.NAME -> compareBy<PlaylistSong, String>(
            String.CASE_INSENSITIVE_ORDER
        ) {
            it.song.song.title
        }

        PlaylistSongSortType.ARTIST -> compareBy<PlaylistSong, String>(
            String.CASE_INSENSITIVE_ORDER
        ) {
            it.song.artists.joinToString { artist -> artist.name }
        }

        PlaylistSongSortType.ALBUM -> compareBy<PlaylistSong, String>(
            String.CASE_INSENSITIVE_ORDER
        ) {
            it.song.song.albumName ?: it.song.album?.title.orEmpty()
        }

        PlaylistSongSortType.DURATION -> compareBy<PlaylistSong> {
            it.song.song.duration.takeIf { duration -> duration > 0 } ?: Int.MAX_VALUE
        }

        PlaylistSongSortType.ADDED_DATE -> compareBy<PlaylistSong> { it.song.song.inLibrary }
        PlaylistSongSortType.MODIFIED_DATE -> compareBy<PlaylistSong> { it.song.song.dateModified }
        PlaylistSongSortType.RELEASE_DATE -> compareBy<PlaylistSong> { it.song.song.getDateLong() }
    }

    return if (descending) {
        songs.sortedWith(comparator.reversed())
    } else {
        songs.sortedWith(comparator)
    }
}

internal fun selectedPlaylistSongs(
    songs: List<PlaylistSong>,
    selectedMapIds: Set<Int>,
): List<PlaylistSong> = songs.filter { it.map.id in selectedMapIds }

internal fun filterPlaylistSuggestions(
    candidates: List<MediaMetadata>,
    existingSongIds: Set<String>,
): List<MediaMetadata> {
    val seen = HashSet<String>()
    return candidates.filter { candidate ->
        candidate.id.isNotBlank() &&
            candidate.id !in existingSongIds &&
            seen.add(candidate.id)
    }
}
