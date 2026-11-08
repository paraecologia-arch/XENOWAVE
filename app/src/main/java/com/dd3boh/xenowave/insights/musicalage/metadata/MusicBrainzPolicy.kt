package com.dd3boh.xenowave.insights.musicalage.metadata

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.min

/**
 * XENOWAVE proposal for a bounded, polite public-metadata lookup. These are
 * application limits, not claims about MusicBrainz's official quotas.
 */
data class MusicBrainzPolicy(
    val maxRecordingsPerRun: Int = 20,
    val maxRequestsPerRun: Int = 40,
    val maxCandidatesPerSearch: Int = 10,
    val maxResponseBytes: Long = 1_048_576L,
    val perRequestTimeoutMillis: Long = 10_000L,
    val totalBudgetMillis: Long = 120_000L,
    val minRequestIntervalMillis: Long = 1_100L,
    val maxRetries: Int = 2,
    val backoffBaseMillis: Long = 1_000L,
    val backoffMaxMillis: Long = 8_000L,
    val confirmedTtlDays: Long = 30L,
    val ambiguityTtlHours: Long = 24L,
    val transientCooldownMinutes: Long = 15L,
) {
    init {
        require(maxRecordingsPerRun >= 0) { "maxRecordingsPerRun must not be negative" }
        require(maxRequestsPerRun >= 0) { "maxRequestsPerRun must not be negative" }
        require(maxCandidatesPerSearch in 1..10) { "maxCandidatesPerSearch must be 1..10" }
        require(maxResponseBytes > 0L) { "maxResponseBytes must be positive" }
        require(perRequestTimeoutMillis > 0L) { "perRequestTimeoutMillis must be positive" }
        require(totalBudgetMillis > 0L) { "totalBudgetMillis must be positive" }
        require(minRequestIntervalMillis >= 0L) { "minRequestIntervalMillis must not be negative" }
        require(maxRetries in 0..10) { "maxRetries must be 0..10" }
    }
}

/**
 * Real elapsed time is measured with the monotonic clock. Wall-clock time is
 * used only for dates and cache timestamps, never for deadlines.
 */
class EnrichmentBudget(
    private val policy: MusicBrainzPolicy,
    private val nanoClock: () -> Long = System::nanoTime,
) {
    private val startedAtNanos = nanoClock()
    private var requests = 0

    val requestCount: Int
        get() = requests

    val remainingMillis: Long
        get() {
            val elapsedNanos = (nanoClock() - startedAtNanos).coerceAtLeast(0L)
            val elapsedMillis = elapsedNanos / 1_000_000L
            return (policy.totalBudgetMillis - elapsedMillis).coerceAtLeast(0L)
        }

    fun canRequest(): Boolean =
        requests < policy.maxRequestsPerRun && remainingMillis > 0L

    fun onRequest() {
        requests++
    }

    fun requestTimeoutMillis(): Long = min(policy.perRequestTimeoutMillis, remainingMillis)
}

fun interface RequestRateLimiter {
    suspend fun acquire()

    /**
     * Same as [acquire] but refuses to sleep beyond the caller budget instead
     * of converting a long wait into zero. Implementations that know their
     * next allowed instant should override this.
     */
    suspend fun acquireWithin(maxWaitMillis: Long): Boolean {
        acquire()
        return true
    }
}

/**
 * One process-wide limiter by default, so multiple client instances share the
 * same minimum interval. Tests inject a fake limiter.
 */
class MusicBrainzRateLimiter(
    private val minIntervalMillis: Long = 1_100L,
    // Monotonic by default: wall-clock changes must not shorten or extend the
    // real spacing between request starts.
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : RequestRateLimiter {
    private val mutex = Mutex()
    private var nextAllowedAt = 0L

    override suspend fun acquire() {
        acquireWithin(Long.MAX_VALUE)
    }

    override suspend fun acquireWithin(maxWaitMillis: Long): Boolean {
        mutex.withLock {
            val now = clockMillis()
            val wait = (nextAllowedAt - now).coerceAtLeast(0L)
            if (wait > maxWaitMillis) return false
            if (wait > 0L) sleep(wait)
            nextAllowedAt = clockMillis() + minIntervalMillis
            return true
        }
    }
}

object MusicBrainzGlobalRateLimiter {
    val instance: RequestRateLimiter = MusicBrainzRateLimiter()
}

/**
 * Concurrency 1 shared by every service instance in the process. A second
 * batch waits instead of issuing a concurrent request.
 */
class MusicBrainzProviderGate(
    private val mutex: Mutex = Mutex(),
) {
    suspend fun <T> withPermit(block: suspend () -> T): T = mutex.withLock { block() }

    /**
     * Waits for the single provider slot without exceeding the caller's
     * deadline. Returning [PermitOutcome.NotAcquired] means the budget expired
     * while queued, so the caller must not send a request.
     */
    suspend fun <T> withPermitWithin(
        maxWaitMillis: Long,
        block: suspend () -> T,
    ): PermitOutcome<T> {
        if (maxWaitMillis <= 0L) return PermitOutcome.NotAcquired
        return withTimeoutOrNull(maxWaitMillis) {
            PermitOutcome.Acquired(mutex.withLock { block() })
        } ?: PermitOutcome.NotAcquired
    }

    companion object {
        val shared = MusicBrainzProviderGate()
    }
}

sealed interface PermitOutcome<out T> {
    data class Acquired<T>(val value: T) : PermitOutcome<T>
    data object NotAcquired : PermitOutcome<Nothing>
}

/**
 * Provider-wide cooldown derived from Retry-After. It survives the end of a
 * batch, so starting another run cannot bypass it.
 */
class MusicBrainzProviderCooldown(
    private val nanoClock: () -> Long = System::nanoTime,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private var blockedUntilNanos = 0L

    suspend fun waitIfNeeded(maxWaitMillis: Long): Boolean = mutex.withLock {
        val now = nanoClock()
        val waitNanos = (blockedUntilNanos - now).coerceAtLeast(0L)
        val waitMillis = waitNanos / 1_000_000L
        if (waitMillis <= 0L) return true
        if (waitMillis > maxWaitMillis) return false
        sleep(waitMillis)
        true
    }

    suspend fun deferFor(millis: Long) = mutex.withLock {
        if (millis <= 0L) return@withLock
        val until = saturatingAdd(nanoClock(), saturatingMultiply(millis, 1_000_000L))
        if (until > blockedUntilNanos) blockedUntilNanos = until
    }

    fun clear() {
        blockedUntilNanos = 0L
    }

    companion object {
        val shared = MusicBrainzProviderCooldown()
    }
}

/**
 * Parses Retry-After as seconds or an HTTP date.
 *
 * Negative, non-finite and overflowing values are not converted into a short
 * wait. A wait longer than the caller budget must be deferred by the caller.
 */
fun retryAfterMillis(headers: Map<String, String>, nowEpochMillis: Long): Long? {
    val raw = headers["retry-after"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    raw.toLongOrNull()?.let { seconds ->
        if (seconds < 0L) return null
        return saturatingMultiply(seconds, 1_000L)
    }
    return try {
        val date = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME)
        (date.toInstant().toEpochMilli() - nowEpochMillis).coerceAtLeast(0L)
    } catch (_: Exception) {
        null
    }
}

private fun saturatingMultiply(left: Long, right: Long): Long {
    if (left <= 0L || right <= 0L) return 0L
    return if (left > Long.MAX_VALUE / right) Long.MAX_VALUE else left * right
}

private fun saturatingAdd(left: Long, right: Long): Long {
    if (right <= 0L) return left
    return if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}
