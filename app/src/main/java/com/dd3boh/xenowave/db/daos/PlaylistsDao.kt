package com.dd3boh.xenowave.db.daos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.dd3boh.xenowave.constants.PlaylistFilter
import com.dd3boh.xenowave.constants.PlaylistSortType
import com.dd3boh.xenowave.db.entities.Playlist
import com.dd3boh.xenowave.db.entities.PlaylistEntity
import com.dd3boh.xenowave.db.entities.PlaylistSong
import com.dd3boh.xenowave.db.entities.PlaylistSongMap
import com.dd3boh.xenowave.db.entities.PlaylistSyncState
import com.dd3boh.xenowave.utils.PlaylistSyncEntry
import com.dd3boh.xenowave.utils.PlaylistSyncSnapshot
import com.dd3boh.xenowave.extensions.reversed
import com.zionhuang.innertube.models.PlaylistItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map


/*
 * Logic related to playlists entities and their mapping
 */

@Dao
interface PlaylistsDao {

    @Query("SELECT * FROM playlist WHERE id = :id")
    fun playlistEntityForSync(id: String): PlaylistEntity?

    @Query("SELECT * FROM playlist_sync_state WHERE playlistId = :id")
    fun playlistSyncState(id: String): PlaylistSyncState?

    @Query("SELECT * FROM playlist_sync_state WHERE playlistId = :id")
    fun playlistSyncStateFlow(id: String): Flow<PlaylistSyncState?>

    @Query("SELECT * FROM playlist_sync_state WHERE pending = 1 ORDER BY playlistId")
    fun pendingPlaylistSyncStates(): Flow<List<PlaylistSyncState>>

    @Query("SELECT COUNT(*) FROM playlist_sync_state WHERE browseId = :browseId AND deleted = 1")
    fun isPlaylistPendingDeletion(browseId: String): Int

    @Upsert
    fun savePlaylistSyncState(state: PlaylistSyncState)

    @Query("DELETE FROM playlist_sync_state WHERE playlistId = :id")
    fun forgetPlaylistSyncState(id: String)

    fun playlistSyncSnapshot(id: String): PlaylistSyncSnapshot = PlaylistSyncSnapshot(
        playlistEntityForSync(id)?.name.orEmpty(),
        playlistSongMaps(id).map { PlaylistSyncEntry(it.id, it.songId, it.setVideoId) },
    )

    fun markPlaylistPending(id: String, orderChanged: Boolean = false, deleted: Boolean = false) {
        val playlist = playlistEntityForSync(id) ?: return
        val old = playlistSyncState(id)
        if (playlist.isLocal || !playlist.isEditable || (playlist.browseId == null && old == null)) return
        val state = old ?: PlaylistSyncState(id, playlist.browseId, null, playlistSyncSnapshot(id).encode())
        savePlaylistSyncState(state.copy(
            pending = true, deleted = deleted, revision = state.revision + 1,
            orderPending = state.orderPending || orderChanged, error = null,
        ))
    }

    @Transaction
    fun createSyncedPlaylist(playlist: PlaylistEntity, ownerId: String) {
        insert(playlist)
        savePlaylistSyncState(PlaylistSyncState(
            playlist.id, null, ownerId, PlaylistSyncSnapshot(playlist.name, emptyList()).encode(), pending = true,
        ))
    }

    @Transaction
    fun queuePlaylistUpload(id: String, ownerId: String) {
        val playlist = playlistEntityForSync(id) ?: return
        if (!playlist.isLocal || !playlist.isEditable) return
        updatePlaylistFromSync(playlist.copy(isLocal = false, browseId = null))
        playlistSongMaps(id).forEach { updatePlaylistMapFromSync(it.copy(setVideoId = null)) }
        savePlaylistSyncState(PlaylistSyncState(
            id, null, ownerId, PlaylistSyncSnapshot(playlist.name, emptyList()).encode(), pending = true,
        ))
    }

    // region Gets
    @Transaction
    @Query("""
        SELECT 
            p.*, 
            COUNT(psm.playlistId) AS songCount,
            SUM(CASE WHEN s.dateDownload IS NOT NULL THEN 1 ELSE 0 END) AS downloadCount
        FROM playlist p
            LEFT JOIN playlist_song_map psm ON p.id = psm.playlistId
            LEFT JOIN song s ON psm.songId = s.id
        WHERE p.id = :playlistId
        GROUP BY p.id
    """)
    fun playlist(playlistId: String): Flow<Playlist?>

    @Transaction
    @Query("""
        SELECT 
            p.*, 
            COUNT(psm.playlistId) AS songCount,
            SUM(CASE WHEN s.dateDownload IS NOT NULL THEN 1 ELSE 0 END) AS downloadCount
        FROM playlist p
            LEFT JOIN playlist_song_map psm ON p.id = psm.playlistId
            LEFT JOIN song s ON psm.songId = s.id
        WHERE p.browseId = :browseId
        GROUP BY p.id
    """)
    fun playlistByBrowseId(browseId: String): Flow<Playlist?>

    @Transaction
    @Query("""
        SELECT 
            p.*, 
            COUNT(psm.playlistId) AS songCount,
            SUM(CASE WHEN s.dateDownload IS NOT NULL THEN 1 ELSE 0 END) AS downloadCount
        FROM playlist p
            LEFT JOIN playlist_song_map psm ON p.id = psm.playlistId
            LEFT JOIN song s ON psm.songId = s.id
        WHERE name LIKE '%' || :query || '%'
            AND s.inLibrary IS NOT NULL
        GROUP BY p.id
        LIMIT :previewSize
    """)
    fun searchPlaylists(query: String, previewSize: Int = Int.MAX_VALUE): Flow<List<Playlist>>

    @Transaction
    @Query("SELECT * FROM playlist_song_map WHERE playlistId = :playlistId ORDER BY position, id")
    fun playlistSongs(playlistId: String): Flow<List<PlaylistSong>>

    @Query("SELECT songId from playlist_song_map WHERE playlistId = :playlistId AND songId IN (:songIds)")
    fun playlistDuplicates(playlistId: String, songIds: List<String>,): List<String>

    @Query("SELECT COALESCE(MAX(position) + 1, 0) FROM playlist_song_map WHERE playlistId = :playlistId")
    fun nextPlaylistPosition(playlistId: String): Int

    @Query("SELECT * FROM playlist_song_map WHERE songId = :songId")
    fun songMapsToPlaylist(songId: String): List<PlaylistSongMap>

    @Query("SELECT * FROM playlist_song_map WHERE playlistId = :playlistId ORDER BY position, id")
    fun playlistSongMaps(playlistId: String): List<PlaylistSongMap>

    @Query("SELECT m.* FROM playlist_song_map m JOIN song s ON m.songId = s.id WHERE m.playlistId = :playlistId AND s.isLocal = 1 ORDER BY m.position, m.id")
    fun localAudioPlaylistMaps(playlistId: String): List<PlaylistSongMap>

    @Query("SELECT * FROM playlist_song_map WHERE playlistId = :playlistId AND position >= :from ORDER BY position, id")
    fun songMapsToPlaylist(playlistId: String, from: Int): List<PlaylistSongMap>

    @Query("""
        SELECT DISTINCT playlistId
        FROM playlist_song_map
        WHERE songId IN (:songs)
    """)
    fun playlistIdBySongs(songs: List<String>): Flow<List<String>>

    @RawQuery(observedEntities = [PlaylistEntity::class])
    fun _getPlaylists(query: SupportSQLiteQuery): Flow<List<Playlist>>

    // TODO: do i even want an enum for this
    /**
     * Variant
     *
     * 0 -> Use this one (WHERE p.bookmarkedAt IS NOT NULL OR p.isLocal = 1)
     * 1 -> local playlists (WHERE p.isLocal AND p.bookmarkedAt IS NOT NULL)
     * 2 -> editable playlists (WHERE p.isEditable AND p.bookmarkedAt IS NOT NULL)
     */
    fun playlists(filter: PlaylistFilter, sortType: PlaylistSortType, descending: Boolean, variant: Int = 0): Flow<List<Playlist>> {
        val orderBy = when (sortType) {
            PlaylistSortType.CREATE_DATE -> "p.rowId ASC"
            PlaylistSortType.NAME -> "p.name COLLATE NOCASE ASC"
            PlaylistSortType.SONG_COUNT -> "songCount ASC"
        }

        val having = when (filter) {
            PlaylistFilter.DOWNLOADED -> "HAVING SUM(CASE WHEN s.dateDownload IS NOT NULL THEN 1 ELSE 0 END) > 0"
            else -> ""
        }

        val where = when (variant) {
            1 -> "WHERE p.isLocal AND p.bookmarkedAt IS NOT NULL"
            2 -> "WHERE p.isEditable AND p.bookmarkedAt IS NOT NULL"
            else -> "WHERE p.bookmarkedAt IS NOT NULL OR p.isLocal = 1"
        }

        val query = SimpleSQLiteQuery("""
            SELECT 
                p.*, 
                COUNT(psm.playlistId) AS songCount,
                SUM(CASE WHEN s.dateDownload IS NOT NULL THEN 1 ELSE 0 END) AS downloadCount
            FROM playlist p
                LEFT JOIN playlist_song_map psm ON p.id = psm.playlistId
                LEFT JOIN song s ON psm.songId = s.id
            $where
            GROUP BY p.id
            $having
            ORDER BY $orderBy
        """)

        return _getPlaylists(query).map{ it.reversed(descending) }
    }

    fun playlistInLibraryAsc() = playlists(PlaylistFilter.LIBRARY, PlaylistSortType.CREATE_DATE, false)
    // endregion

    // region Inserts
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(playlist: PlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertPlaylistMapFromSync(map: PlaylistSongMap): Long

    @Transaction
    fun insert(map: PlaylistSongMap): Long {
        markPlaylistPending(map.playlistId)
        return insertPlaylistMapFromSync(map)
    }
    // endregion

    // region Updates
    @Update
    fun updatePlaylistFromSync(playlist: PlaylistEntity)

    @Transaction
    fun update(playlist: PlaylistEntity) {
        val previous = playlistEntityForSync(playlist.id)
        if (previous != null && previous.name != playlist.name) markPlaylistPending(playlist.id)
        updatePlaylistFromSync(playlist.copy(browseId = playlist.browseId ?: previous?.browseId))
        if (playlist.isLocal) forgetPlaylistSyncState(playlist.id)
    }

    @Update
    fun updatePlaylistMapFromSync(map: PlaylistSongMap)

    @Transaction
    fun update(map: PlaylistSongMap) {
        val previous = playlistSongMaps(map.playlistId).firstOrNull { it.id == map.id }
        if (previous?.position != map.position) markPlaylistPending(map.playlistId, orderChanged = true)
        updatePlaylistMapFromSync(map)
    }

    @Transaction
    fun update(playlistEntity: PlaylistEntity, playlistItem: PlaylistItem) {
        updatePlaylistFromSync(playlistEntity.copy(
            name = if (playlistSyncState(playlistEntity.id)?.pending == true) playlistEntity.name else playlistItem.title,
            browseId = playlistItem.id,
            isEditable = playlistItem.isEditable,
            thumbnailUrl = playlistItem.thumbnail,
            remoteSongCount = playlistItem.songCountText?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() },
            playEndpointParams = playlistItem.playEndpoint?.params,
            shuffleEndpointParams = playlistItem.shuffleEndpoint?.params,
            radioEndpointParams = playlistItem.radioEndpoint?.params
        ))
    }

    @Transaction
    fun addSongToPlaylist(playlist: Playlist, songIds: List<String>) {
        // The caller deliberately decides whether duplicate entries are allowed. In particular,
        // AddToPlaylistDialog's "add anyway" action must remain able to add an existing song.
        // MAX(position) rather than songCount keeps appends ordered if old data contains gaps.
        var position = nextPlaylistPosition(playlist.id)
        songIds.forEach { id ->
            insert(
                PlaylistSongMap(
                    songId = id,
                    playlistId = playlist.id,
                    position = position++
                )
            )
        }
    }

    @Transaction
    fun removePlaylistSongs(playlistId: String, mapIds: Set<Int>) {
        if (mapIds.isEmpty()) return

        val maps = playlistSongMaps(playlistId)
        val remaining = maps.filterNot { it.id in mapIds }
        if (remaining.size == maps.size) return

        markPlaylistPending(playlistId)
        deletePlaylistSongMaps(playlistId, mapIds.toList())
        remaining.forEachIndexed { index, map ->
            if (map.position != index) {
                updatePlaylistMapFromSync(map.copy(position = index))
            }
        }
    }

    @Transaction
    fun removeDuplicateOccurrence(playlistId: String, mapId: Int): Boolean {
        val maps = playlistSongMaps(playlistId)
        val target = maps.firstOrNull { it.id == mapId } ?: return false
        if (maps.count { it.songId == target.songId } <= 1) return false

        delete(target)
        maps.filterNot { it.id == mapId }.forEachIndexed { index, map ->
            if (map.position != index) {
                updatePlaylistMapFromSync(map.copy(position = index))
            }
        }
        return true
    }

    @Query("UPDATE playlist SET isLocal = 1 WHERE id = :playlistId")
    fun markPlaylistLocal(playlistId: String)

    @Transaction
    fun playlistDesync(playlistId: String) {
        markPlaylistLocal(playlistId)
        forgetPlaylistSyncState(playlistId)
    }

    @Transaction
    @Query(
        """
        UPDATE playlist_song_map SET position = 
            CASE 
                WHEN position < :fromPosition THEN position + 1
                WHEN position > :fromPosition THEN position - 1
                ELSE :toPosition
            END 
        WHERE playlistId = :playlistId AND position BETWEEN MIN(:fromPosition, :toPosition) AND MAX(:fromPosition, :toPosition)
    """
    )
    fun movePlaylistMapPositions(playlistId: String, fromPosition: Int, toPosition: Int)

    @Transaction
    fun move(playlistId: String, fromPosition: Int, toPosition: Int) {
        if (fromPosition == toPosition) return
        markPlaylistPending(playlistId, orderChanged = true)
        movePlaylistMapPositions(playlistId, fromPosition, toPosition)
    }
    // endregion

    // region Deletes
    @Delete
    fun deletePlaylistFromSync(playlist: PlaylistEntity)

    @Transaction
    fun delete(playlist: PlaylistEntity) {
        markPlaylistPending(playlist.id, deleted = true)
        deletePlaylistFromSync(playlist)
    }

    @Query("DELETE FROM playlist WHERE browseId = :browseId")
    fun deletePlaylistById(browseId: String)

    @Query("DELETE FROM playlist_song_map WHERE playlistId = :playlistId")
    fun clearPlaylistFromSync(playlistId: String)

    @Transaction
    fun clearPlaylist(playlistId: String) {
        markPlaylistPending(playlistId)
        clearPlaylistFromSync(playlistId)
    }

    @Query("DELETE FROM playlist_song_map WHERE playlistId = :playlistId AND id IN (:ids)")
    fun deletePlaylistSongMaps(playlistId: String, ids: List<Int>)

    @Delete
    fun deletePlaylistMapFromSync(playlistSongMap: PlaylistSongMap)

    @Transaction
    fun delete(playlistSongMap: PlaylistSongMap) {
        markPlaylistPending(playlistSongMap.playlistId)
        deletePlaylistMapFromSync(playlistSongMap)
    }
    // endregion
}
