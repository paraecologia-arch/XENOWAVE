package com.dd3boh.xenowave.db.daos

import androidx.room.Room
import com.dd3boh.xenowave.db.DatabaseDao
import com.dd3boh.xenowave.db.InternalDatabase
import com.dd3boh.xenowave.db.entities.QueueEntity
import com.dd3boh.xenowave.db.entities.QueueSongMap
import com.dd3boh.xenowave.models.MediaMetadata
import com.dd3boh.xenowave.models.MultiQueueObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class QueueDaoTest {
    private lateinit var database: InternalDatabase
    private lateinit var dao: DatabaseDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            InternalDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.dao
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun readQueueRepairsAndPersistsInvalidShuffleAndPlaybackState() = runBlocking {
        insertSongs("one", "two", "three")
        dao.insert(
            QueueEntity(
                id = 1L,
                title = "queue",
                shuffled = true,
                queuePos = 99,
                lastSongPos = -4L,
                index = 0,
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

        val restored = dao.readQueue().single()
        val persistedQueue = dao.getAllQueues().first().single()
        val persistedSongs = dao.getQueueSongs(1L).first()

        assertEquals(0, restored.queuePos)
        assertEquals(0L, restored.lastSongPos)
        assertEquals(listOf(2, 0, 1), restored.queue.map { it.shuffleIndex })
        assertEquals(0, persistedQueue.queuePos)
        assertEquals(0L, persistedQueue.lastSongPos)
        assertEquals(listOf(2, 0, 1), persistedSongs.map { it.shuffledIndex })
        assertEquals(listOf(0L, 1L, 2L), persistedSongs.map { it.queueIndex })
    }

    @Test
    fun saveAndReadRoundTripsOrderCurrentSongAndPausedPosition() = runBlocking {
        insertSongs("one", "two", "three")
        val queue = MultiQueueObject(
            id = 42L,
            title = "round-trip",
            queue = mutableListOf(
                metadata("one", shuffleIndex = 2),
                metadata("two", shuffleIndex = 0),
                metadata("three", shuffleIndex = 1),
            ),
            shuffled = true,
            queuePos = 2,
            lastSongPos = 42_000L,
            index = 0,
        )

        dao.saveQueue(queue)
        val restored = dao.readQueue().single()

        assertEquals(listOf("one", "two", "three"), restored.queue.map { it.id })
        assertEquals(listOf(2, 0, 1), restored.queue.map { it.shuffleIndex })
        assertEquals(listOf("two", "three", "one"), restored.getCurrentQueueShuffled().map { it.id })
        assertEquals("three", restored.getCurrentSong()?.id)
        assertEquals(2, restored.queuePos)
        assertEquals(42_000L, restored.lastSongPos)
    }

    @Test
    fun readingQueuesDoesNotDiscardAValidQueueWhenAnEmptyQueueExists() = runBlocking {
        insertSongs("one")
        dao.insert(QueueEntity(id = 1L, title = "valid", queuePos = 0, lastSongPos = 5_000L, index = 0))
        dao.insert(QueueSongMap(queueId = 1L, songId = "one", index = 0L, shuffledIndex = 0L))
        dao.insert(QueueEntity(id = 2L, title = "empty", queuePos = 3, lastSongPos = -1L, index = 1))

        val loaded = dao.readQueue()

        assertEquals(listOf(1L), loaded.map { it.id })
        assertEquals(setOf(1L, 2L), dao.getAllQueues().first().map { it.id }.toSet())
        assertEquals("one", loaded.single().getCurrentSong()?.id)
        assertEquals(5_000L, loaded.single().lastSongPos)
    }

    @Test
    fun anEmptyInitializationSnapshotDoesNotEraseThePersistedQueue() = runBlocking {
        insertSongs("one")
        dao.saveQueue(
            MultiQueueObject(
                id = 7L,
                title = "persisted",
                queue = mutableListOf(metadata("one", shuffleIndex = 0)),
                queuePos = 0,
                lastSongPos = 9_000L,
                index = 0,
            )
        )

        dao.saveAllQueueSnapshots(emptyList())

        val persistedQueue = dao.getAllQueues().first().single()
        val persistedSongs = dao.getQueueSongs(7L).first()
        assertEquals(7L, persistedQueue.id)
        assertEquals(9_000L, persistedQueue.lastSongPos)
        assertEquals(listOf("one"), persistedSongs.map { it.song.id })
    }

    private fun metadata(id: String, shuffleIndex: Int) = MediaMetadata(
        id = id,
        title = id,
        artists = emptyList(),
        duration = 60,
        genre = null,
        shuffleIndex = shuffleIndex,
    )

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
