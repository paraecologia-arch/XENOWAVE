package com.dd3boh.xenowave.insights.musicalage.metadata

import com.dd3boh.xenowave.insights.musicalage.ContentEvidenceState
import com.dd3boh.xenowave.insights.musicalage.RecordingIdentity
import com.dd3boh.xenowave.insights.musicalage.RecordingVersion
import com.dd3boh.xenowave.insights.musicalage.YearEvidence
import com.dd3boh.xenowave.insights.musicalage.YearEvidencePrecision
import com.dd3boh.xenowave.insights.musicalage.YearEvidenceSource
import com.dd3boh.xenowave.insights.musicalage.YearEvidenceState
import com.dd3boh.xenowave.insights.musicalage.content.InMemoryManualEvidenceStore
import com.dd3boh.xenowave.insights.musicalage.content.ManualEvidence
import com.dd3boh.xenowave.insights.musicalage.content.SongMetadata
import com.dd3boh.xenowave.insights.musicalage.data.InMemoryHistoryGeneration
import com.dd3boh.xenowave.insights.musicalage.data.SongMetadataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

class MusicBrainzEnrichmentServiceTest {
    private val now = Instant.parse("2026-11-06T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val policy = MusicBrainzPolicy(
        maxRecordingsPerRun = 20,
        maxRequestsPerRun = 40,
        maxCandidatesPerSearch = 10,
        backoffBaseMillis = 0L,
        backoffMaxMillis = 0L,
        totalBudgetMillis = 120_000L,
    )
    private val authorization = MetadataLookupAuthorization(
        provider = MetadataProvider.MUSICBRAINZ,
        approvedSongIds = setOf("song-1"),
        purpose = "teste sintético",
        approvedAt = now,
    )
    private val song = SongMetadata(
        songId = "song-1",
        title = "Fixture Song",
        artists = listOf("Fixture Artist"),
        durationMillis = 240_000L,
    )

    @Test
    fun `missing or revoked authorization performs zero requests`() = runBlocking {
        val transport = FakeTransport { MetadataResponse(200, responseJson()) }
        val service = service(transport)

        val none = service.enrich(null, listOf("song-1"))
        assertEquals(EnrichmentStopReason.NOT_AUTHORIZED, none.stoppedReason)
        assertEquals(0, transport.requests.size)

        val empty = service.enrich(
            authorization.copy(approvedSongIds = emptySet()),
            listOf("song-1"),
        )
        assertEquals(EnrichmentStopReason.NOT_AUTHORIZED, empty.stoppedReason)
        assertEquals(0, transport.requests.size)

        val unapproved = service.enrich(authorization, listOf("song-2"))
        assertEquals(EnrichmentStopReason.EMPTY_SELECTION, unapproved.stoppedReason)
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun `authorized lookup accepts a public catalog match and caches it`() = runBlocking {
        val transport = FakeTransport { MetadataResponse(200, responseJson()) }
        val service = service(transport)

        val first = service.enrich(authorization, listOf("song-1"))
        val evidence = first.evidence.getValue("song-1").single()
        assertEquals(YearEvidenceState.ACCEPTED, evidence.state)
        assertEquals(1993, evidence.year)
        assertEquals(1, first.diagnostic.requestsAttempted)
        assertEquals(1, first.diagnostic.cacheMisses)
        assertEquals(1, transport.requests.size)
        assertFalse(transport.requests.single().url.contains("song-1"))
        assertFalse(transport.requests.single().url.contains("2026"))

        val second = service.enrich(authorization, listOf("song-1"))
        assertEquals(1, second.diagnostic.cacheHits)
        assertEquals(0, second.diagnostic.requestsAttempted)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `404 is cached as absence`() = runBlocking {
        val transport = FakeTransport {
            MetadataResponse(statusCode = 404)
        }
        val service = service(transport)

        val first = service.enrich(authorization, listOf("song-1"))
        assertEquals(YearEvidenceState.MISSING, first.evidence.getValue("song-1").single().state)
        val second = service.enrich(authorization, listOf("song-1"))
        assertEquals(1, second.diagnostic.cacheHits)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `retry after beyond budget is deferred without shortening the wait`() = runBlocking {
        val transport = FakeTransport {
            MetadataResponse(
                statusCode = 429,
                headers = mapOf("retry-after" to "5"),
            )
        }
        val service = service(
            transport,
            policy.copy(totalBudgetMillis = 1_000L, perRequestTimeoutMillis = 1_000L),
        )

        val result = service.enrich(authorization, listOf("song-1"))

        assertEquals(EnrichmentStopReason.DEFERRED_RETRY_AFTER, result.stoppedReason)
        assertEquals(1, result.diagnostic.requestsDeferred)
        assertEquals(1, transport.requests.size)
        assertEquals(
            YearEvidenceState.TRANSIENT_ERROR,
            result.evidence.getValue("song-1").single().state,
        )
    }

    @Test
    fun `transient server error retries and preserves valid result`() = runBlocking {
        var calls = 0
        val transport = FakeTransport {
            calls++
            if (calls == 1) MetadataResponse(statusCode = 500)
            else MetadataResponse(200, responseJson())
        }
        val service = service(transport)

        val result = service.enrich(authorization, listOf("song-1"))

        assertEquals(EnrichmentStopReason.COMPLETED, result.stoppedReason)
        assertEquals(YearEvidenceState.ACCEPTED, result.evidence.getValue("song-1").single().state)
        assertEquals(2, result.diagnostic.requestsAttempted)
        assertEquals(1, result.diagnostic.retries)
        assertEquals(1, result.diagnostic.requestsFailed)
    }

    @Test
    fun `malformed response becomes a transient error`() = runBlocking {
        val transport = FakeTransport { MetadataResponse(200, "{") }
        val service = service(transport, policy.copy(maxRetries = 0))

        val result = service.enrich(authorization, listOf("song-1"))

        assertEquals(
            YearEvidenceState.TRANSIENT_ERROR,
            result.evidence.getValue("song-1").single().state,
        )
        assertEquals(1, result.diagnostic.requestsFailed)
    }

    @Test
    fun `oversized response becomes a transient error without retrying forever`() = runBlocking {
        val transport = FakeTransport {
            throw MetadataResponseTooLargeException("too large")
        }
        val service = service(transport, policy.copy(maxRetries = 0))

        val result = service.enrich(authorization, listOf("song-1"))

        assertEquals(
            YearEvidenceState.TRANSIENT_ERROR,
            result.evidence.getValue("song-1").single().state,
        )
        assertEquals(1, result.diagnostic.requestsFailed)
    }

    @Test
    fun `one failed track does not discard another valid result`() = runBlocking {
        val other = song.copy(songId = "song-2", title = "Other Song")
        val source = SongMetadataSource {
            mapOf("song-1" to song, "song-2" to other)
        }
        val transport = FakeTransport { request ->
            if (request.url.contains("Other")) {
                MetadataResponse(404)
            } else {
                MetadataResponse(200, responseJson())
            }
        }
        val service = service(transport, metadataSource = source)

        val result = service.enrich(
            authorization.copy(approvedSongIds = setOf("song-1", "song-2")),
            listOf("song-1", "song-2"),
        )

        assertEquals(YearEvidenceState.ACCEPTED, result.evidence.getValue("song-1").single().state)
        assertEquals(YearEvidenceState.MISSING, result.evidence.getValue("song-2").single().state)
        assertEquals(EnrichmentStopReason.COMPLETED, result.stoppedReason)
    }

    @Test
    fun `cancellation propagates from the transport`() {
        val transport = FakeTransport { throw CancellationException("cancel") }
        val service = service(transport)

        assertThrows(CancellationException::class.java) {
            runBlocking { service.enrich(authorization, listOf("song-1")) }
        }
    }

    @Test
    fun `revoking consent during a request prevents publication and cache`() = runBlocking {
        val cache = InMemoryRecordingEvidenceCache()
        val transport = FakeTransport {
            authorization.consent.revoke()
            MetadataResponse(200, responseJson())
        }
        val service = service(transport, cache = cache)

        val result = service.enrich(authorization, listOf("song-1"))

        assertEquals(EnrichmentStopReason.REVOKED, result.stoppedReason)
        assertTrue(result.evidence.isEmpty())
        assertFalse(cache.get(RecordingEvidenceCacheKey.forMetadata(song, "any"), now).hit)
    }

    @Test
    fun `expired evidence is reused and marked stale when refresh fails`() = runBlocking {
        val cache = InMemoryRecordingEvidenceCache()
        val cacheKey = RecordingEvidenceCacheKey.forMetadata(
            song,
            com.dd3boh.xenowave.insights.musicalage.MusicalAgeV2.MATCHING_POLICY_VERSION,
        )
        cache.put(
            RecordingEvidenceCacheEntry(
                key = cacheKey,
                kind = CacheEntryKind.CONFIRMED,
                evidence = listOf(acceptedEvidence(1993)),
                storedAt = now.minusSeconds(60),
                expiresAt = now.minusSeconds(1),
            )
        )
        val service = service(
            transport = FakeTransport { MetadataResponse(statusCode = 500) },
            cache = cache,
            policy = policy.copy(maxRetries = 0),
        )

        val result = service.enrich(authorization, listOf("song-1"))
        val evidence = result.evidence.getValue("song-1").single()

        assertEquals(YearEvidenceState.ACCEPTED, evidence.state)
        assertTrue(evidence.stale)
        assertEquals(1, result.diagnostic.staleEvidenceUsed)
    }

    @Test
    fun `successful refresh renews an expired cache entry`() = runBlocking {
        val cache = InMemoryRecordingEvidenceCache()
        val cacheKey = RecordingEvidenceCacheKey.forMetadata(
            song,
            com.dd3boh.xenowave.insights.musicalage.MusicalAgeV2.MATCHING_POLICY_VERSION,
        )
        cache.put(
            RecordingEvidenceCacheEntry(
                key = cacheKey,
                kind = CacheEntryKind.CONFIRMED,
                evidence = listOf(acceptedEvidence(1993)),
                storedAt = now.minusSeconds(60),
                expiresAt = now.minusSeconds(1),
            )
        )
        val service = service(
            transport = FakeTransport { MetadataResponse(200, responseJson()) },
            cache = cache,
        )

        val result = service.enrich(authorization, listOf("song-1"))

        assertEquals(
            YearEvidenceState.ACCEPTED,
            result.evidence.getValue("song-1").single().state,
        )
        val renewed = cache.get(cacheKey, now.plusSeconds(1))
        assertTrue(renewed.hit)
        assertEquals(1993, renewed.entry?.evidence?.single()?.year)
    }

    @Test
    fun `a negative refresh does not erase confirmed evidence`() = runBlocking {
        val cache = InMemoryRecordingEvidenceCache()
        val cacheKey = RecordingEvidenceCacheKey.forMetadata(
            song,
            com.dd3boh.xenowave.insights.musicalage.MusicalAgeV2.MATCHING_POLICY_VERSION,
        )
        cache.put(
            RecordingEvidenceCacheEntry(
                key = cacheKey,
                kind = CacheEntryKind.CONFIRMED,
                evidence = listOf(acceptedEvidence(1993)),
                storedAt = now.minusSeconds(60),
                expiresAt = now.minusSeconds(1),
            )
        )
        val service = service(
            transport = FakeTransport { MetadataResponse(404) },
            cache = cache,
        )

        val result = service.enrich(authorization, listOf("song-1"))

        val evidence = result.evidence.getValue("song-1")
        assertTrue(evidence.any { it.state == YearEvidenceState.ACCEPTED && it.stale })
        val stored = cache.get(cacheKey, now.plusSeconds(1))
        assertTrue(stored.hit)
        assertTrue(stored.entry?.evidence.orEmpty().any { it.state == YearEvidenceState.ACCEPTED })
    }

    @Test
    fun `revocation during a suspended request publishes and caches nothing`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cache = InMemoryRecordingEvidenceCache()
        val transport = FakeTransport {
            entered.complete(Unit)
            release.await()
            MetadataResponse(200, responseJson())
        }
        val service = service(transport, cache = cache)

        val job = launch(Dispatchers.Default) {
            val result = service.enrich(authorization, listOf("song-1"))
            assertEquals(EnrichmentStopReason.REVOKED, result.stoppedReason)
            assertTrue(result.evidence.isEmpty())
        }
        withTimeout(5_000) { entered.await() }
        authorization.consent.revoke()
        release.complete(Unit)
        job.join()

        val cacheKey = RecordingEvidenceCacheKey.forMetadata(
            song,
            com.dd3boh.xenowave.insights.musicalage.MusicalAgeV2.MATCHING_POLICY_VERSION,
        )
        assertFalse(cache.get(cacheKey, now).hit)
    }

    @Test
    fun `revocation while waiting for the provider slot never sends a request`() = runBlocking {
        val gate = MusicBrainzProviderGate()
        val holderEntered = CompletableDeferred<Unit>()
        val releaseHolder = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            gate.withPermit {
                holderEntered.complete(Unit)
                releaseHolder.await()
            }
        }
        withTimeout(5_000) { holderEntered.await() }

        val transport = FakeTransport { MetadataResponse(200, responseJson()) }
        val service = service(transport, providerGate = gate)
        val job = launch(Dispatchers.Default) {
            val result = service.enrich(authorization, listOf("song-1"))
            assertEquals(EnrichmentStopReason.REVOKED, result.stoppedReason)
        }
        delay(100)
        authorization.consent.revoke()
        releaseHolder.complete(Unit)
        job.join()
        holder.join()

        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `provider queue wait is charged to the total budget`() = runBlocking {
        val gate = MusicBrainzProviderGate()
        val holderEntered = CompletableDeferred<Unit>()
        val releaseHolder = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            gate.withPermit {
                holderEntered.complete(Unit)
                delay(600)
                releaseHolder.complete(Unit)
            }
        }
        withTimeout(5_000) { holderEntered.await() }

        val transport = FakeTransport { MetadataResponse(200, responseJson()) }
        val service = service(
            transport = transport,
            policy = policy.copy(totalBudgetMillis = 250L, perRequestTimeoutMillis = 250L),
            providerGate = gate,
        )

        val result = service.enrich(authorization, listOf("song-1"))

        assertTrue(
            result.stoppedReason == EnrichmentStopReason.DEFERRED_RETRY_AFTER ||
                result.stoppedReason == EnrichmentStopReason.BUDGET_EXHAUSTED
        )
        assertEquals(0, result.diagnostic.requestsAttempted)
        assertTrue(transport.requests.isEmpty())
        withTimeout(5_000) { releaseHolder.await() }
        holder.join()
    }

    @Test
    fun `two instances space real request starts through the shared gate`() = runBlocking {
        val gate = MusicBrainzProviderGate()
        val cooldown = MusicBrainzProviderCooldown()
        val starts = CopyOnWriteArrayList<Long>()
        val limiter = MusicBrainzRateLimiter(minIntervalMillis = 120L)

        fun instance(): MusicBrainzEnrichmentService {
            val transport = FakeTransport {
                starts += System.nanoTime()
                MetadataResponse(404)
            }
            return service(
                transport = transport,
                providerGate = gate,
                providerCooldown = cooldown,
                rateLimiter = limiter,
            )
        }

        awaitAll(
            async(Dispatchers.Default) {
                instance().enrich(authorization, listOf("song-1"))
            },
            async(Dispatchers.Default) {
                instance().enrich(authorization, listOf("song-1"))
            },
        )

        assertEquals(2, starts.size)
        val gapMillis = kotlin.math.abs(starts[1] - starts[0]) / 1_000_000L
        assertTrue("request starts were ${gapMillis}ms apart", gapMillis >= 100L)
    }

    @Test
    fun `partial search keeps the year as a candidate`() = runBlocking {
        val partial = responseJson().replace("\"count\": 1", "\"count\": 2")
        val service = service(FakeTransport { MetadataResponse(200, partial) })

        val result = service.enrich(authorization, listOf("song-1"))

        assertEquals(
            YearEvidenceState.CANDIDATE,
            result.evidence.getValue("song-1").single().state,
        )
    }

    @Test
    fun `one malformed candidate is discarded without failing the batch`() = runBlocking {
        val malformed = """
            {"count": 1, "recordings": [
              {"id": "11111111-1111-1111-1111-111111111111",
               "title": "Fixture Song", "artist-credit": "not-an-array"}
            ]}
        """.trimIndent()
        val service = service(FakeTransport { MetadataResponse(200, malformed) })

        val result = service.enrich(authorization, listOf("song-1"))

        assertEquals(
            YearEvidenceState.MISSING,
            result.evidence.getValue("song-1").single().state,
        )
        assertEquals(1, result.diagnostic.discardedCandidates)
    }

    @Test
    fun `history invalidation during metadata load discards the snapshot`() = runBlocking {
        val generation = InMemoryHistoryGeneration()
        val source = SongMetadataSource {
            generation.invalidate()
            mapOf("song-1" to song)
        }
        val service = service(
            transport = FakeTransport { MetadataResponse(200, responseJson()) },
            historyGeneration = generation,
            metadataSource = source,
        )

        val result = service.enrich(authorization, listOf("song-1"))
        assertEquals(EnrichmentStopReason.HISTORY_INVALIDATED, result.stoppedReason)
        assertTrue(result.evidence.isEmpty())
    }

    @Test
    fun `history invalidation during a request discards the snapshot`() = runBlocking {
        val generation = InMemoryHistoryGeneration()
        val transport = FakeTransport {
            generation.invalidate()
            MetadataResponse(200, responseJson())
        }
        val service = service(transport, historyGeneration = generation)

        val result = service.enrich(authorization, listOf("song-1"))

        assertEquals(EnrichmentStopReason.HISTORY_INVALIDATED, result.stoppedReason)
        assertTrue(result.evidence.isEmpty())
    }

    @Test
    fun `selection is capped at twenty recordings`() = runBlocking {
        val many = (1..25).associate { index ->
            "song-$index" to song.copy(songId = "song-$index")
        }
        val source = SongMetadataSource { ids -> many.filterKeys { it in ids } }
        val transport = FakeTransport { MetadataResponse(404) }
        val manyAuth = authorization.copy(approvedSongIds = many.keys)
        val service = service(transport, metadataSource = source)

        val result = service.enrich(manyAuth, many.keys.toList())

        assertEquals(20, result.diagnostic.selectedSongs)
        assertEquals(20, transport.requests.size)
    }

    @Test
    fun `snapshot resolver gives manual correction explicit priority`() {
        val snapshot = snapshotWith(
            listOf(acceptedEvidence(1993))
        )
        val manual = InMemoryManualEvidenceStore().apply {
            put(
                ManualEvidence(
                    songId = "song-1",
                    contentState = ContentEvidenceState.CONFIRMED_MUSIC,
                    year = 2000,
                    precision = YearEvidencePrecision.YEAR_ONLY,
                    resolvedVersion = RecordingVersion.STUDIO,
                    chosenAt = now,
                )
            )
        }

        val resolved = SnapshotRecordingYearResolver(snapshot, manual).resolve(
            listOf(RecordingIdentity("local:song-1", localSongId = "song-1"))
        ).getValue("local:song-1").single()

        assertEquals(YearEvidenceState.ACCEPTED, resolved.state)
        assertEquals(2000, resolved.year)
        assertEquals(YearEvidenceSource.USER_CONFIRMED, resolved.source)
    }

    @Test
    fun `snapshot resolver preserves manual year conflicts`() {
        val manual = InMemoryManualEvidenceStore().apply {
            put(
                ManualEvidence(
                    songId = "song-1",
                    year = 2000,
                    precision = YearEvidencePrecision.YEAR_ONLY,
                    resolvedVersion = RecordingVersion.STUDIO,
                    chosenAt = now,
                )
            )
            put(
                ManualEvidence(
                    songId = "song-1",
                    year = 2001,
                    precision = YearEvidencePrecision.YEAR_ONLY,
                    resolvedVersion = RecordingVersion.LIVE,
                    chosenAt = now,
                )
            )
        }
        val resolved = SnapshotRecordingYearResolver(snapshotWith(emptyList()), manual).resolve(
            listOf(RecordingIdentity("local:song-1", localSongId = "song-1"))
        ).getValue("local:song-1").single()

        assertEquals(YearEvidenceState.CONFLICT, resolved.state)
        assertEquals(setOf(2000, 2001), resolved.conflictingYears)
    }

    private fun service(
        transport: MetadataTransport,
        policy: MusicBrainzPolicy = this.policy,
        historyGeneration: InMemoryHistoryGeneration = InMemoryHistoryGeneration(),
        metadataSource: SongMetadataSource = SongMetadataSource { mapOf("song-1" to song) },
        cache: RecordingEvidenceCache = InMemoryRecordingEvidenceCache(),
        providerGate: MusicBrainzProviderGate = MusicBrainzProviderGate(),
        providerCooldown: MusicBrainzProviderCooldown = MusicBrainzProviderCooldown(),
        rateLimiter: RequestRateLimiter = RequestRateLimiter { },
    ): MusicBrainzEnrichmentService = MusicBrainzEnrichmentService(
        transport = transport,
        metadataSource = metadataSource,
        cache = cache,
        policy = policy,
        clock = clock,
        rateLimiter = rateLimiter,
        historyGeneration = historyGeneration,
        jitterMillis = { 0L },
        providerGate = providerGate,
        providerCooldown = providerCooldown,
    )

    private fun responseJson(): String = """
        {
          "count": 1,
          "recordings": [
            {
              "id": "11111111-1111-1111-1111-111111111111",
              "score": 100,
              "title": "Fixture Song",
              "length": 241000,
              "first-release-date": "1993-05-10",
              "artist-credit": [{"name": "Fixture Artist", "artist": {"name": "Fixture Artist"}}],
              "isrcs": ["BRAAA0000001"],
              "releases": [{
                "id": "22222222-2222-2222-2222-222222222222",
                "title": "Album",
                "date": "1993-05-10",
                "release-group": {
                  "id": "33333333-3333-3333-3333-333333333333",
                  "primary-type": "Album",
                  "secondary-types": []
                }
              }]
            }
          ]
        }
    """.trimIndent()

    private fun acceptedEvidence(year: Int) = YearEvidence(
        recordingKey = "local:song-1",
        year = year,
        state = YearEvidenceState.ACCEPTED,
        origin = "fixture",
        precision = YearEvidencePrecision.YEAR_ONLY,
        resolvedVersion = "studio",
        source = YearEvidenceSource.CATALOG,
        acceptanceRule = "fixture-v1",
    )

    private fun snapshotWith(evidence: List<YearEvidence>) = EnrichmentSnapshot(
        evidence = if (evidence.isEmpty()) emptyMap() else mapOf("song-1" to evidence),
        diagnostic = EnrichmentDiagnostic(
            provider = MetadataProvider.MUSICBRAINZ,
            requestedSongs = 1,
            authorizedSongs = 1,
            selectedSongs = 1,
            requestsAttempted = 0,
            requestsBlocked = 0,
            requestsDeferred = 0,
            requestsFailed = 0,
            retries = 0,
            cacheHits = 0,
            cacheMisses = 0,
            cacheExpired = 0,
            acceptedYears = evidence.count { it.state == YearEvidenceState.ACCEPTED },
            candidateYears = 0,
            conflictedYears = 0,
            missingYears = 0,
            transientErrors = 0,
            responseBytes = 0L,
            stoppedReason = EnrichmentStopReason.COMPLETED,
            historyGeneration = 0L,
        ),
        generatedAt = now,
        historyGeneration = 0L,
        stoppedReason = EnrichmentStopReason.COMPLETED,
    )

    private class FakeTransport(
        private val handler: suspend (MetadataRequest) -> MetadataResponse,
    ) : MetadataTransport {
        val requests = ArrayList<MetadataRequest>()

        override suspend fun execute(
            request: MetadataRequest,
            maxResponseBytes: Long,
            timeoutMillis: Long,
        ): MetadataResponse {
            requests += request
            return handler(request)
        }
    }
}
