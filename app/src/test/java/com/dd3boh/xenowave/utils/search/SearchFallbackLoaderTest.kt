package com.dd3boh.xenowave.utils.search

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.system.measureTimeMillis

class SearchFallbackLoaderTest {
    private val loader = SearchFallbackLoader(
        requestTimeoutMillis = 200,
        totalTimeoutMillis = 600,
        maxAdditionalAttempts = 2,
    )

    @Test
    fun `useful original result does not trigger variants`() = runBlocking {
        val calls = mutableListOf<String>()
        val attempt = loader.load<List<String>>(
            query = "rock",
            variants = listOf("v1", "v2"),
            request = { query ->
                calls += query
                Result.success(listOf("one", "two"))
            },
            isSparse = { it.size < 2 },
        )

        assertEquals(listOf("rock"), calls)
        assertEquals(listOf("one", "two"), attempt.original)
        assertNull(attempt.variant)
        assertNull(attempt.error)
    }

    @Test
    fun `empty original uses first useful variant`() = runBlocking {
        val attempt = loader.load<List<String>>(
            query = "rock dos anos 70",
            variants = listOf("rock dos anos 1970", "70s rock"),
            request = { query ->
                if (query == "rock dos anos 70") Result.success(emptyList())
                else Result.success(listOf("related"))
            },
            isSparse = { it.isEmpty() },
        )

        assertEquals(emptyList<String>(), attempt.original)
        assertEquals(listOf("related"), attempt.variant)
        assertNull(attempt.error)
    }

    @Test
    fun `failed variant keeps the valid original response`() = runBlocking {
        val attempt = loader.load<List<String>>(
            query = "rock",
            variants = listOf("v1"),
            request = { query ->
                if (query == "rock") Result.success<List<String>>(emptyList())
                else Result.failure<List<String>>(IOException("network"))
            },
            isSparse = { it.isEmpty() },
        )

        assertEquals(emptyList<String>(), attempt.original)
        assertNull(attempt.variant)
        assertNull(attempt.error)
    }

    @Test
    fun `original failure is reported without extra requests`() = runBlocking {
        var calls = 0
        val attempt = loader.load<List<String>>(
            query = "rock",
            variants = listOf("v1", "v2"),
            request = {
                calls++
                Result.failure<List<String>>(IOException("offline"))
            },
            isSparse = { it.isEmpty() },
        )

        assertEquals(1, calls)
        assertNull(attempt.original)
        assertNull(attempt.variant)
        assertTrue(attempt.error is IOException)
    }

    @Test
    fun `variant budget is respected`() = runBlocking {
        val calls = mutableListOf<String>()
        val attempt = loader.load<List<String>>(
            query = "rock",
            variants = listOf("v1", "v2", "v3", "v4"),
            request = { query ->
                calls += query
                Result.success<List<String>>(emptyList())
            },
            isSparse = { it.isEmpty() },
        )

        assertEquals(3, calls.size)
        assertEquals(emptyList<String>(), attempt.original)
        assertNull(attempt.error)
    }

    @Test
    fun `request timeout is reported instead of hanging`() = runBlocking {
        lateinit var attempt: SearchAttempt<List<String>>
        val elapsed = measureTimeMillis {
            attempt = loader.load<List<String>>(
                query = "rock",
                variants = emptyList(),
                request = {
                    delay(5_000)
                    Result.success<List<String>>(emptyList())
                },
                isSparse = { it.isEmpty() },
            )
        }

        assertTrue(attempt.error is SearchTimeoutException)
        assertTrue("elapsed=$elapsed", elapsed < 2_000)
    }

    @Test
    fun `cancellation propagates to the caller`() = runBlocking {
        val job = launch {
            loader.load<List<String>>(
                query = "rock",
                variants = listOf("v1"),
                request = {
                    delay(10_000)
                    Result.success<List<String>>(emptyList())
                },
                isSparse = { it.isEmpty() },
            )
        }
        delay(50)
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
    }
}
