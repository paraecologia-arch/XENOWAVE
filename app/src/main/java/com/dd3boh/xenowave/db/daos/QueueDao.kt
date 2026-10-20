package com.dd3boh.xenowave.db.daos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.dd3boh.xenowave.db.entities.QueueEntity
import com.dd3boh.xenowave.db.entities.QueueSong
import com.dd3boh.xenowave.db.entities.QueueSongMap
import com.dd3boh.xenowave.models.MultiQueueObject
import com.dd3boh.xenowave.models.toMediaMetadata
import com.dd3boh.xenowave.playback.normalizeQueueState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

@Dao
interface QueueDao {

    // region Gets
    @Query("SELECT * from queue ORDER BY `index`, id")
    fun getAllQueues(): Flow<List<QueueEntity>>

    @Transaction
    @Query("""
        SELECT song.*, queue_song_map.id AS mapId, queue_song_map.`index` AS queueIndex,
            queue_song_map.shuffledIndex
        FROM queue_song_map
        JOIN song ON queue_song_map.songId = song.id
        WHERE queueId = :queueId
        ORDER BY queue_song_map.`index`, queue_song_map.id
    """)
    fun getQueueSongs(queueId: Long): Flow<List<QueueSong>>

    suspend fun readQueue(): List<MultiQueueObject> {
        val resultQueues = ArrayList<MultiQueueObject>()
        val queues = getAllQueues().first()

        queues.forEach { queue ->
            val shuffledSongs = getQueueSongs(queue.id).first()
            if (shuffledSongs.isEmpty()) return@forEach
            val queueObject = MultiQueueObject(
                    id = queue.id,
                    title = queue.title,
                    queue = shuffledSongs.map {
                        val s = it.song.toMediaMetadata()
                        s.shuffleIndex = it.shuffledIndex
                        s
                    }.toMutableList(),
                    shuffled = queue.shuffled,
                    queuePos = queue.queuePos,
                    lastSongPos = queue.lastSongPos,
                    index = queue.index,
                    playlistId = queue.playlistId
                )
            resultQueues.add(normalizePersistedQueue(queueObject, shuffledSongs))
        }

        return resultQueues
    }

    suspend fun getResumptionQueue(): MultiQueueObject? {
        val queues = getAllQueues().first()
        if (queues.isEmpty()) return null
        val q = queues.last()
        val shuffledSongs = getQueueSongs(q.id).first()
        if (shuffledSongs.isEmpty()) return null

        return normalizePersistedQueue(
            MultiQueueObject(
            id = q.id,
            title = q.title,
            queue = shuffledSongs.map {
                val s = it.song.toMediaMetadata()
                s.shuffleIndex = it.shuffledIndex
                s
            }.toMutableList(),
            shuffled = q.shuffled,
            queuePos = q.queuePos,
            lastSongPos = q.lastSongPos,
            index = q.index,
            playlistId = q.playlistId
            ),
            shuffledSongs
        )
    }
    // endregion

    // region Inserts
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(queue: QueueEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(queueSong: QueueSongMap)
    // endregion

    // region Updates
    @Update
    fun update(queue: QueueEntity)

    @Transaction
    fun updateQueue(mq: MultiQueueObject) {
        update(
            QueueEntity(
                id = mq.id,
                title = mq.title,
                shuffled = mq.shuffled,
                queuePos = mq.queuePos,
                lastSongPos = mq.lastSongPos,
                index = mq.index,
                playlistId = mq.playlistId
            )
        )
    }

    @Transaction
    fun updateAllQueues(mqs: List<MultiQueueObject>) {
        val queues = mqs.toList()
        if (queues.isEmpty()) {
            deleteAllQueues()
            return
        }
        queues.forEachIndexed { index, q -> q.index = index }
        nukeAliens(queues.map { it.id })
        queues.forEach { updateQueue(it) }
    }

    @Transaction
    suspend fun normalizePersistedQueue(queue: MultiQueueObject, queueSongs: List<QueueSong>): MultiQueueObject {
        if (!normalizeQueueState(queue)) return queue

        update(
            QueueEntity(
                id = queue.id,
                title = queue.title,
                shuffled = queue.shuffled,
                queuePos = queue.queuePos,
                lastSongPos = queue.lastSongPos,
                index = queue.index,
                playlistId = queue.playlistId
            )
        )
        queueSongs.forEachIndexed { index, persistedSong ->
            val normalizedShuffleIndex = queue.queue[index].shuffleIndex
            if (persistedSong.queueIndex != index.toLong() ||
                persistedSong.shuffledIndex != normalizedShuffleIndex
            ) {
                updateQueueSongMap(persistedSong.mapId, index.toLong(), normalizedShuffleIndex.toLong())
            }
        }
        return queue
    }

    // endregion

    // region Deletes
    @Delete
    fun delete(mq: QueueEntity)

    @Query("DELETE FROM queue")
    fun deleteAllQueues()

    @Query("DELETE FROM queue_song_map WHERE queueId = :id")
    fun deleteAllQueueSongs(id: Long)

    @Query("DELETE FROM queue WHERE id = :id")
    fun deleteQueue(id: Long)

    @Query("DELETE FROM queue WHERE id NOT IN (:ids)")
    fun nukeAliens(ids: List<Long>)

    @Query("UPDATE queue_song_map SET `index` = :index, shuffledIndex = :shuffledIndex WHERE id = :id")
    fun updateQueueSongMap(id: Int, index: Long, shuffledIndex: Long)
    // endregion
}
