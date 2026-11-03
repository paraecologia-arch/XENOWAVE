package com.dd3boh.xenowave.utils

import android.content.Context
import com.dd3boh.xenowave.constants.AccountChannelHandleKey
import com.dd3boh.xenowave.constants.AccountEmailKey
import com.dd3boh.xenowave.constants.DataSyncIdKey
import com.dd3boh.xenowave.db.MusicDatabase
import com.dd3boh.xenowave.db.entities.PlaylistSongMap
import com.dd3boh.xenowave.db.entities.PlaylistSyncState
import com.dd3boh.xenowave.models.toMediaMetadata
import com.zionhuang.innertube.pages.PlaylistPage
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Stable account/channel identity; cookies and tokens are never saved in the outbox. */
fun Context.playlistSyncOwner(): String? {
    val prefs = dataStore.get(DataSyncIdKey).orEmpty()
    val fallback = listOf(dataStore.get(AccountEmailKey).orEmpty(), dataStore.get(AccountChannelHandleKey).orEmpty())
    val identity = prefs.ifBlank { fallback.filter { it.isNotBlank() }.joinToString("|") }
    if (identity.isBlank()) return null
    return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
}

private class UnconfirmedPlaylistWrite : Exception()

@Singleton
class PlaylistSyncManager @Inject constructor(
    private val database: MusicDatabase,
    private val session: PlaylistSyncSession,
    private val remote: PlaylistSyncRemote,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    internal fun close() = scope.cancel()

    init {
        scope.launch {
            combine(database.pendingPlaylistSyncStates().distinctUntilChanged(), session.changes) { _, _ -> Unit }.collect { flush() }
        }
        scope.launch {
            while (isActive) {
                delay(30_000)
                flush()
            }
        }
    }

    private fun checkAccount(owner: String, write: Boolean = false) {
        check(if (write) session.canWrite(owner) else session.owner() == owner) { "Playlist account changed or sync disabled" }
    }

    suspend fun flush() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val owner = session.owner() ?: return@withLock
            if (!session.canWrite(owner)) return@withLock
            database.pendingPlaylistSyncStates().first().forEach { state ->
                if (state.ownerId == null || state.ownerId == owner) synchronize(state.playlistId, owner)
            }
        }
    }

    suspend fun syncPlaylist(playlistId: String, retryUnconfirmed: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val owner = session.owner() ?: return@withLock false
            synchronize(playlistId, owner, retryUnconfirmed)
        }
    }

    private suspend fun read(browseId: String, owner: String): PlaylistPage {
        checkAccount(owner)
        // completed() fails the entire read if any continuation fails. Never apply a partial page.
        return remote.read(browseId.removePrefix("VL")).also { checkAccount(owner) }
    }

    private fun entries(page: PlaylistPage) = page.songs.mapIndexed { index, song ->
        PlaylistSyncEntry(index, song.id, song.setVideoId)
    }

    private suspend fun edit(id: String, owner: String, browseId: String, deleting: Boolean = false, call: suspend () -> Unit) {
        checkAccount(owner, write = true)
        val current = database.playlistSyncState(id)
        check(current?.pending == true && current.deleted == deleting && current.browseId == browseId && current.ownerId == owner)
        call()
        checkAccount(owner)
    }

    private fun changeState(id: String, transform: (PlaylistSyncState) -> PlaylistSyncState) = database.inTransaction {
        playlistSyncState(id)?.let { savePlaylistSyncState(transform(it)) }
    }

    private suspend fun synchronize(id: String, owner: String, retryUnconfirmed: Boolean = false): Boolean {
        var state = database.playlistSyncState(id)
        if (state?.ownerId != null && state.ownerId != owner) return false
        try {
            val local = database.playlistEntityForSync(id)
            if (local?.isLocal == true) {
                database.forgetPlaylistSyncState(id)
                return true
            }
            if (state?.pending == true && !session.canWrite(owner)) return false

            // Creating a synced playlist is also queued. A timed-out create is recovered from the
            // account library before retrying, rather than blindly creating a duplicate.
            if (state?.pending == true && state.browseId == null) {
                if (state.deleted && state.inFlightAdd == null) {
                    database.forgetPlaylistSyncState(id)
                    return true
                }
                checkAccount(owner, write = true)
                val attempt = state.inFlightAdd?.let(PlaylistAddAttempt::decode)
                val title = local?.name ?: attempt?.videoId ?: return false
                val library = remote.library()
                checkAccount(owner, write = true)
                var browseId = attempt?.let { saved ->
                    library.filter { it.isEditable && it.title == saved.videoId && it.id !in saved.before }.singleOrNull()?.id
                }
                if (attempt != null && browseId == null && !retryUnconfirmed) throw UnconfirmedPlaylistWrite()
                if (browseId == null) {
                    if (state.deleted) throw UnconfirmedPlaylistWrite()
                    changeState(id) { it.copy(inFlightAdd = PlaylistAddAttempt(-1, title, library.map { p -> p.id }.toSet()).encode()) }
                    checkAccount(owner, write = true)
                    check(database.playlistSyncState(id)?.let { it.pending && !it.deleted } == true)
                    try {
                        browseId = remote.create(title)
                    } catch (e: ClientRequestException) {
                        changeState(id) { it.copy(inFlightAdd = null) }
                        throw e
                    }
                    checkAccount(owner)
                }
                val remoteId = requireNotNull(browseId)
                database.inTransaction {
                    playlistEntityForSync(id)?.let { updatePlaylistFromSync(it.copy(browseId = remoteId)) }
                    playlistSyncState(id)?.let { savePlaylistSyncState(it.copy(browseId = remoteId, ownerId = owner, inFlightAdd = null)) }
                }
                state = database.playlistSyncState(id)
            }

            val browseId = state?.browseId ?: local?.browseId ?: return false
            if (state?.deleted == true) {
                checkAccount(owner, write = true)
                try {
                    if (state.ownerId == null) {
                        check(read(browseId, owner).playlist.isEditable)
                        changeState(id) { it.copy(ownerId = owner) }
                    }
                    edit(id, owner, browseId, deleting = true) { remote.delete(browseId.removePrefix("VL")) }
                } catch (e: ClientRequestException) {
                    if (e.response.status != HttpStatusCode.NotFound) throw e
                }
                changeState(id) { it.copy(pending = false, ownerId = owner, error = null) }
                return true
            }

            var page = read(browseId, owner)
            if (state?.pending == true) {
                check(page.playlist.isEditable) { "Playlist is not editable by this account" }
                changeState(id) { it.copy(ownerId = owner) }
                val initialRevision = state.revision

                // Recover an add whose HTTP result was lost (including process death after the POST).
                state.inFlightAdd?.let { encoded ->
                    val attempt = PlaylistAddAttempt.decode(encoded)
                    val recovered = PlaylistSyncPlan.recoveredAddition(attempt.before, entries(page), attempt.videoId)
                    if (recovered != null) acknowledgeAdd(id, attempt, recovered)
                    else if (retryUnconfirmed) changeState(id) { it.copy(inFlightAdd = null) }
                    else throw UnconfirmedPlaylistWrite()
                }

                // Take a fresh desired snapshot after recovery; never operate on stale list indices.
                val desired = database.playlistSyncSnapshot(id)
                val baseline = PlaylistSyncSnapshot.decode(database.playlistSyncState(id)!!.baseline)
                val removals = PlaylistSyncPlan.removals(baseline, desired.entries)
                entries(page).filter { it.setVideoId in removals }.forEach { entry ->
                    edit(id, owner, browseId) { remote.remove(browseId, entry.videoId, entry.setVideoId!!) }
                }

                for (entry in desired.entries.filter { it.setVideoId == null }) {
                    val song = database.song(entry.videoId).first() ?: continue
                    // Local audio is preserved locally; uploading files to YouTube Music is unsupported.
                    if (song.song.isLocal) continue
                    page = read(browseId, owner)
                    val attempt = PlaylistAddAttempt(entry.mapId, entry.videoId, entries(page).mapNotNull { it.setVideoId }.toSet())
                    changeState(id) { it.copy(inFlightAdd = attempt.encode()) }
                    try {
                        edit(id, owner, browseId) { remote.add(browseId, entry.videoId) }
                    } catch (e: Exception) {
                        if (e is ClientRequestException || e is PlaylistWriteRejected) changeState(id) { it.copy(inFlightAdd = null) }
                        throw e
                    }
                    page = read(browseId, owner)
                    val setId = PlaylistSyncPlan.recoveredAddition(attempt.before, entries(page), entry.videoId)
                        ?: throw UnconfirmedPlaylistWrite()
                    acknowledgeAdd(id, attempt, setId)
                }

                page = read(browseId, owner)
                val withIds = database.playlistSyncSnapshot(id)
                val remoteIds = entries(page).map { requireNotNull(it.setVideoId) }
                val target = PlaylistSyncPlan.order(remoteIds, withIds.entries.mapNotNull { it.setVideoId }, state.orderPending)
                for ((item, successor) in PlaylistSyncPlan.moves(remoteIds, target)) {
                    edit(id, owner, browseId) { remote.move(browseId.removePrefix("VL"), item, successor) }
                }
                val rename = desired.name != baseline.name
                if (rename) edit(id, owner, browseId) { remote.rename(browseId.removePrefix("VL"), desired.name) }
                page = read(browseId, owner)
                check(entries(page).none { it.setVideoId in removals })
                if (rename) check(page.playlist.title == desired.name)
                if (state.orderPending) {
                    val wanted = withIds.entries.mapNotNull { it.setVideoId }.filter { it in entries(page).map { r -> r.setVideoId } }
                    check(entries(page).mapNotNull { it.setVideoId }.filter { it in wanted } == wanted)
                }
                checkAccount(owner)
                val acknowledged = PlaylistSyncSnapshot(page.playlist.title, entries(page))
                val applied = database.inTransaction {
                    val latest = playlistSyncState(id) ?: return@inTransaction false
                    if (latest.revision != initialRevision || latest.deleted) {
                        // Another local edit occurred during the requests. Keep it pending, but move
                        // the baseline forward so a newly added song can subsequently be removed.
                        val known = PlaylistSyncSnapshot.decode(latest.baseline).entries.mapNotNull { it.setVideoId }.toSet()
                        val baseline = acknowledged.copy(entries = acknowledged.entries.filter { it.setVideoId in known })
                        savePlaylistSyncState(latest.copy(baseline = baseline.encode(), error = null))
                        false
                    } else {
                        applyRemote(id, page, owner)
                        true
                    }
                }
                return applied
            }
            checkAccount(owner)
            database.inTransaction {
                if (playlistSyncState(id)?.pending != true) applyRemote(id, page, owner)
            }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            changeState(id) { it.copy(error = if (e is UnconfirmedPlaylistWrite || it.inFlightAdd != null) "UNCONFIRMED" else "FAILED") }
            return false
        }
    }

    private fun acknowledgeAdd(id: String, attempt: PlaylistAddAttempt, setId: String) = database.inTransaction {
        val current = playlistSyncState(id) ?: return@inTransaction
        if (current.inFlightAdd != attempt.encode()) return@inTransaction
        playlistSongMaps(id).firstOrNull { it.id == attempt.mapId }?.let {
            updatePlaylistMapFromSync(it.copy(setVideoId = setId))
        }
        playlistSyncState(id)?.let { state ->
            val baseline = PlaylistSyncSnapshot.decode(state.baseline)
            val entry = PlaylistSyncEntry(attempt.mapId, attempt.videoId, setId)
            savePlaylistSyncState(state.copy(
                baseline = baseline.copy(entries = baseline.entries.filterNot { it.setVideoId == setId } + entry).encode(),
                inFlightAdd = null,
            ))
        }
    }

    /** Only invoked inside the same Room transaction that clears the pending flag. */
    private fun MusicDatabase.applyRemote(id: String, page: PlaylistPage, owner: String) {
        val local = playlistEntityForSync(id) ?: return
        if (local.isLocal) return
        // Synced playlists normally contain online songs only. Do not drop local file entries.
        val localIds = localAudioPlaylistMaps(id)
        clearPlaylistFromSync(id)
        page.songs.forEachIndexed { position, song ->
            insert(song.toMediaMetadata())
            insertPlaylistMapFromSync(PlaylistSongMap(playlistId = id, songId = song.id, position = position, setVideoId = song.setVideoId))
        }
        localIds.forEachIndexed { index, map -> insertPlaylistMapFromSync(map.copy(id = 0, position = page.songs.size + index)) }
        updatePlaylistFromSync(local.copy(name = page.playlist.title, remoteSongCount = page.songs.size, isEditable = page.playlist.isEditable))
        savePlaylistSyncState(PlaylistSyncState(id, local.browseId, owner, playlistSyncSnapshot(id).encode()))
    }
}
