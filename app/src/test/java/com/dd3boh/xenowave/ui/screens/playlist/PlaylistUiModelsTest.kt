package com.dd3boh.xenowave.ui.screens.playlist

import com.dd3boh.xenowave.constants.PlaylistSongSortType
import com.dd3boh.xenowave.db.entities.ArtistEntity
import com.dd3boh.xenowave.db.entities.PlaylistSong
import com.dd3boh.xenowave.db.entities.PlaylistSongMap
import com.dd3boh.xenowave.db.entities.Song
import com.dd3boh.xenowave.db.entities.SongEntity
import com.dd3boh.xenowave.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class PlaylistUiModelsTest {

    @Test
    fun searchMatchesTitleArtistAndAlbumIgnoringCase() {
        val songs = listOf(
            playlistSong(1, "alpha", "Alpha Song", "North Artist", "First Album", 120, 0),
            playlistSong(2, "beta", "Beta Song", "West Artist", "Second Album", 180, 1),
        )

        assertEquals(listOf("alpha"), filterPlaylistSongs(songs, "alp").map { it.song.id })
        assertEquals(listOf("beta"), filterPlaylistSongs(songs, "west").map { it.song.id })
        assertEquals(listOf("beta"), filterPlaylistSongs(songs, "second").map { it.song.id })
        assertEquals(listOf("alpha", "beta"), filterPlaylistSongs(songs, "SONG").map { it.song.id })
    }

    @Test
    fun emptySearchReturnsTheOriginalListAndMissingSearchReturnsEmpty() {
        val songs = listOf(
            playlistSong(1, "alpha", "Alpha Song", "Artist", "Album", 120, 0),
        )

        assertSame(songs, filterPlaylistSongs(songs, "   "))
        assertTrue(filterPlaylistSongs(songs, "does-not-exist").isEmpty())
    }

    @Test
    fun visualSortingDoesNotMutatePersistedOrder() {
        val songs = listOf(
            playlistSong(1, "one", "Zulu", "Artist", "Album", 300, 0),
            playlistSong(2, "two", "Alpha", "Artist", "Album", 100, 1),
        )

        val sorted = sortPlaylistSongs(songs, PlaylistSongSortType.NAME, descending = false)

        assertEquals(listOf("two", "one"), sorted.map { it.song.id })
        assertEquals(listOf("one", "two"), songs.map { it.song.id })
        assertSame(songs, sortPlaylistSongs(songs, PlaylistSongSortType.CUSTOM, descending = true))
    }

    @Test
    fun albumAndDurationSortingUseSongMetadata() {
        val songs = listOf(
            playlistSong(1, "one", "One", "Artist", "Zulu Album", 300, 0),
            playlistSong(2, "two", "Two", "Artist", "Alpha Album", 100, 1),
        )

        assertEquals(
            listOf("two", "one"),
            sortPlaylistSongs(songs, PlaylistSongSortType.ALBUM, descending = false).map { it.song.id },
        )
        assertEquals(
            listOf("two", "one"),
            sortPlaylistSongs(songs, PlaylistSongSortType.DURATION, descending = false).map { it.song.id },
        )
    }

    @Test
    fun insightsCountSongsDurationOfflineEntriesAndDuplicateOccurrences() {
        val songs = listOf(
            playlistSong(1, "same", "Same", "Artist", "Album", 120, 0, downloaded = true),
            playlistSong(2, "same", "Same", "Artist", "Album", 120, 1, isLocal = true),
            playlistSong(3, "other", "Other", "Artist", "Album", 60, 2),
        )

        val insights = analyzePlaylist(songs)

        assertEquals(3, insights.songCount)
        assertEquals(300L, insights.totalDurationSeconds)
        assertEquals(3, insights.knownDurationCount)
        assertEquals(2, insights.offlineCount)
        assertEquals(1, insights.duplicateGroups.size)
        assertEquals(1, insights.duplicateCount)
    }

    @Test
    fun duplicateDiagnosticsUseStableSongIdAndKeepOccurrenceIdentity() {
        val songs = listOf(
            playlistSong(10, "same-song", "Same", "Artist", "Album", 120, 0),
            playlistSong(11, "same-song", "Same", "Artist", "Album", 120, 1),
            playlistSong(12, "different-song", "Same", "Artist", "Album", 120, 2),
        )

        val insights = analyzePlaylist(songs)

        assertEquals(1, insights.duplicateGroups.size)
        assertEquals("same-song", insights.duplicateGroups.single().songId)
        assertEquals(
            listOf(10, 11),
            insights.duplicateGroups.single().occurrences.map { it.map.id },
        )
        assertEquals(
            listOf(11),
            selectedPlaylistSongs(songs, setOf(11)).map { it.map.id },
        )
    }

    @Test
    fun emptyPlaylistProducesZeroInsights() {
        val insights = analyzePlaylist(emptyList())

        assertEquals(0, insights.songCount)
        assertEquals(0L, insights.totalDurationSeconds)
        assertEquals(0, insights.knownDurationCount)
        assertEquals(0, insights.offlineCount)
        assertEquals(0, insights.duplicateCount)
    }

    @Test
    fun suggestionsExcludeExistingRemoveDuplicatesAndKeepInputOrder() {
        val candidates = listOf(
            metadata("new-one"),
            metadata("existing"),
            metadata("new-one"),
            metadata("  "),
            metadata("new-two"),
        )

        val filtered = filterPlaylistSuggestions(candidates, setOf("existing"))

        assertEquals(listOf("new-one", "new-two"), filtered.map { it.id })
    }

    private fun playlistSong(
        mapId: Int,
        songId: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
        position: Int,
        downloaded: Boolean = false,
        isLocal: Boolean = false,
    ): PlaylistSong {
        val song = Song(
            song = SongEntity(
                id = songId,
                title = title,
                duration = duration,
                albumName = album,
                dateDownload = if (downloaded) LocalDateTime.of(2026, 1, 1, 0, 0) else null,
                isLocal = isLocal,
                localPath = if (isLocal) "/tmp/$songId" else null,
            ),
            artists = listOf(
                ArtistEntity(
                    id = "artist-$artist",
                    name = artist,
                )
            ),
        )
        return PlaylistSong(
            map = PlaylistSongMap(
                id = mapId,
                playlistId = "playlist",
                songId = songId,
                position = position,
            ),
            song = song,
        )
    }

    private fun metadata(id: String) = MediaMetadata(
        id = id,
        title = id,
        artists = emptyList(),
        duration = 60,
        genre = null,
    )
}
