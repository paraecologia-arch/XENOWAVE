package com.dd3boh.xenowave.playback

import com.dd3boh.xenowave.models.MediaMetadata
import com.dd3boh.xenowave.models.MultiQueueObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuePersistenceBufferTest {

    @Test
    fun newestFullSnapshotForTheSameQueueReplacesTheOlderOne() {
        val buffer = QueuePersistenceBuffer()
        val older = queue(lastSongPos = 100L)
        val newer = queue(lastSongPos = 200L)

        buffer.enqueue(fullQueue = older, allQueues = listOf(older), immediate = true)
        buffer.enqueue(fullQueue = newer, allQueues = listOf(newer), immediate = true)

        val request = buffer.take()!!
        assertEquals(1, request.fullQueues.size)
        assertEquals(200L, request.fullQueues.single().lastSongPos)
        assertEquals(200L, request.allQueues!!.single().lastSongPos)
        assertTrue(request.immediate)
        assertTrue(buffer.isEmpty())
    }

    @Test
    fun aNewerFullSnapshotSupersedesPendingMetadata() {
        val buffer = QueuePersistenceBuffer()
        val older = queue(lastSongPos = 100L)
        val newer = queue(lastSongPos = 200L)

        buffer.enqueue(metadataQueue = older)
        buffer.enqueue(fullQueue = newer)

        val request = buffer.take()!!
        assertTrue(request.metadataQueues.isEmpty())
        assertEquals(200L, request.fullQueues.single().lastSongPos)
    }

    @Test
    fun aFailedOlderWriteCannotOverwriteNewerPendingState() {
        val buffer = QueuePersistenceBuffer()
        val older = queue(lastSongPos = 100L)
        val newer = queue(lastSongPos = 200L)

        buffer.enqueue(fullQueue = older, allQueues = listOf(older), immediate = true)
        val failedRequest = buffer.take()!!
        buffer.enqueue(fullQueue = newer, allQueues = listOf(newer), immediate = true)
        buffer.restoreFailed(failedRequest)

        val retry = buffer.take()!!
        assertEquals(200L, retry.fullQueues.single().lastSongPos)
        assertEquals(200L, retry.allQueues!!.single().lastSongPos)
        assertTrue(retry.immediate)
    }

    @Test
    fun aFailedOlderFullSnapshotKeepsNewerMetadataQueuedAfterIt() {
        val buffer = QueuePersistenceBuffer()
        val older = queue(lastSongPos = 100L)
        val newerMetadata = queue(lastSongPos = 200L)

        buffer.enqueue(fullQueue = older, allQueues = listOf(older), immediate = true)
        val failedRequest = buffer.take()!!
        buffer.enqueue(metadataQueue = newerMetadata, allQueues = listOf(newerMetadata), immediate = true)
        buffer.restoreFailed(failedRequest)

        val retry = buffer.take()!!
        assertEquals(100L, retry.fullQueues.single().lastSongPos)
        assertEquals(200L, retry.metadataQueues.single().lastSongPos)
        assertEquals(200L, retry.allQueues!!.single().lastSongPos)
    }

    @Test
    fun emptyBufferHasNothingToWrite() {
        val buffer = QueuePersistenceBuffer()

        assertTrue(buffer.isEmpty())
        assertFalse(buffer.isImmediate())
        assertEquals(null, buffer.take())
    }

    private fun queue(lastSongPos: Long) = MultiQueueObject(
        id = 1L,
        title = "queue",
        queue = mutableListOf(
            MediaMetadata(
                id = "song",
                title = "song",
                artists = emptyList(),
                duration = 60,
                genre = null,
            )
        ),
        queuePos = 0,
        lastSongPos = lastSongPos,
        index = 0,
    )
}
