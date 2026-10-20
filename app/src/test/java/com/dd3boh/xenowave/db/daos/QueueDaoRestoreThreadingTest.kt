package com.dd3boh.xenowave.db.daos

import android.os.Looper
import androidx.room.Room
import com.dd3boh.xenowave.db.DatabaseDao
import com.dd3boh.xenowave.db.InternalDatabase
import com.dd3boh.xenowave.db.entities.QueueEntity
import com.dd3boh.xenowave.db.entities.QueueSongMap
import com.dd3boh.xenowave.models.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Regression coverage for the startup crash where the persisted queue was restored from a Main
 * coroutine (PlayerConnection.viewModelScope / MusicService.initQueue).
 *
 * The database is intentionally built without allowMainThreadQueries(), so Room keeps its
 * main-thread protection enabled exactly like production. The Robolectric test thread is the main
 * looper thread, so the suspend DAO functions resume on Main after their initial Flow reads and
 * reproduce the production crash when a blocking Room call leaks into the restore path.
 */
@RunWith(RobolectricTestRunner::class)
class QueueDaoRestoreThreadingTest {
    private lateinit var database: InternalDatabase
    private lateinit var dao: DatabaseDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            InternalDatabase::class.java,
        ).build()
        dao = database.dao
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun emptyPersistedQueueRestoresFromMainThread() = runBlocking {
        assertMainThread()

        assertNull(dao.getResumptionQueue())
        assertTrue(dao.readQueue().isEmpty())
    }

    @Test
    fun resumptionQueueRepairsAndPersistsInvalidStateFromMainThread() = runBlocking {
        assertMainThread()
        withContext(Dispatchers.IO) {
            insertSongs("one", "two", "three")
            dao.insert(
                QueueEntity(
                    id = 1L,
                    title = "queue",
                    shuffled = true,
                    queuePos = 99,
                    lastSongPos = -4L,
                    index = 0,
                    playlistId = "playlist",
                )
            )
            listOf("one", "two", "three").forEachIndexed { index, id ->
                dao.insert(
                    QueueSongMap(
                        queueId = 1L,
                        songId = id,
                        index = listOf(2L, 2L, 4L)[index],
                        shuffledIndex = 2L,
                    )
                )
            }
        }

        val restored = dao.getResumptionQueue()

        assertNotNull(restored)
        restored!!
        assertEquals(0, restored.queuePos)
        assertEquals(0L, restored.lastSongPos)
        assertEquals("playlist", restored.playlistId)
        assertEquals(listOf("one", "two", "three"), restored.queue.map { it.id })
        assertEquals(listOf(2, 0, 1), restored.queue.map { it.shuffleIndex })

        val persistedQueue = dao.getAllQueues().first().single()
        val persistedSongs = dao.getQueueSongs(1L).first()
        assertEquals(0, persistedQueue.queuePos)
        assertEquals(0L, persistedQueue.lastSongPos)
        assertEquals(listOf(0L, 1L, 2L), persistedSongs.map { it.queueIndex })
        assertEquals(listOf(2, 0, 1), persistedSongs.map { it.shuffledIndex })
    }

    @Test
    fun readQueueRestoresValidQueueFromMainThread() = runBlocking {
        assertMainThread()
        withContext(Dispatchers.IO) {
            insertSongs("one", "two", "three")
            dao.insert(
                QueueEntity(
                    id = 7L,
                    title = "valid",
                    shuffled = true,
                    queuePos = 2,
                    lastSongPos = 42_000L,
                    index = 0,
                    playlistId = "playlist",
                )
            )
            listOf("one", "two", "three").forEachIndexed { index, id ->
                dao.insert(
                    QueueSongMap(
                        queueId = 7L,
                        songId = id,
                        index = index.toLong(),
                        shuffledIndex = listOf(2L, 0L, 1L)[index],
                    )
                )
            }
        }

        val restored = dao.readQueue().single()

        assertEquals(7L, restored.id)
        assertEquals(listOf("one", "two", "three"), restored.queue.map { it.id })
        assertEquals(listOf(2, 0, 1), restored.queue.map { it.shuffleIndex })
        assertEquals(listOf("two", "three", "one"), restored.getCurrentQueueShuffled().map { it.id })
        assertEquals("three", restored.getCurrentSong()?.id)
        assertEquals(2, restored.queuePos)
        assertEquals(42_000L, restored.lastSongPos)
        assertEquals("playlist", restored.playlistId)
    }

    private fun assertMainThread() {
        assertTrue(
            "Restore must be exercised from the main thread to mirror production",
            Looper.myLooper() == Looper.getMainLooper(),
        )
    }

    private fun insertSongs(vararg ids: String) {
        ids.forEach { id ->
            dao.insert(
                MediaMetadata(
                    id = id,
                    title = id,
                    artists = emptyList(),
                    duration = 60,
                    genre = null,
                )
            )
        }
    }
}
