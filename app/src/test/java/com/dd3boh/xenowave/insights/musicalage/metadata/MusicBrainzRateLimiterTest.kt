package com.dd3boh.xenowave.insights.musicalage.metadata

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicBrainzRateLimiterTest {
    @Test
    fun `minimum interval is enforced across acquire calls`() = runBlocking {
        var now = 0L
        val sleeps = ArrayList<Long>()
        val limiter = MusicBrainzRateLimiter(
            minIntervalMillis = 1_100L,
            clockMillis = { now },
            sleep = { millis ->
                sleeps += millis
                now += millis
            },
        )

        limiter.acquire()
        limiter.acquire()
        limiter.acquire()

        assertEquals(listOf(1_100L, 1_100L), sleeps)
    }

    @Test
    fun `a wait larger than the budget is refused instead of shortened`() = runBlocking {
        var now = 0L
        val sleeps = ArrayList<Long>()
        val limiter = MusicBrainzRateLimiter(
            minIntervalMillis = 1_100L,
            clockMillis = { now },
            sleep = { millis ->
                sleeps += millis
                now += millis
            },
        )

        assertTrue(limiter.acquireWithin(2_000L))
        assertFalse(limiter.acquireWithin(500L))
        assertEquals(emptyList<Long>(), sleeps)
    }

    @Test
    fun `retry after validates negative and overflow`() {
        assertEquals(null, retryAfterMillis(mapOf("retry-after" to "-5"), 0L))
        assertEquals(
            Long.MAX_VALUE,
            retryAfterMillis(mapOf("retry-after" to Long.MAX_VALUE.toString()), 0L),
        )
    }

    @Test
    fun `provider cooldown is shared state and never shortened`() = runBlocking {
        var now = 0L
        val cooldown = MusicBrainzProviderCooldown(
            nanoClock = { now },
            sleep = { millis -> now += millis * 1_000_000L },
        )
        cooldown.deferFor(1_100L)

        assertFalse(cooldown.waitIfNeeded(50L))
        assertTrue(cooldown.waitIfNeeded(2_000L))
        assertTrue(cooldown.waitIfNeeded(0L))
    }
}
