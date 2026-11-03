package com.dd3boh.xenowave.utils

import androidx.room.Room
import com.dd3boh.xenowave.db.InternalDatabase
import com.dd3boh.xenowave.db.MusicDatabase
import com.dd3boh.xenowave.db.entities.PlaylistEntity
import com.dd3boh.xenowave.db.entities.PlaylistSongMap
import com.dd3boh.xenowave.db.entities.PlaylistSyncState
import com.dd3boh.xenowave.models.MediaMetadata
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.pages.PlaylistPage
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class PlaylistSyncManagerTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var database: MusicDatabase
    private lateinit var manager: PlaylistSyncManager
    private lateinit var session: Session
    private lateinit var remote: Remote
    private val dbName = "playlist-sync-test.db"

    @Before
    fun setUp() {
        app.deleteDatabase(dbName)
        session = Session()
        remote = Remote()
        open()
        database.inTransaction {
            insert(PlaylistEntity(id = "local", name = "Original", browseId = "remote"))
            remote.songs.forEachIndexed { index, song ->
                insert(metadata(song.id))
                insertPlaylistMapFromSync(PlaylistSongMap(playlistId = "local", songId = song.id, position = index, setVideoId = song.setVideoId))
            }
            savePlaylistSyncState(PlaylistSyncState("local", "remote", "account-a", playlistSyncSnapshot("local").encode()))
        }
    }

    private fun open() {
        database = MusicDatabase(Room.databaseBuilder(app, InternalDatabase::class.java, dbName).allowMainThreadQueries().build())
        manager = PlaylistSyncManager(database, session, remote)
    }

    private fun restart() {
        manager.close()
        database.close()
        open()
    }

    @After
    fun tearDown() {
        manager.close()
        database.close()
        app.deleteDatabase(dbName)
    }

    @Test
    fun mergesRemoteAdditionsAndRemovesOnlyTheSelectedDuplicate() = runBlocking {
        database.inTransaction {
            removePlaylistSongs("local", setOf(playlistSongMaps("local").first().id))
            move("local", 1, 0)
            update(playlistEntityForSync("local")!!.copy(name = "Renamed"))
            insert(metadata("new"))
            insert(PlaylistSongMap(playlistId = "local", songId = "new", position = 2))
        }
        remote.songs += remote.song("external", "external-set")
        assertTrue(manager.syncPlaylist("local"))
        assertEquals(listOf("b", "a", "external", "new"), remote.songs.map { it.id })
        assertEquals("second-a", remote.songs[1].setVideoId)
        assertEquals("Renamed", remote.title)
        assertEquals(remote.songs.map { it.id }, database.playlistSongMaps("local").map { it.songId })
        assertFalse(database.playlistSyncState("local")!!.pending)
    }

    @Test
    fun recoversALostAddResponseAfterDatabaseAndManagerRestartWithoutResending() = runBlocking {
        add("new")
        remote.failAfterAdd = 1
        assertFalse(manager.syncPlaylist("local"))
        assertEquals("UNCONFIRMED", database.playlistSyncState("local")!!.error)
        restart()
        assertTrue(manager.syncPlaylist("local"))
        assertEquals(1, remote.addCalls)
        assertEquals(1, remote.songs.count { it.id == "new" })
        assertFalse(database.playlistSyncState("local")!!.pending)
    }

    @Test
    fun keepsConfirmedProgressWhenTheSecondSongFails() = runBlocking {
        add("new-one")
        add("new-two")
        remote.failAfterAdd = 2
        assertFalse(manager.syncPlaylist("local"))
        assertNotNull(database.playlistSongMaps("local").first { it.songId == "new-one" }.setVideoId)
        assertTrue(manager.syncPlaylist("local"))
        assertEquals(2, remote.addCalls)
        assertEquals(1, remote.songs.count { it.id == "new-one" })
        assertEquals(1, remote.songs.count { it.id == "new-two" })
    }

    @Test
    fun uncertainRequestsPauseUntilTheyCanBeRecoveredOrExplicitlyRetried() = runBlocking {
        add("new")
        remote.failBeforeAdd = true
        assertFalse(manager.syncPlaylist("local"))
        assertFalse(manager.syncPlaylist("local"))
        assertEquals(1, remote.addCalls)
        assertTrue(manager.syncPlaylist("local", retryUnconfirmed = true))
        assertEquals(2, remote.addCalls)
        assertEquals(1, remote.songs.count { it.id == "new" })
    }

    @Test
    fun pendingEditsAreNeitherSentNorOverwrittenInReadOnlyModeOrAnotherAccount() = runBlocking {
        add("new")
        session.writable = false
        assertFalse(manager.syncPlaylist("local"))
        assertEquals(0, remote.readCalls)
        session.writable = true
        session.currentOwner = "account-b"
        assertFalse(manager.syncPlaylist("local"))
        assertEquals(0, remote.readCalls)
        assertEquals("new", database.playlistSongMaps("local").last().songId)
        session.currentOwner = "account-a"
        assertTrue(manager.syncPlaylist("local"))
    }

    @Test
    fun switchingAccountsDuringARequestDoesNotAcknowledgeItOnTheWrongAccount() = runBlocking {
        add("new")
        remote.afterAdd = { session.currentOwner = "account-b" }
        assertFalse(manager.syncPlaylist("local"))
        assertTrue(database.playlistSyncState("local")!!.pending)
        session.currentOwner = "account-a"
        remote.afterAdd = null
        assertTrue(manager.syncPlaylist("local"))
        assertEquals(1, remote.addCalls)
    }

    @Test
    fun editsMadeDuringSynchronizationRemainPendingAndAreSentOnTheNextPass() = runBlocking {
        add("new")
        remote.songs += remote.song("external", "external-set")
        remote.afterAdd = {
            database.inTransaction { update(playlistEntityForSync("local")!!.copy(name = "Edited during request")) }
        }
        assertFalse(manager.syncPlaylist("local"))
        assertTrue(database.playlistSyncState("local")!!.pending)
        assertEquals("Edited during request", database.playlistEntityForSync("local")!!.name)
        remote.afterAdd = null
        assertTrue(manager.syncPlaylist("local"))
        assertEquals("Edited during request", remote.title)
        assertTrue(remote.songs.any { it.id == "external" })
        assertEquals(1, remote.addCalls)
    }

    @Test
    fun aFailedCompleteReadLeavesTheLocalPlaylistIntact() = runBlocking {
        val original = database.playlistSongMaps("local")
        remote.failRead = true
        assertFalse(manager.syncPlaylist("local"))
        assertEquals(original, database.playlistSongMaps("local"))
        assertEquals(0, remote.addCalls)
    }

    @Test
    fun deletingAPlaylistPersistsTheRemoteDeletionAcrossRestart() = runBlocking {
        database.inTransaction { delete(playlistEntityForSync("local")!!) }
        assertNull(database.playlistEntityForSync("local"))
        restart()
        remote.failDelete = true
        assertFalse(manager.syncPlaylist("local"))
        assertTrue(database.playlistSyncState("local")!!.pending)
        assertTrue(manager.syncPlaylist("local"))
        assertFalse(remote.exists)
        assertFalse(database.playlistSyncState("local")!!.pending)
        assertEquals(1, database.isPlaylistPendingDeletion("remote"))
    }

    @Test
    fun uploadingALocalPlaylistCreatesOneRemotePlaylistAndKeepsLocalAudioOnTheDevice() = runBlocking {
        database.inTransaction {
            updatePlaylistFromSync(playlistEntityForSync("local")!!.copy(isLocal = true))
            forgetPlaylistSyncState("local")
            insert(metadata("file", isLocal = true))
            insertPlaylistMapFromSync(PlaylistSongMap(playlistId = "local", songId = "file", position = 3))
            queuePlaylistUpload("local", "account-a")
        }
        remote.exists = false
        remote.songs.clear()
        remote.failCreateAfterApply = true
        assertFalse(manager.syncPlaylist("local"))
        restart()
        assertTrue(manager.syncPlaylist("local"))
        assertEquals(1, remote.createCalls)
        assertEquals(listOf("a", "a", "b"), remote.songs.map { it.id })
        assertEquals("file", database.playlistSongMaps("local").last().songId)
        assertTrue(database.localAudioPlaylistMaps("local").any { it.songId == "file" })
    }

    @Test
    fun addsALargeBatchWithoutRereadingTheWholePlaylistForEachSong() = runBlocking {
        repeat(40) { add("batch-$it") }
        assertTrue(manager.syncPlaylist("local"))
        assertEquals(40, remote.addCalls)
        assertTrue(remote.readCalls <= 3)
        assertEquals(43, database.playlistSongMaps("local").size)
    }

    @Test
    fun fallsBackToReadingWhenAnOlderResponseOmitsTheOccurrenceId() = runBlocking {
        add("new")
        remote.acknowledge = false
        assertTrue(manager.syncPlaylist("local"))
        assertEquals(1, remote.addCalls)
        assertNotNull(database.playlistSongMaps("local").last().setVideoId)
    }

    @Test
    fun rollingBackALocalEditAlsoRollsBackItsOutboxChange() {
        val before = database.playlistSyncState("local")
        runCatching {
            database.inTransaction {
                update(playlistEntityForSync("local")!!.copy(name = "Uncommitted"))
                error("rollback")
            }
        }
        assertEquals("Original", database.playlistEntityForSync("local")!!.name)
        assertEquals(before, database.playlistSyncState("local"))
    }

    private fun add(id: String) = database.inTransaction {
        insert(metadata(id))
        insert(PlaylistSongMap(playlistId = "local", songId = id, position = nextPlaylistPosition("local")))
    }

    private fun metadata(id: String, isLocal: Boolean = false) = MediaMetadata(id, id, emptyList(), 60, genre = null, isLocal = isLocal)

    private class Session : PlaylistSyncSession {
        var currentOwner = "account-a"
        var writable = true
        override val changes = emptyFlow<Unit>() // Tests explicitly advance the synchronizer.
        override fun owner() = currentOwner
        override fun canWrite(owner: String) = writable && currentOwner == owner
    }

    private class Remote : PlaylistSyncRemote {
        var title = "Original"
        var exists = true
        var readCalls = 0
        var addCalls = 0
        var createCalls = 0
        var failAfterAdd = -1
        var failBeforeAdd = false
        var failCreateAfterApply = false
        var failDelete = false
        var failRead = false
        var afterAdd: (() -> Unit)? = null
        var acknowledge = true
        val songs = mutableListOf(song("a", "first-a"), song("a", "second-a"), song("b", "b-set"))
        fun song(id: String, setId: String) = SongItem(id = id, title = id, artists = emptyList(), duration = 60, thumbnail = "https://example.com/art.jpg", setVideoId = setId)
        fun item() = PlaylistItem("remote", title, null, songs.size.toString(), null, null, null, null, isEditable = true)
        override suspend fun read(id: String): PlaylistPage {
            readCalls++
            if (failRead) throw IOException("Continuation unavailable")
            check(exists)
            return PlaylistPage(item(), songs.toList(), null, null)
        }
        override suspend fun library() = if (exists) listOf(item()) else emptyList()
        override suspend fun create(title: String): String {
            createCalls++
            this.title = title
            exists = true
            if (failCreateAfterApply) { failCreateAfterApply = false; throw IOException("Lost create response") }
            return "remote"
        }
        override suspend fun add(id: String, videoId: String): String? {
            addCalls++
            if (failBeforeAdd) { failBeforeAdd = false; throw IOException("Lost connection") }
            songs += song(videoId, "added-$addCalls")
            afterAdd?.invoke()
            if (failAfterAdd == addCalls) throw IOException("Lost add response")
            return if (acknowledge) "added-$addCalls" else null
        }
        override suspend fun remove(id: String, videoId: String, setId: String) { songs.removeAll { it.setVideoId == setId } }
        override suspend fun move(id: String, setId: String, successor: String) {
            val song = songs.first { it.setVideoId == setId }
            songs.remove(song)
            songs.add(songs.indexOfFirst { it.setVideoId == successor }, song)
        }
        override suspend fun rename(id: String, title: String) { this.title = title }
        override suspend fun delete(id: String) {
            if (failDelete) { failDelete = false; throw IOException("Server unavailable") }
            exists = false
        }
    }
}
