package com.dd3boh.xenowave.insights.musicalage.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import java.time.LocalDateTime

/**
 * Minimal projection used by the musical-age diagnostic.
 *
 * The query is bounded to the period and returns only the columns needed to
 * calculate weights and year-evidence diagnostics. It does not load the whole
 * library and does not join artists, albums, genres or play counts.
 */
data class MusicalAgeListeningRow(
    @ColumnInfo(name = "eventId") val eventId: Long,
    @ColumnInfo(name = "songId") val songId: String,
    @ColumnInfo(name = "timestamp") val timestamp: LocalDateTime,
    @ColumnInfo(name = "playedMillis") val playedMillis: Long,
    @ColumnInfo(name = "durationSeconds") val durationSeconds: Int?,
    @ColumnInfo(name = "localYear") val localYear: Int?,
)

data class MusicalAgeSongRow(
    @ColumnInfo(name = "songId") val songId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "durationSeconds") val durationSeconds: Int?,
    @ColumnInfo(name = "localYear") val localYear: Int?,
    @ColumnInfo(name = "isLocal") val isLocal: Boolean,
    @ColumnInfo(name = "localPath") val localPath: String?,
    @ColumnInfo(name = "albumName") val albumName: String?,
)

data class MusicalAgeArtistRow(
    @ColumnInfo(name = "songId") val songId: String,
    @ColumnInfo(name = "artistName") val artistName: String,
)

data class MusicalAgeEligibleSongRow(
    @ColumnInfo(name = "songId") val songId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "durationSeconds") val durationSeconds: Int?,
    @ColumnInfo(name = "localYear") val localYear: Int?,
    @ColumnInfo(name = "albumName") val albumName: String?,
    @ColumnInfo(name = "eventCount") val eventCount: Int,
)

@Dao
interface MusicalAgeDao {
    @Query(
        """
        SELECT event.id AS eventId,
               event.songId AS songId,
               event.timestamp AS timestamp,
               event.playTime AS playedMillis,
               song.duration AS durationSeconds,
               song.year AS localYear
        FROM event
        LEFT JOIN song ON song.id = event.songId
        WHERE event.timestamp >= :fromInclusive
          AND event.timestamp <= :toInclusive
        ORDER BY event.id ASC
        """
    )
    suspend fun listeningRowsBetween(
        fromInclusive: LocalDateTime,
        toInclusive: LocalDateTime,
    ): List<MusicalAgeListeningRow>

    @Query(
        """
        SELECT song.id AS songId,
               song.title AS title,
               song.duration AS durationSeconds,
               song.year AS localYear,
               song.isLocal AS isLocal,
               song.localPath AS localPath,
               song.albumName AS albumName
        FROM song
        WHERE song.id IN (:songIds)
        """
    )
    suspend fun songMetadata(songIds: List<String>): List<MusicalAgeSongRow>

    @Query(
        """
        SELECT map.songId AS songId,
               artist.name AS artistName
        FROM song_artist_map map
        JOIN artist ON artist.id = map.artistId
        WHERE map.songId IN (:songIds)
        ORDER BY map.songId ASC, map.position ASC
        """
    )
    suspend fun songArtists(songIds: List<String>): List<MusicalAgeArtistRow>

    /**
     * Bounded, paginated list of songs actually present in the 90-day period.
     * It is a review aid, not a new source of listening data.
     */
    @Query(
        """
        SELECT event.songId AS songId,
               COALESCE(song.title, '') AS title,
               song.duration AS durationSeconds,
               song.year AS localYear,
               song.albumName AS albumName,
               COUNT(event.id) AS eventCount
        FROM event
        LEFT JOIN song ON song.id = event.songId
        WHERE event.timestamp >= :fromInclusive
          AND event.timestamp <= :toInclusive
        GROUP BY event.songId
        ORDER BY eventCount DESC, event.songId ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun eligibleSongsBetween(
        fromInclusive: LocalDateTime,
        toInclusive: LocalDateTime,
        limit: Int,
        offset: Int,
    ): List<MusicalAgeEligibleSongRow>

    @Query(
        """
        SELECT COUNT(DISTINCT event.songId)
        FROM event
        WHERE event.timestamp >= :fromInclusive
          AND event.timestamp <= :toInclusive
        """
    )
    suspend fun eligibleSongCountBetween(
        fromInclusive: LocalDateTime,
        toInclusive: LocalDateTime,
    ): Int

    /**
     * Distinct song ids in the period, used only to look up already cached
     * metadata evidence locally. It never performs network access.
     */
    @Query(
        """
        SELECT DISTINCT event.songId
        FROM event
        WHERE event.timestamp >= :fromInclusive
          AND event.timestamp <= :toInclusive
        ORDER BY event.songId ASC
        """
    )
    suspend fun songIdsBetween(
        fromInclusive: LocalDateTime,
        toInclusive: LocalDateTime,
    ): List<String>
}
