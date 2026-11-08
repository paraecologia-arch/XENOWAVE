package com.dd3boh.xenowave.insights.musicalage.data

import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Coordinates local calculation/enrichment with explicit history cleanup.
 * Call [invalidate] when Event history is cleared; a calculation that observes
 * a changed generation must not publish its old snapshot.
 */
interface HistoryGeneration {
    fun current(): Long
    fun invalidate()

    /** Observable invalidation, used to drop an already visible result. */
    val changes: StateFlow<Long>
}

class InMemoryHistoryGeneration(initial: Long = 0L) : HistoryGeneration {
    private val generation = AtomicLong(initial)
    private val flow = MutableStateFlow(initial)
    override val changes: StateFlow<Long> = flow.asStateFlow()

    override fun current(): Long = generation.get()
    override fun invalidate() {
        flow.value = generation.incrementAndGet()
    }
}

/**
 * Shared by the app and the future musical-age screen. The settings clear
 * action invalidates it immediately after deleting Event rows.
 */
object AppMusicalAgeHistoryGeneration : HistoryGeneration {
    private val generation = AtomicLong(0L)
    private val flow = MutableStateFlow(0L)
    override val changes: StateFlow<Long> = flow.asStateFlow()

    override fun current(): Long = generation.get()

    override fun invalidate() {
        flow.value = generation.incrementAndGet()
    }
}

class HistoryInvalidatedException :
    CancellationException("Listen history changed while the musical-age data was being read")
