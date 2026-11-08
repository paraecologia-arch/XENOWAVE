package com.dd3boh.xenowave.insights.musicalage.metadata

import com.dd3boh.xenowave.insights.musicalage.MusicalAgeV2
import com.dd3boh.xenowave.insights.musicalage.RecordingIdentity
import com.dd3boh.xenowave.insights.musicalage.RecordingYearResolver
import com.dd3boh.xenowave.insights.musicalage.YearEvidence
import com.dd3boh.xenowave.insights.musicalage.YearEvidencePrecision
import com.dd3boh.xenowave.insights.musicalage.YearEvidenceSource
import com.dd3boh.xenowave.insights.musicalage.YearEvidenceState
import com.dd3boh.xenowave.insights.musicalage.content.EmptyManualEvidenceStore
import com.dd3boh.xenowave.insights.musicalage.content.ManualEvidenceStore
import com.dd3boh.xenowave.insights.musicalage.content.SongMetadata
import com.dd3boh.xenowave.insights.musicalage.content.resolveManualYear
import com.dd3boh.xenowave.insights.musicalage.data.AppMusicalAgeHistoryGeneration
import com.dd3boh.xenowave.insights.musicalage.data.HistoryGeneration
import com.dd3boh.xenowave.insights.musicalage.data.HistoryInvalidatedException
import com.dd3boh.xenowave.insights.musicalage.data.SongMetadataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.random.Random

enum class MetadataProvider {
    MUSICBRAINZ,
}

/**
 * Explicit consent token. Constructing the service or the token is not
 * consent: the caller must present it with the selected songs. Revoking it
 * cancels pending retries and prevents later publication/persistence.
 */
class MetadataConsent(
    /**
     * Called once when the token is revoked. The ViewModel uses this to cancel
     * the coroutine that owns the HTTP call, so revocation also stops an
     * in-flight request instead of only discarding its late result.
     */
    private val onRevoke: () -> Unit = {},
) {
    private val revoked = AtomicBoolean(false)

    val isActive: Boolean
        get() = !revoked.get()

    fun revoke() {
        if (revoked.compareAndSet(false, true)) {
            onRevoke()
        }
    }
}

class ConsentRevokedException :
    CancellationException("Metadados cancelados/revogados pelo usuário")

/**
 * Explicit authorization. Constructing the service is not consent; a lookup
 * must receive this object with the selected recordings and purpose.
 */
data class MetadataLookupAuthorization(
    val provider: MetadataProvider,
    val approvedSongIds: Set<String>,
    val purpose: String,
    val approvedAt: Instant,
    val consent: MetadataConsent = MetadataConsent(),
)

enum class EnrichmentStopReason {
    NOT_AUTHORIZED,
    EMPTY_SELECTION,
    COMPLETED,
    BUDGET_EXHAUSTED,
    DEFERRED_RETRY_AFTER,
    HISTORY_INVALIDATED,
    REVOKED,
}

data class LookupProgress(
    val selectedSongs: Int,
    val completedSongs: Int,
    val currentIndex: Int,
    val requestsAttempted: Int,
)

data class EnrichmentDiagnostic(
    val provider: MetadataProvider,
    val requestedSongs: Int,
    val authorizedSongs: Int,
    val selectedSongs: Int,
    val requestsAttempted: Int,
    val requestsBlocked: Int,
    val requestsDeferred: Int,
    val requestsFailed: Int,
    val retries: Int,
    val cacheHits: Int,
    val cacheMisses: Int,
    val cacheExpired: Int,
    val acceptedYears: Int,
    val candidateYears: Int,
    val conflictedYears: Int,
    val missingYears: Int,
    val transientErrors: Int,
    val responseBytes: Long,
    val stoppedReason: EnrichmentStopReason,
    val historyGeneration: Long,
    val cacheWriteFailures: Int = 0,
    val discardedCandidates: Int = 0,
    val staleEvidenceUsed: Int = 0,
)

data class EnrichmentSnapshot(
    val provider: MetadataProvider = MetadataProvider.MUSICBRAINZ,
    val evidence: Map<String, List<YearEvidence>> = emptyMap(),
    val diagnostic: EnrichmentDiagnostic,
    val generatedAt: Instant,
    val historyGeneration: Long,
    val stoppedReason: EnrichmentStopReason,
)

/**
 * Optional, explicitly authorized metadata enrichment. It never runs on
 * startup, playback or stats collection; it only runs when a caller passes an
 * authorization and a selected list of song ids.
 */
class MusicBrainzEnrichmentService(
    private val transport: MetadataTransport,
    private val metadataSource: SongMetadataSource,
    private val cache: RecordingEvidenceCache,
    private val matcher: RecordingMatcher = RecordingMatcher(),
    private val policy: MusicBrainzPolicy = MusicBrainzPolicy(),
    private val clock: Clock = Clock.systemUTC(),
    private val rateLimiter: RequestRateLimiter = MusicBrainzGlobalRateLimiter.instance,
    private val historyGeneration: HistoryGeneration = AppMusicalAgeHistoryGeneration,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val jitterMillis: () -> Long = { Random.nextLong(0L, 250L) },
    private val providerGate: MusicBrainzProviderGate = MusicBrainzProviderGate.shared,
    private val providerCooldown: MusicBrainzProviderCooldown =
        MusicBrainzProviderCooldown.shared,
) {
    /**
     * Reads only the private evidence cache for the given local song ids.
     * It performs no network request and never creates a [MetadataConsent].
     * The result is a pure snapshot for [SnapshotRecordingYearResolver].
     */
    suspend fun cachedEvidenceFor(
        songIds: List<String>,
    ): Map<String, List<YearEvidence>> = withContext(ioDispatcher) {
        val distinct = songIds.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return@withContext emptyMap()
        val metadata = metadataSource.metadataFor(distinct)
        val now = clock.instant()
        buildMap {
            for (songId in distinct) {
                val song = metadata[songId] ?: continue
                if (song.title.isBlank() || song.artists.isEmpty()) continue
                val key = RecordingEvidenceCacheKey.forMetadata(
                    song,
                    MusicalAgeV2.MATCHING_POLICY_VERSION,
                )
                val lookup = cache.get(key, now)
                val entry = lookup.entry
                if (lookup.hit && entry != null) {
                    put("local:$songId", entry.evidence)
                }
            }
        }
    }

    suspend fun enrich(
        authorization: MetadataLookupAuthorization?,
        songIds: List<String>,
        onProgress: (LookupProgress) -> Unit = {},
    ): EnrichmentSnapshot = withContext(ioDispatcher) {
        val requested = songIds.filter { it.isNotBlank() }.distinct()
        if (authorization == null ||
            authorization.provider != MetadataProvider.MUSICBRAINZ ||
            authorization.approvedSongIds.isEmpty() ||
            authorization.purpose.isBlank() ||
            !authorization.consent.isActive
        ) {
            return@withContext blockedSnapshot(
                requested,
                if (authorization != null && !authorization.consent.isActive) {
                    EnrichmentStopReason.REVOKED
                } else {
                    EnrichmentStopReason.NOT_AUTHORIZED
                },
            )
        }
        val selected = requested
            .filter { it in authorization.approvedSongIds }
            .take(policy.maxRecordingsPerRun)
        if (selected.isEmpty()) {
            return@withContext blockedSnapshot(requested, EnrichmentStopReason.EMPTY_SELECTION)
        }

        // The total budget starts before local metadata/cache loading: queue
        // waits, rate limiting, backoff and retries all consume the same clock.
        val budget = EnrichmentBudget(policy)
        val generation = historyGeneration.current()
        val metadata = metadataSource.metadataFor(selected)
        if (historyGeneration.current() != generation) {
            return@withContext invalidatedSnapshot(requested, selected.size, generation)
        }

        val evidence = LinkedHashMap<String, List<YearEvidence>>()
        var cacheHits = 0
        var cacheMisses = 0
        var cacheExpired = 0
        var cacheWriteFailures = 0
        var requestsBlocked = 0
        var requestsDeferred = 0
        var requestsFailed = 0
        var retries = 0
        var discardedCandidates = 0
        var staleEvidenceUsed = 0
        var responseBytes = 0L
        var completed = 0
        var stopReason = EnrichmentStopReason.COMPLETED
        val cacheGeneration = cache.generation()

        for ((index, songId) in selected.withIndex()) {
            coroutineContext.ensureActive()
            activeStopReason(authorization, generation)?.let { reason ->
                return@withContext if (reason == EnrichmentStopReason.REVOKED) {
                    revokedSnapshot(requested, selected.size, generation)
                } else {
                    invalidatedSnapshot(requested, selected.size, generation)
                }
            }
            onProgress(
                LookupProgress(
                    selectedSongs = selected.size,
                    completedSongs = completed,
                    currentIndex = index,
                    requestsAttempted = budget.requestCount,
                )
            )
            val song = metadata[songId]
            if (song == null || song.title.isBlank() || song.artists.isEmpty()) {
                evidence[songId] = listOf(
                    missingEvidence(songId, "metadados locais insuficientes para busca")
                )
                completed++
                continue
            }

            val cacheKey = RecordingEvidenceCacheKey.forMetadata(
                song,
                MusicalAgeV2.MATCHING_POLICY_VERSION,
            )
            val now = clock.instant()
            val cached = cache.get(cacheKey, now)
            val staleCandidate = cached.entry?.takeIf { cached.expired }
            if (cached.hit) {
                cacheHits++
                cached.entry?.evidence?.let {
                    evidence[songId] = it
                    if (it.any { item -> item.stale }) staleEvidenceUsed++
                }
                completed++
                continue
            }
            if (cached.expired) cacheExpired++
            cacheMisses++

            if (!budget.canRequest()) {
                requestsBlocked++
                stopReason = EnrichmentStopReason.BUDGET_EXHAUSTED
                if (staleCandidate != null) {
                    evidence[songId] = staleEvidence(staleCandidate.evidence)
                    staleEvidenceUsed++
                }
                break
            }

            val result = lookupWithRetries(
                song = song,
                budget = budget,
                authorization = authorization,
                generation = generation,
                onRetry = { retries++ },
                onDeferred = { requestsDeferred++ },
                onFailed = { requestsFailed++ },
                onBytes = { responseBytes += it },
            )
            if (result.stopReason == EnrichmentStopReason.REVOKED ||
                result.stopReason == EnrichmentStopReason.HISTORY_INVALIDATED
            ) {
                return@withContext if (result.stopReason == EnrichmentStopReason.REVOKED) {
                    revokedSnapshot(requested, selected.size, generation)
                } else {
                    invalidatedSnapshot(requested, selected.size, generation)
                }
            }
            if (result.stopReason != null) {
                stopReason = result.stopReason
                if (stopReason == EnrichmentStopReason.BUDGET_EXHAUSTED) {
                    requestsBlocked++
                }
                if (staleCandidate != null) {
                    evidence[songId] = staleEvidence(staleCandidate.evidence)
                    staleEvidenceUsed++
                } else {
                    evidence[songId] = listOf(
                        transientEvidence(
                            songId,
                            result.failureReason
                                ?: "consulta adiada para respeitar Retry-After/orçamento",
                        )
                    )
                }
                break
            }
            val match = result.match
            val freshEvidence = if (match != null) {
                listOf(match.evidence)
            } else if (staleCandidate != null) {
                staleEvidenceUsed++
                staleEvidence(staleCandidate.evidence)
            } else {
                listOf(
                    transientEvidence(
                        songId,
                        result.failureReason ?: "falha transitória de metadados",
                    )
                )
            }
            val preservedConfirmed = match != null &&
                match.evidence.state != YearEvidenceState.ACCEPTED &&
                staleCandidate?.evidence
                    ?.any { it.state == YearEvidenceState.ACCEPTED } == true
            val evidenceList = if (preservedConfirmed) {
                // A negative/contradictory answer must not erase a previously
                // confirmed year. Keep it, mark it stale, and add the fresh
                // negative/conflict evidence so provenance stays visible.
                preservedEvidence(staleCandidate.evidence) +
                    freshEvidence.filter { it.state != YearEvidenceState.ACCEPTED }
            } else {
                freshEvidence
            }
            discardedCandidates += result.discardedCandidates
            evidence[songId] = evidenceList
            if (match != null) {
                // Never persist or publish after revocation/cleanup.
                activeStopReason(authorization, generation)?.let { reason ->
                    return@withContext if (reason == EnrichmentStopReason.REVOKED) {
                        revokedSnapshot(requested, selected.size, generation)
                    } else {
                        invalidatedSnapshot(requested, selected.size, generation)
                    }
                }
                val kind = if (preservedConfirmed) {
                    // Re-check sooner because the renewal was not confirmed.
                    CacheEntryKind.AMBIGUITY
                } else {
                    cacheKind(freshEvidence)
                }
                val wrote = cache.put(
                    RecordingEvidenceCacheEntry(
                        key = cacheKey,
                        kind = kind,
                        evidence = evidenceList,
                        storedAt = now,
                        expiresAt = now.plus(ttl(kind)),
                    ),
                    expectedGeneration = cacheGeneration,
                )
                if (!wrote) cacheWriteFailures++
                if (preservedConfirmed) staleEvidenceUsed++
            }
            completed++
            onProgress(
                LookupProgress(
                    selectedSongs = selected.size,
                    completedSongs = completed,
                    currentIndex = index,
                    requestsAttempted = budget.requestCount,
                )
            )
        }

        activeStopReason(authorization, generation)?.let { reason ->
            return@withContext if (reason == EnrichmentStopReason.REVOKED) {
                revokedSnapshot(requested, selected.size, generation)
            } else {
                invalidatedSnapshot(requested, selected.size, generation)
            }
        }
        EnrichmentSnapshot(
            evidence = evidence,
            diagnostic = diagnostic(
                requested = requested,
                authorized = authorization.approvedSongIds,
                selected = selected,
                evidence = evidence,
                requestsAttempted = budget.requestCount,
                requestsBlocked = requestsBlocked,
                requestsDeferred = requestsDeferred,
                requestsFailed = requestsFailed,
                retries = retries,
                cacheHits = cacheHits,
                cacheMisses = cacheMisses,
                cacheExpired = cacheExpired,
                cacheWriteFailures = cacheWriteFailures,
                discardedCandidates = discardedCandidates,
                staleEvidenceUsed = staleEvidenceUsed,
                responseBytes = responseBytes,
                stopReason = stopReason,
                generation = generation,
            ),
            generatedAt = clock.instant(),
            historyGeneration = generation,
            stoppedReason = stopReason,
        )
    }

    private fun activeStopReason(
        authorization: MetadataLookupAuthorization,
        generation: Long,
    ): EnrichmentStopReason? = when {
        !authorization.consent.isActive -> EnrichmentStopReason.REVOKED
        historyGeneration.current() != generation -> EnrichmentStopReason.HISTORY_INVALIDATED
        else -> null
    }

    private suspend fun lookupWithRetries(
        song: SongMetadata,
        budget: EnrichmentBudget,
        authorization: MetadataLookupAuthorization,
        generation: Long,
        onRetry: () -> Unit,
        onDeferred: () -> Unit,
        onFailed: () -> Unit,
        onBytes: (Long) -> Unit,
    ): LookupOutcome {
        var attempt = 0
        var lastFailure: String? = null
        var discarded = 0
        while (attempt <= policy.maxRetries) {
            coroutineContext.ensureActive()
            activeStopReason(authorization, generation)?.let { reason ->
                return LookupOutcome(
                    stopReason = reason,
                    failureReason = if (reason == EnrichmentStopReason.REVOKED) {
                        "consentimento revogado"
                    } else {
                        "histórico invalidado"
                    },
                )
            }
            if (!budget.canRequest()) {
                return LookupOutcome(
                    stopReason = EnrichmentStopReason.BUDGET_EXHAUSTED,
                    match = null,
                    failureReason = lastFailure ?: "orçamento de requisições esgotado",
                )
            }
            val permit = providerGate.withPermitWithin(budget.remainingMillis) {
                // Everything below runs after the real provider slot was
                // acquired and before the network call. The deadline, consent,
                // generation, cooldown and request budget are rechecked here.
                coroutineContext.ensureActive()
                activeStopReason(authorization, generation)?.let { reason ->
                    return@withPermitWithin LookupOutcome(
                        stopReason = reason,
                        failureReason = if (reason == EnrichmentStopReason.REVOKED) {
                            "consentimento revogado"
                        } else {
                            "histórico invalidado"
                        },
                    )
                }
                if (!budget.canRequest()) {
                    return@withPermitWithin LookupOutcome(
                        stopReason = EnrichmentStopReason.BUDGET_EXHAUSTED,
                        failureReason = lastFailure ?: "orçamento de requisições esgotado",
                    )
                }
                if (!providerCooldown.waitIfNeeded(budget.remainingMillis)) {
                    return@withPermitWithin deferredOutcome(
                        "cooldown do provedor maior que o orçamento restante",
                        discarded,
                    )
                }
                if (!rateLimiter.acquireWithin(budget.remainingMillis)) {
                    return@withPermitWithin deferredOutcome(
                        "intervalo mínimo maior que o orçamento restante",
                        discarded,
                    )
                }
                budget.onRequest()
                // Compute the timeout only after queueing/rate limiting, so a
                // long wait cannot reuse an already expired timeout.
                val timeout = budget.requestTimeoutMillis()
                if (timeout <= 0L) {
                    return@withPermitWithin LookupOutcome(
                        stopReason = EnrichmentStopReason.BUDGET_EXHAUSTED,
                        failureReason = lastFailure ?: "orçamento total esgotado",
                    )
                }
                try {
                    val response = transport.execute(
                        MetadataRequest(
                            url = MusicBrainzQuery.searchUrl(song, policy.maxCandidatesPerSearch),
                            headers = mapOf("Accept" to "application/json"),
                        ),
                        maxResponseBytes = policy.maxResponseBytes,
                        timeoutMillis = timeout,
                    )
                    if (!authorization.consent.isActive) {
                        return@withPermitWithin LookupOutcome(
                            stopReason = EnrichmentStopReason.REVOKED,
                            failureReason = "consentimento revogado",
                        )
                    }
                    if (historyGeneration.current() != generation) {
                        return@withPermitWithin LookupOutcome(
                            stopReason = EnrichmentStopReason.HISTORY_INVALIDATED,
                            failureReason = "histórico invalidado",
                        )
                    }
                    onBytes(response.body.toByteArray(Charsets.UTF_8).size.toLong())
                    when {
                        response.statusCode == 200 -> {
                            val page = MusicBrainzJsonParser.parseRecordingSearchPage(
                                response.body,
                                policy.maxCandidatesPerSearch,
                            )
                            discarded = page.discarded.size
                            val today = LocalDate.ofInstant(clock.instant(), clock.zone)
                            val match = matcher.match(
                                metadata = song,
                                candidates = page.candidates,
                                today = today,
                                obtainedAt = clock.instant(),
                                search = page.completeness,
                            )
                            LookupOutcome(
                                stopReason = null,
                                match = match,
                                failureReason = null,
                                discardedCandidates = discarded,
                            )
                        }

                        response.statusCode == 404 -> {
                            val today = LocalDate.ofInstant(clock.instant(), clock.zone)
                            val match = matcher.match(
                                metadata = song,
                                candidates = emptyList(),
                                today = today,
                                obtainedAt = clock.instant(),
                                search = SearchCompleteness(
                                    totalCount = 0,
                                    offset = 0,
                                    returnedCount = 0,
                                    limit = policy.maxCandidatesPerSearch,
                                ),
                            )
                            LookupOutcome(
                                stopReason = null,
                                match = match,
                                failureReason = null,
                            )
                        }

                        response.statusCode == 429 || response.statusCode == 503 -> {
                            val retryAfter = retryAfterMillis(response.headers, clock.millis())
                            if (retryAfter != null) {
                                // Another instance may have received this
                                // Retry-After while this one was queueing.
                                providerCooldown.deferFor(retryAfter)
                                if (retryAfter >= budget.remainingMillis) {
                                    return@withPermitWithin deferredOutcome(
                                        "Retry-After maior que o orçamento restante",
                                        discarded,
                                    )
                                }
                            }
                            lastFailure = "rate limit HTTP ${response.statusCode}"
                            if (attempt >= policy.maxRetries) {
                                LookupOutcome(
                                    stopReason = null,
                                    match = null,
                                    failureReason = lastFailure,
                                    discardedCandidates = discarded,
                                )
                            } else {
                                val wait = retryAfter ?: backoffMillis(attempt)
                                if (wait >= budget.remainingMillis) {
                                    deferredOutcome(
                                        "espera maior que o orçamento restante",
                                        discarded,
                                    )
                                } else {
                                    onRetry()
                                    LookupOutcome(
                                        stopReason = null,
                                        match = null,
                                        failureReason = lastFailure,
                                        discardedCandidates = discarded,
                                        retryDelayMillis = wait,
                                    )
                                }
                            }
                        }

                        else -> {
                            lastFailure = "HTTP ${response.statusCode}"
                            onFailed()
                            if (attempt >= policy.maxRetries) {
                                LookupOutcome(
                                    stopReason = null,
                                    match = null,
                                    failureReason = lastFailure,
                                    discardedCandidates = discarded,
                                )
                            } else {
                                val wait = backoffMillis(attempt)
                                if (wait >= budget.remainingMillis) {
                                    deferredOutcome(
                                        "espera maior que o orçamento restante",
                                        discarded,
                                    )
                                } else {
                                    onRetry()
                                    LookupOutcome(
                                        stopReason = null,
                                        match = null,
                                        failureReason = lastFailure,
                                        discardedCandidates = discarded,
                                        retryDelayMillis = wait,
                                    )
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: MetadataResponseTooLargeException) {
                    retryableFailure(
                        "resposta grande/truncada",
                        attempt,
                        budget,
                        discarded,
                        onFailed,
                        onRetry,
                    )
                } catch (e: MetadataParseException) {
                    retryableFailure(
                        "JSON inválido/estrutura inesperada",
                        attempt,
                        budget,
                        discarded,
                        onFailed,
                        onRetry,
                    )
                } catch (e: MetadataTransportException) {
                    retryableFailure(
                        e.message ?: "falha de transporte",
                        attempt,
                        budget,
                        discarded,
                        onFailed,
                        onRetry,
                    )
                } catch (e: IOException) {
                    retryableFailure(
                        "falha de rede",
                        attempt,
                        budget,
                        discarded,
                        onFailed,
                        onRetry,
                    )
                } catch (e: Exception) {
                    // A malformed response must never take down the batch/screen.
                    retryableFailure(
                        "resposta de metadados inesperada",
                        attempt,
                        budget,
                        discarded,
                        onFailed,
                        onRetry,
                    )
                }
            }
            when (permit) {
                PermitOutcome.NotAcquired -> {
                    onDeferred()
                    return LookupOutcome(
                        stopReason = EnrichmentStopReason.DEFERRED_RETRY_AFTER,
                        failureReason = "orçamento esgotado aguardando a fila do provedor",
                        discardedCandidates = discarded,
                    )
                }

                is PermitOutcome.Acquired -> {
                    val outcome = permit.value
                    if (outcome.stopReason == EnrichmentStopReason.DEFERRED_RETRY_AFTER) {
                        onDeferred()
                        return outcome
                    }
                    val wait = outcome.retryDelayMillis
                    if (wait == null) return outcome
                    if (wait >= budget.remainingMillis) {
                        onDeferred()
                        return deferredOutcome(
                            "espera maior que o orçamento restante",
                            outcome.discardedCandidates,
                        )
                    }
                    delay(wait)
                    attempt++
                }
            }
        }
        return LookupOutcome(
            stopReason = null,
            match = null,
            failureReason = lastFailure,
            discardedCandidates = discarded,
        )
    }

    private fun deferredOutcome(
        reason: String,
        discarded: Int,
    ): LookupOutcome = LookupOutcome(
        stopReason = EnrichmentStopReason.DEFERRED_RETRY_AFTER,
        failureReason = reason,
        discardedCandidates = discarded,
    )

    private fun retryableFailure(
        reason: String,
        attempt: Int,
        budget: EnrichmentBudget,
        discarded: Int,
        onFailed: () -> Unit,
        onRetry: () -> Unit,
    ): LookupOutcome {
        onFailed()
        if (attempt >= policy.maxRetries) {
            return LookupOutcome(
                stopReason = null,
                match = null,
                failureReason = reason,
                discardedCandidates = discarded,
            )
        }
        val wait = backoffMillis(attempt)
        if (wait >= budget.remainingMillis) {
            return deferredOutcome("espera maior que o orçamento restante", discarded)
        }
        onRetry()
        return LookupOutcome(
            stopReason = null,
            match = null,
            failureReason = reason,
            discardedCandidates = discarded,
            retryDelayMillis = wait,
        )
    }

    private fun backoffMillis(attempt: Int): Long {
        val exponential = policy.backoffBaseMillis shl attempt.coerceAtMost(10)
        return min(policy.backoffMaxMillis, exponential) + jitterMillis()
    }

    private fun blockedSnapshot(
        requested: List<String>,
        reason: EnrichmentStopReason,
    ): EnrichmentSnapshot {
        val generation = historyGeneration.current()
        return EnrichmentSnapshot(
            diagnostic = diagnostic(
                requested = requested,
                authorized = emptySet(),
                selected = emptyList(),
                evidence = emptyMap(),
                requestsAttempted = 0,
                requestsBlocked = requested.size,
                requestsDeferred = 0,
                requestsFailed = 0,
                retries = 0,
                cacheHits = 0,
                cacheMisses = 0,
                cacheExpired = 0,
                responseBytes = 0L,
                stopReason = reason,
                generation = generation,
            ),
            generatedAt = clock.instant(),
            historyGeneration = generation,
            stoppedReason = reason,
        )
    }

    private fun invalidatedSnapshot(
        requested: List<String>,
        selectedCount: Int,
        generation: Long,
    ): EnrichmentSnapshot = emptySnapshot(
        requested,
        selectedCount,
        generation,
        EnrichmentStopReason.HISTORY_INVALIDATED,
    )

    private fun revokedSnapshot(
        requested: List<String>,
        selectedCount: Int,
        generation: Long,
    ): EnrichmentSnapshot = emptySnapshot(
        requested,
        selectedCount,
        generation,
        EnrichmentStopReason.REVOKED,
    )

    private fun emptySnapshot(
        requested: List<String>,
        selectedCount: Int,
        generation: Long,
        reason: EnrichmentStopReason,
    ): EnrichmentSnapshot = EnrichmentSnapshot(
        diagnostic = diagnostic(
            requested = requested,
            authorized = emptySet(),
            selected = emptyList(),
            evidence = emptyMap(),
            requestsAttempted = 0,
            requestsBlocked = 0,
            requestsDeferred = 0,
            requestsFailed = 0,
            retries = 0,
            cacheHits = 0,
            cacheMisses = 0,
            cacheExpired = 0,
            responseBytes = 0L,
            stopReason = reason,
            generation = generation,
        ),
        generatedAt = clock.instant(),
        historyGeneration = generation,
        stoppedReason = reason,
    )

    private fun diagnostic(
        requested: List<String>,
        authorized: Set<String>,
        selected: List<String>,
        evidence: Map<String, List<YearEvidence>>,
        requestsAttempted: Int,
        requestsBlocked: Int,
        requestsDeferred: Int,
        requestsFailed: Int,
        retries: Int,
        cacheHits: Int,
        cacheMisses: Int,
        cacheExpired: Int,
        responseBytes: Long,
        stopReason: EnrichmentStopReason,
        generation: Long,
        cacheWriteFailures: Int = 0,
        discardedCandidates: Int = 0,
        staleEvidenceUsed: Int = 0,
    ): EnrichmentDiagnostic {
        val all = evidence.values.flatten()
        return EnrichmentDiagnostic(
            provider = MetadataProvider.MUSICBRAINZ,
            requestedSongs = requested.size,
            authorizedSongs = authorized.size,
            selectedSongs = selected.size,
            requestsAttempted = requestsAttempted,
            requestsBlocked = requestsBlocked,
            requestsDeferred = requestsDeferred,
            requestsFailed = requestsFailed,
            retries = retries,
            cacheHits = cacheHits,
            cacheMisses = cacheMisses,
            cacheExpired = cacheExpired,
            acceptedYears = all.count { it.state == YearEvidenceState.ACCEPTED },
            candidateYears = all.count { it.state == YearEvidenceState.CANDIDATE },
            conflictedYears = all.count { it.state == YearEvidenceState.CONFLICT },
            missingYears = all.count { it.state == YearEvidenceState.MISSING },
            transientErrors = all.count { it.state == YearEvidenceState.TRANSIENT_ERROR },
            responseBytes = responseBytes,
            stoppedReason = stopReason,
            historyGeneration = generation,
            cacheWriteFailures = cacheWriteFailures,
            discardedCandidates = discardedCandidates,
            staleEvidenceUsed = staleEvidenceUsed,
        )
    }

    private fun missingEvidence(songId: String, reason: String): YearEvidence = YearEvidence(
        recordingKey = "local:$songId",
        state = YearEvidenceState.MISSING,
        origin = "local metadata",
        source = YearEvidenceSource.NONE,
        reason = reason,
        policyVersion = MusicalAgeV2.YEAR_EVIDENCE_POLICY_VERSION,
    )

    private fun transientEvidence(songId: String, reason: String): YearEvidence = YearEvidence(
        recordingKey = "local:$songId",
        state = YearEvidenceState.TRANSIENT_ERROR,
        origin = "MusicBrainz",
        source = YearEvidenceSource.CATALOG,
        transientError = reason,
        reason = reason,
        policyVersion = MusicalAgeV2.YEAR_EVIDENCE_POLICY_VERSION,
    )

    private fun staleEvidence(evidence: List<YearEvidence>): List<YearEvidence> =
        evidence.map {
            it.copy(
                stale = true,
                reason = "evidência anterior preservada; a renovação falhou",
            )
        }

    private fun preservedEvidence(evidence: List<YearEvidence>): List<YearEvidence> =
        evidence.map {
            it.copy(
                stale = true,
                reason = "evidência confirmada anterior preservada; a nova consulta não a confirmou",
            )
        }

    private fun cacheKind(evidence: List<YearEvidence>): CacheEntryKind = when {
        evidence.any { it.state == YearEvidenceState.ACCEPTED } -> CacheEntryKind.CONFIRMED
        evidence.any { it.state == YearEvidenceState.TRANSIENT_ERROR } ->
            CacheEntryKind.TRANSIENT_ERROR
        evidence.any { it.state == YearEvidenceState.CONFLICT } ->
            CacheEntryKind.AMBIGUITY
        evidence.any { it.state == YearEvidenceState.CANDIDATE } ->
            CacheEntryKind.AMBIGUITY
        else -> CacheEntryKind.ABSENCE
    }

    private fun ttl(kind: CacheEntryKind): Duration = when (kind) {
        CacheEntryKind.CONFIRMED -> Duration.ofDays(policy.confirmedTtlDays)
        CacheEntryKind.AMBIGUITY,
        CacheEntryKind.ABSENCE,
        -> Duration.ofHours(policy.ambiguityTtlHours)
        CacheEntryKind.TRANSIENT_ERROR -> Duration.ofMinutes(policy.transientCooldownMinutes)
    }

    private data class LookupOutcome(
        val stopReason: EnrichmentStopReason?,
        val match: RecordingMatchResult? = null,
        val failureReason: String?,
        val discardedCandidates: Int = 0,
        val retryDelayMillis: Long? = null,
    )
}

/**
 * Sync resolver used by the pure engine. It merges explicit manual evidence
 * with a previously produced immutable snapshot; it performs no IO.
 */
class SnapshotRecordingYearResolver(
    private val snapshot: EnrichmentSnapshot,
    private val manualEvidenceStore: ManualEvidenceStore = EmptyManualEvidenceStore,
) : RecordingYearResolver {
    override fun resolve(recordings: List<RecordingIdentity>): Map<String, List<YearEvidence>> =
        recordings.associate { recording ->
            if (snapshot.stoppedReason == EnrichmentStopReason.HISTORY_INVALIDATED) {
                throw HistoryInvalidatedException()
            }
            if (snapshot.stoppedReason == EnrichmentStopReason.REVOKED) {
                throw ConsentRevokedException()
            }
            val songIds = recording.localSongIds.ifEmpty {
                listOfNotNull(recording.localSongId)
            }
            val manual = manualEvidenceStore.forSongs(songIds).values.flatten()
            val manualResolution = resolveManualYear(manual)
            val snapshotEvidence = songIds.firstNotNullOfOrNull {
                snapshot.evidence[it]
            }
            val resolved = when {
                manualResolution.isConflict -> listOf(
                    YearEvidence(
                        recordingKey = recording.recordingKey,
                        state = YearEvidenceState.CONFLICT,
                        origin = "user-confirmed",
                        source = YearEvidenceSource.USER_CONFIRMED,
                        conflictingYears = manualResolution.conflictingYears,
                        canonicalRecordingId = recording.canonicalRecordingId,
                        reason = manualResolution.reason,
                        policyVersion = MusicalAgeV2.MANUAL_EVIDENCE_POLICY_VERSION,
                    )
                )

                manualResolution.accepted != null -> listOf(
                    YearEvidence(
                        recordingKey = recording.recordingKey,
                        year = manualResolution.accepted.year,
                        state = YearEvidenceState.ACCEPTED,
                        origin = "user-confirmed",
                        precision = manualResolution.accepted.precision,
                        resolvedVersion = manualResolution.accepted.resolvedVersion.label,
                        canonicalRecordingId = manualResolution.accepted.canonicalRecordingId
                            ?: recording.canonicalRecordingId,
                        obtainedAt = manualResolution.accepted.chosenAt,
                        source = YearEvidenceSource.USER_CONFIRMED,
                        sourceReference = "manual-evidence",
                        acceptanceRule = "user-confirmed-v2",
                        linkedRecordingId = manualResolution.accepted.recordingKey
                            ?: recording.recordingKey,
                        associationProof = "user-identified-version",
                        policyVersion = MusicalAgeV2.MANUAL_EVIDENCE_POLICY_VERSION,
                    )
                )

                manualResolution.incomplete != null -> listOf(
                    YearEvidence(
                        recordingKey = recording.recordingKey,
                        year = manualResolution.incomplete.year,
                        state = YearEvidenceState.CANDIDATE,
                        origin = "user-confirmed-incomplete",
                        precision = manualResolution.incomplete.precision,
                        resolvedVersion = manualResolution.incomplete.resolvedVersion.label,
                        source = YearEvidenceSource.USER_CONFIRMED,
                        sourceReference = "manual-evidence",
                        acceptanceRule = "user-confirmed-v2",
                        linkedRecordingId = recording.recordingKey,
                        reason = manualResolution.reason,
                        policyVersion = MusicalAgeV2.MANUAL_EVIDENCE_POLICY_VERSION,
                    )
                )

                snapshotEvidence != null -> snapshotEvidence
                recording.localYearHint != null -> listOf(
                    YearEvidence(
                        recordingKey = recording.recordingKey,
                        year = recording.localYearHint,
                        state = YearEvidenceState.UNVERIFIED,
                        origin = "song.year (semântica/proveniência da gravação não comprovada)",
                        precision = YearEvidencePrecision.UNKNOWN,
                        source = YearEvidenceSource.LOCAL_TAG,
                        canonicalRecordingId = recording.canonicalRecordingId,
                        policyVersion = MusicalAgeV2.YEAR_EVIDENCE_POLICY_VERSION,
                    )
                )
                else -> listOf(
                    YearEvidence(
                        recordingKey = recording.recordingKey,
                        state = YearEvidenceState.MISSING,
                        origin = "sem evidência local ou de catálogo",
                        source = YearEvidenceSource.NONE,
                        canonicalRecordingId = recording.canonicalRecordingId,
                        policyVersion = MusicalAgeV2.YEAR_EVIDENCE_POLICY_VERSION,
                    )
                )
            }
            recording.recordingKey to resolved
        }
}
