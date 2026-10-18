package com.dd3boh.xenowave.playback

import com.dd3boh.xenowave.models.MultiQueueObject

internal data class QueuePersistenceRequest(
    val fullQueues: List<MultiQueueObject>,
    val metadataQueues: List<MultiQueueObject>,
    val allQueues: List<MultiQueueObject>?,
    val immediate: Boolean,
)

/**
 * Coalesces queue writes before they reach the single database worker.
 *
 * Requests are keyed by queue id. A newer request for the same queue replaces an older
 * snapshot, so a delayed or retried write can never be preferred over the latest state.
 */
internal class QueuePersistenceBuffer {
    private val fullQueues = LinkedHashMap<Long, MultiQueueObject>()
    private val metadataQueues = LinkedHashMap<Long, MultiQueueObject>()
    private var allQueues: List<MultiQueueObject>? = null
    private var immediate = false

    fun enqueue(
        fullQueue: MultiQueueObject? = null,
        metadataQueue: MultiQueueObject? = null,
        allQueues: List<MultiQueueObject>? = null,
        immediate: Boolean = false,
    ) {
        fullQueue?.let {
            fullQueues[it.id] = it
            metadataQueues.remove(it.id)
        }
        metadataQueue?.let { metadataQueues[it.id] = it }
        allQueues?.let { this.allQueues = it }
        this.immediate = this.immediate || immediate
    }

    fun isImmediate() = immediate

    fun take(): QueuePersistenceRequest? {
        if (fullQueues.isEmpty() && metadataQueues.isEmpty() && allQueues == null) {
            return null
        }

        val request = QueuePersistenceRequest(
            fullQueues = fullQueues.values.toList(),
            metadataQueues = metadataQueues.values.toList(),
            allQueues = allQueues,
            immediate = immediate,
        )
        fullQueues.clear()
        metadataQueues.clear()
        allQueues = null
        immediate = false
        return request
    }

    fun restoreFailed(request: QueuePersistenceRequest) {
        request.fullQueues.forEach { fullQueues.putIfAbsent(it.id, it) }
        request.metadataQueues.forEach { metadataQueues.putIfAbsent(it.id, it) }
        if (allQueues == null) {
            allQueues = request.allQueues
        }
        immediate = immediate || request.immediate
    }

    fun isEmpty() = fullQueues.isEmpty() && metadataQueues.isEmpty() && allQueues == null

    fun clear() {
        fullQueues.clear()
        metadataQueues.clear()
        allQueues = null
        immediate = false
    }
}
