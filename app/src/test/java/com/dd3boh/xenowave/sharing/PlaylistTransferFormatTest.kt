package com.dd3boh.xenowave.sharing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaylistTransferFormatTest {

    @Test
    fun `round trip preserves order and duplicates`() {
        val result = PlaylistTransferFormat.export(
            playlistName = "Minha lista\nfavorita",
            songs = listOf(
                ExportSong("vid00000001", "Primeira", listOf("Artista A"), isLocal = false),
                ExportSong("vid00000002", "Segunda", listOf("Artista B"), isLocal = false),
                ExportSong("vid00000001", "Primeira", listOf("Artista A"), isLocal = false),
                ExportSong("/storage/emulated/0/a.mp3", "Local", emptyList(), isLocal = true),
            ),
        ) as PlaylistExportResult.Success

        assertEquals(3, result.playlist.tracks.size)
        assertEquals(1, result.playlist.excludedLocalCount)
        assertEquals("Minha lista favorita", result.playlist.name)

        val parsed = PlaylistTransferFormat.parse(result.json) as PlaylistParseResult.Valid
        assertEquals(result.playlist, parsed.playlist)
        assertEquals(
            listOf("vid00000001", "vid00000002", "vid00000001"),
            parsed.playlist.tracks.map { it.videoId },
        )
    }

    @Test
    fun `local only playlist is not exported as a fake public link`() {
        val result = PlaylistTransferFormat.export(
            "Só local",
            listOf(ExportSong("/storage/a.mp3", "Local", emptyList(), isLocal = true)),
        )
        assertTrue(result is PlaylistExportResult.Failure)
        assertEquals("no-online-tracks", (result as PlaylistExportResult.Failure).reason)
    }

    @Test
    fun `rejects malformed documents and path injection`() {
        assertTrue(PlaylistTransferFormat.parse("{") is PlaylistParseResult.Invalid)
        assertTrue(
            PlaylistTransferFormat.parse(
                """{"schemaVersion":1,"kind":"xenowave-playlist-references","name":"x","tracks":[{"videoId":"../../etc/passwd","title":"x","artists":[]}]}"""
            ) is PlaylistParseResult.Invalid
        )
    }

    @Test
    fun `rejects oversized track counts`() {
        val tracks = (0..PlaylistTransferFormat.MAX_TRACKS).joinToString(",") {
            """{"videoId":"vid00000001","title":"x","artists":[]}"""
        }
        val json =
            """{"schemaVersion":1,"kind":"xenowave-playlist-references","name":"x","tracks":[$tracks]}"""
        assertTrue(PlaylistTransferFormat.parse(json) is PlaylistParseResult.Invalid)
    }
}
