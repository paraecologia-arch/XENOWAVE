package com.dd3boh.xenowave.playback

import androidx.media3.common.C
import com.dd3boh.xenowave.models.MultiQueueObject

/**
 * Repairs persisted queue state without changing its physical song order.
 *
 * The queue list is stored in normal order. When shuffle is enabled, shuffleIndex is a
 * permutation of that list, so retaining a valid unique value keeps the previous shuffle order.
 * Invalid or duplicate values are assigned the remaining positions in physical-list order.
 */
internal fun normalizeQueueState(queue: MultiQueueObject): Boolean {
    var changed = false

    if (queue.queue.isEmpty()) {
        if (queue.queuePos != -1) {
            queue.queuePos = -1
            changed = true
        }
        if (queue.shuffled) {
            queue.shuffled = false
            changed = true
        }
        if (queue.lastSongPos < 0L && queue.lastSongPos != C.TIME_UNSET) {
            queue.lastSongPos = 0L
            changed = true
        }
        return changed
    }

    if (queue.queuePos !in queue.queue.indices) {
        queue.queuePos = 0
        changed = true
    }

    if (queue.lastSongPos < 0L && queue.lastSongPos != C.TIME_UNSET) {
        queue.lastSongPos = 0L
        changed = true
    }

    if (!queue.shuffled) {
        queue.queue.forEachIndexed { index, song ->
            if (song.shuffleIndex != index) {
                song.shuffleIndex = index
                changed = true
            }
        }
        return changed
    }

    val used = BooleanArray(queue.queue.size)
    val repairIndexes = ArrayList<Int>()
    queue.queue.forEachIndexed { index, song ->
        val shuffleIndex = song.shuffleIndex
        if (shuffleIndex in queue.queue.indices && !used[shuffleIndex]) {
            used[shuffleIndex] = true
        } else {
            repairIndexes += index
        }
    }

    val availableIndexes = queue.queue.indices.filterNot(used::get).iterator()
    repairIndexes.forEach { index ->
        val replacement = availableIndexes.next()
        if (queue.queue[index].shuffleIndex != replacement) {
            queue.queue[index].shuffleIndex = replacement
            changed = true
        }
    }

    return changed
}
