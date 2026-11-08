package com.dd3boh.xenowave.insights.musicalage.data

import com.dd3boh.xenowave.insights.musicalage.content.SongMetadata

/**
 * Loads only the selected songs, never the whole library. Both queries are
 * bounded by the distinct IDs already present in the authorized history.
 */
class SongMetadataLoader(
    private val dao: MusicalAgeDao,
    private val chunkSize: Int = SQLITE_SAFE_CHUNK,
) {
    suspend fun load(songIds: List<String>): Map<String, SongMetadata> {
        val distinct = songIds.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return emptyMap()
        val chunks = distinct.chunked(chunkSize.coerceIn(1, SQLITE_SAFE_CHUNK))
        val rows = chunks.flatMap { dao.songMetadata(it) }
        val artists = chunks.flatMap { dao.songArtists(it) }
            .groupBy({ it.songId }, { it.artistName })
        return rows.associate { row ->
            row.songId to SongMetadata(
                songId = row.songId,
                title = row.title,
                artists = artists[row.songId].orEmpty(),
                durationMillis = row.durationSeconds
                    ?.takeIf { it > 0 }
                    ?.toLong()
                    ?.times(1_000L),
                albumTitle = row.albumName,
                localYearHint = row.localYear,
                isLocal = row.isLocal,
                localPathExtension = row.localPath
                    ?.substringAfterLast('.', "")
                    ?.takeIf { it.isNotBlank() }
                    ?.lowercase(),
            )
        }
    }

    companion object {
        /**
         * SQLite's default host parameter limit is 999. Keep a margin so the
         * list itself plus other parameters never reaches the limit.
         */
        const val SQLITE_SAFE_CHUNK = 900
    }
}

fun interface SongMetadataSource {
    suspend fun metadataFor(songIds: List<String>): Map<String, SongMetadata>
}

class RoomSongMetadataSource(
    dao: MusicalAgeDao,
) : SongMetadataSource {
    private val loader = SongMetadataLoader(dao)

    override suspend fun metadataFor(songIds: List<String>): Map<String, SongMetadata> =
        loader.load(songIds)
}
