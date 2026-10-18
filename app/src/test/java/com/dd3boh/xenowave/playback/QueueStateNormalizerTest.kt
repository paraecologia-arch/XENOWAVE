package com.dd3boh.xenowave.playback

import androidx.media3.common.C
import com.dd3boh.xenowave.models.MediaMetadata
import com.dd3boh.xenowave.models.MultiQueueObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueStateNormalizerTest {

    @Test
    fun repairsShuffleIndexesAsADeterministicPermutation() {
        val queue = queue(shuffled = true, shuffleIndexes = listOf(2, 2, -1, 9))

        assertTrue(normalizeQueueState(queue))

        assertEquals(listOf(2, 0, 1, 3), queue.queue.map { it.shuffleIndex })
        assertEquals(4, queue.queue.map { it.shuffleIndex }.toSet().size)
        assertEquals("song-0", queue.getCurrentSong()?.id)
    }

    @Test
    fun restoresNormalIndexesAndInvalidCurrentPosition() {
        val queue = queue(shuffled = false, shuffleIndexes = listOf(9, -1), queuePos = 8, lastSongPos = -5)

        assertTrue(normalizeQueueState(queue))

        assertEquals(listOf(0, 1), queue.queue.map { it.shuffleIndex })
        assertEquals(0, queue.queuePos)
        assertEquals(0L, queue.lastSongPos)
    }

    @Test
    fun preservesTimeUnset() {
        val queue = queue(shuffled = false, shuffleIndexes = listOf(0), lastSongPos = C.TIME_UNSET)

        assertFalse(normalizeQueueState(queue))

        assertEquals(C.TIME_UNSET, queue.lastSongPos)
    }

    @Test
    fun normalizesAnEmptyQueueWithoutIndexAccess() {
        val queue = MultiQueueObject(
            id = 1L,
            title = "empty",
            queue = mutableListOf(),
            shuffled = true,
            queuePos = 4,
            lastSongPos = -10L,
            index = 0,
        )

        assertTrue(normalizeQueueState(queue))

        assertFalse(queue.shuffled)
        assertEquals(-1, queue.queuePos)
        assertEquals(0L, queue.lastSongPos)
    }

    @Test
    fun validShuffledQueueIsLeftUntouched() {
        val queue = queue(
            shuffled = true,
            shuffleIndexes = listOf(2, 0, 1),
            queuePos = 1,
            lastSongPos = 12_345L,
        )

        assertFalse(normalizeQueueState(queue))

        assertEquals(listOf(2, 0, 1), queue.queue.map { it.shuffleIndex })
        assertEquals(1, queue.queuePos)
        assertEquals(12_345L, queue.lastSongPos)
        assertEquals("song-1", queue.getCurrentSong()?.id)
    }

    @Test
    fun repeatedNormalizationIsStable() {
        val queue = queue(shuffled = true, shuffleIndexes = listOf(3, 3, 0, -1, 3))

        assertTrue(normalizeQueueState(queue))
        val firstResult = queue.queue.map { it.shuffleIndex }

        assertFalse(normalizeQueueState(queue))
        assertEquals(firstResult, queue.queue.map { it.shuffleIndex })
        assertEquals(5, firstResult.toSet().size)
    }

    private fun queue(
        shuffled: Boolean,
        shuffleIndexes: List<Int>,
        queuePos: Int = 0,
        lastSongPos: Long = 0L,
    ) = MultiQueueObject(
        id = 1L,
        title = "queue",
        queue = shuffleIndexes.mapIndexed { index, shuffleIndex ->
            MediaMetadata(
                id = "song-$index",
                title = "Song $index",
                artists = emptyList(),
                duration = 60,
                genre = null,
                shuffleIndex = shuffleIndex,
            )
        }.toMutableList(),
        shuffled = shuffled,
        queuePos = queuePos,
        lastSongPos = lastSongPos,
        index = 0,
    )
}
