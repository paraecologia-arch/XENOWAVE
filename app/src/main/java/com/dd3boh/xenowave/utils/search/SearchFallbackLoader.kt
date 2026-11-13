package com.dd3boh.xenowave.utils.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

class SearchTimeoutException : IllegalStateException("Search request timed out")

/**
 * [original] is the first response, even when it is empty. [variant] is the first useful
 * conservative alternative. A failed alternative never replaces a valid empty response.
 */
data class SearchAttempt<T>(
    val original: T?,
    val variant: T?,
    val error: Throwable?,
)

/**
 * Executes the original query and, only when it is empty or short, a small number of
 * conservative variants. The budget is bounded per request and in total.
 */
class SearchFallbackLoader(
    private val requestTimeoutMillis: Long = 4_000,
    private val totalTimeoutMillis: Long = 9_000,
    private val maxAdditionalAttempts: Int = 2,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    suspend fun <T> load(
        query: String,
        variants: List<String>,
        request: suspend (String) -> Result<T>,
        isSparse: (T) -> Boolean,
    ): SearchAttempt<T> {
        val deadline = nanoTime() + totalTimeoutMillis * 1_000_000
        val original = runRequest(query, deadline, request)
        original.exceptionOrNull()?.let { return SearchAttempt(null, null, it) }
        val originalValue = original.getOrThrow()

        if (!isSparse(originalValue)) {
            return SearchAttempt(originalValue, null, null)
        }

        var bestVariant: T? = null
        for (variant in variants.take(maxAdditionalAttempts)) {
            currentCoroutineContext().ensureActive()
            if (nanoTime() >= deadline) break
            val value = runRequest(variant, deadline, request).getOrNull() ?: continue
            if (!isSparse(value)) {
                return SearchAttempt(originalValue, value, null)
            }
            if (bestVariant == null) bestVariant = value
        }
        return SearchAttempt(originalValue, bestVariant, null)
    }

    private suspend fun <T> runRequest(
        query: String,
        deadline: Long,
        request: suspend (String) -> Result<T>,
    ): Result<T> {
        val remainingMillis = ((deadline - nanoTime()) / 1_000_000).coerceAtLeast(0)
        if (remainingMillis <= 0) return Result.failure(SearchTimeoutException())
        return try {
            val result = withTimeoutOrNull(minOf(requestTimeoutMillis, remainingMillis)) {
                request(query)
            } ?: return Result.failure(SearchTimeoutException())
            when (val failure = result.exceptionOrNull()) {
                is TimeoutCancellationException -> Result.failure(SearchTimeoutException())
                is CancellationException -> throw failure
                else -> result
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }
}
