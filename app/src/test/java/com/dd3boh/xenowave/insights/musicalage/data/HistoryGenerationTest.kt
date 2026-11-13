package com.dd3boh.xenowave.insights.musicalage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryGenerationTest {
    @Test
    fun `invalidation is observable for an already visible result`() {
        val generation = InMemoryHistoryGeneration()
        val before = generation.current()

        generation.invalidate()

        assertEquals(before + 1, generation.current())
        assertEquals(before + 1, generation.changes.value)
        assertTrue(generation.changes.value > before)
    }
}
