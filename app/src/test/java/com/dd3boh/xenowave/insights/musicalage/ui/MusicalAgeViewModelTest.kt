package com.dd3boh.xenowave.insights.musicalage.ui

import androidx.room.Room
import com.dd3boh.xenowave.db.InternalDatabase
import com.dd3boh.xenowave.db.entities.Event
import com.dd3boh.xenowave.db.entities.ArtistEntity
import com.dd3boh.xenowave.db.entities.SongEntity
import com.dd3boh.xenowave.db.entities.SongArtistMap
import com.dd3boh.xenowave.insights.musicalage.ContentEvidenceState
import com.dd3boh.xenowave.insights.musicalage.InsufficientMetadataResult
import com.dd3boh.xenowave.insights.musicalage.MusicalAgeClock
import com.dd3boh.xenowave.insights.musicalage.MusicalAgeEngine
import com.dd3boh.xenowave.insights.musicalage.MusicalAgePolicy
import com.dd3boh.xenowave.insights.musicalage.MusicalAgeV2
import com.dd3boh.xenowave.insights.musicalage.RecordingVersion
import com.dd3boh.xenowave.insights.musicalage.ReadyResult
import com.dd3boh.xenowave.insights.musicalage.YearEvidence
import com.dd3boh.xenowave.insights.musicalage.YearEvidencePrecision
import com.dd3boh.xenowave.insights.musicalage.YearEvidenceSource
import com.dd3boh.xenowave.insights.musicalage.YearEvidenceState
import com.dd3boh.xenowave.insights.musicalage.content.ManualEvidence
import com.dd3boh.xenowave.insights.musicalage.content.SongMetadata
import com.dd3boh.xenowave.insights.musicalage.data.ManualEvidenceRepository
import com.dd3boh.xenowave.insights.musicalage.data.MusicalAgeRepository
import com.dd3boh.xenowave.insights.musicalage.data.RoomSongMetadataSource
import com.dd3boh.xenowave.insights.musicalage.metadata.InMemoryRecordingEvidenceCache
import com.dd3boh.xenowave.insights.musicalage.metadata.CacheLookup
import com.dd3boh.xenowave.insights.musicalage.metadata.MetadataRequest
import com.dd3boh.xenowave.insights.musicalage.metadata.MetadataResponse
import com.dd3boh.xenowave.insights.musicalage.metadata.MetadataTransport
import com.dd3boh.xenowave.insights.musicalage.metadata.MusicBrainzEnrichmentService
import com.dd3boh.xenowave.insights.musicalage.metadata.MusicBrainzPolicy
import com.dd3boh.xenowave.insights.musicalage.metadata.MusicBrainzProviderCooldown
import com.dd3boh.xenowave.insights.musicalage.metadata.MusicBrainzProviderGate
import com.dd3boh.xenowave.insights.musicalage.metadata.CacheEntryKind
import com.dd3boh.xenowave.insights.musicalage.metadata.RecordingEvidenceCacheKey
import com.dd3boh.xenowave.insights.musicalage.metadata.RecordingEvidenceCache
import com.dd3boh.xenowave.insights.musicalage.metadata.RecordingEvidenceCacheEntry
import com.dd3boh.xenowave.insights.musicalage.metadata.RequestRateLimiter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
class MusicalAgeViewModelTest {
    private lateinit var database: InternalDatabase
    private lateinit var manual: ManualEvidenceRepository
    private lateinit var directory: java.io.File
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val asOf = LocalDateTime.of(2026, 11, 6, 12, 0)
    private val requests = java.util.concurrent.CopyOnWriteArrayList<MetadataRequest>()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            InternalDatabase::class.java,
        ).allowMainThreadQueries().build()
        directory = java.io.File(
            System.getProperty("java.io.tmpdir"),
            "musical-age-viewmodel-${System.nanoTime()}",
        ).apply { mkdirs() }
        manual = ManualEvidenceRepository(directory)
    }

    @After
    fun tearDown() {
        database.close()
        directory.deleteRecursively()
    }

    @Test
    fun `opening calculates locally and never opens network without consent`() {
        seedReadyHistory()
        val viewModel = viewModel()

        await { viewModel.state.value.result is ReadyResult }

        assertNotNull(viewModel.state.value.result)
        assertEquals(0, requests.size)

        viewModel.refresh()
        await { !viewModel.state.value.loading }
        assertEquals(0, requests.size)
    }

    @Test
    fun `consult without selection does not request and selects at most twenty`() {
        seedReadyHistory()
        val viewModel = viewModel()
        await { viewModel.state.value.result is ReadyResult }
        await { viewModel.state.value.tracks.isNotEmpty() }

        viewModel.consultSelected()
        assertEquals(MusicalAgeMessage.SELECTION_REQUIRED, viewModel.state.value.message)
        assertEquals(0, requests.size)

        viewModel.state.value.tracks.take(20).forEach {
            viewModel.toggleSelection(it.songId)
        }
        viewModel.toggleSelection("another-song")
        assertEquals(20, viewModel.state.value.selected.size)
        assertEquals(MusicalAgeMessage.SELECTION_LIMIT, viewModel.state.value.message)
    }

    @Test
    fun `explicit consent performs one batch and cancellation stops publication`() {
        seedReadyHistory()
        val viewModel = viewModel()
        await { viewModel.state.value.result is ReadyResult }
        await { viewModel.state.value.tracks.isNotEmpty() }
        viewModel.state.value.tracks.take(2).forEach {
            viewModel.toggleSelection(it.songId)
        }

        viewModel.consultSelected()
        await { requests.isNotEmpty() && !viewModel.state.value.enrichmentLoading }

        assertEquals(2, requests.size)
        val beforeClear = requests.size
        viewModel.clearTechnicalCache()
        await { !viewModel.state.value.loading }
        assertEquals(beforeClear, requests.size)
    }

    @Test
    fun `cancelling during a suspended lookup prevents late publication and cache write`() {
        seedReadyHistory()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cache = RecordingCache()
        val viewModel = viewModel(
            transport = MetadataTransport { request, _, _ ->
                requests += request
                entered.complete(Unit)
                release.await()
                MetadataResponse(404)
            },
            cache = cache,
        )
        await { viewModel.state.value.result is ReadyResult }
        await { viewModel.state.value.tracks.isNotEmpty() }
        viewModel.state.value.tracks.take(2).forEach {
            viewModel.toggleSelection(it.songId)
        }

        viewModel.consultSelected()
        await { entered.isCompleted }
        viewModel.clearSelection()
        release.complete(Unit)
        await { !viewModel.state.value.enrichmentLoading }
        await { viewModel.state.value.cancelled }

        assertNull(viewModel.state.value.enrichment)
        assertTrue(viewModel.state.value.selected.isEmpty())
        assertTrue(cache.puts.isEmpty())
        assertEquals(1, requests.size)
    }

    @Test
    fun `selection change during a lookup cancels it before the next page`() {
        seedReadyHistory()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cache = RecordingCache()
        val viewModel = viewModel(
            transport = MetadataTransport { request, _, _ ->
                requests += request
                entered.complete(Unit)
                release.await()
                MetadataResponse(404)
            },
            cache = cache,
        )
        await { viewModel.state.value.result is ReadyResult }
        await { viewModel.state.value.tracks.isNotEmpty() }
        val selected = viewModel.state.value.tracks.take(2)
        selected.forEach { viewModel.toggleSelection(it.songId) }

        viewModel.consultSelected()
        await { entered.isCompleted }
        viewModel.toggleSelection(selected.first().songId)
        release.complete(Unit)
        await { !viewModel.state.value.enrichmentLoading }

        assertNull(viewModel.state.value.enrichment)
        assertTrue(cache.puts.isEmpty())
        assertEquals(1, requests.size)
    }

    @Test
    fun `host stop cancels a suspended lookup without publishing`() {
        seedReadyHistory()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cache = RecordingCache()
        val viewModel = viewModel(
            transport = MetadataTransport { request, _, _ ->
                requests += request
                entered.complete(Unit)
                release.await()
                MetadataResponse(404)
            },
            cache = cache,
        )
        await { viewModel.state.value.result is ReadyResult }
        await { viewModel.state.value.tracks.isNotEmpty() }
        viewModel.state.value.tracks.take(2).forEach {
            viewModel.toggleSelection(it.songId)
        }

        viewModel.consultSelected()
        await { entered.isCompleted }
        viewModel.onHostStopped()
        release.complete(Unit)
        await { !viewModel.state.value.enrichmentLoading }

        assertNull(viewModel.state.value.enrichment)
        assertTrue(viewModel.state.value.cancelled)
        assertTrue(cache.puts.isEmpty())
        assertEquals(1, requests.size)
    }

    @Test
    fun `clearing technical cache invalidates the visible result immediately`() {
        seedReadyHistory()
        val viewModel = viewModel()
        await { viewModel.state.value.result is ReadyResult }

        viewModel.clearTechnicalCache()

        assertNull(viewModel.state.value.result)
        await { !viewModel.state.value.loading }
    }

    @Test
    fun `export token is invalidated by cache clearing and by a new result`() {
        seedReadyHistory()
        val viewModel = viewModel()
        await { viewModel.state.value.result is ReadyResult }

        val token = viewModel.createExportToken()
        assertNotNull(token)
        assertTrue(viewModel.isExportTokenCurrent(token!!))

        viewModel.refresh()
        await { !viewModel.state.value.loading && viewModel.state.value.result is ReadyResult }
        assertFalse(viewModel.isExportTokenCurrent(token))

        val replacement = viewModel.createExportToken()
        assertNotNull(replacement)
        viewModel.clearTechnicalCache()
        assertFalse(viewModel.isExportTokenCurrent(replacement!!))
    }

    @Test
    fun `valid local cache produces a result with zero requests`() {
        seedReadyHistory(withManualYears = false)
        val cache = InMemoryRecordingEvidenceCache()
        for (track in 0 until 20) {
            val songId = "song-$track"
            val key = RecordingEvidenceCacheKey.forMetadata(
                SongMetadata(
                    songId = songId,
                    title = "fixture",
                    artists = listOf("Fixture Artist"),
                    durationMillis = 240_000L,
                ),
                MusicalAgeV2.MATCHING_POLICY_VERSION,
            )
            cache.put(
                RecordingEvidenceCacheEntry(
                    key = key,
                    kind = CacheEntryKind.CONFIRMED,
                    evidence = listOf(
                        YearEvidence(
                            recordingKey = "local:$songId",
                            year = 2000,
                            state = YearEvidenceState.ACCEPTED,
                            origin = "fixture",
                            precision = YearEvidencePrecision.YEAR_ONLY,
                            resolvedVersion = "unqualified",
                            source = YearEvidenceSource.CATALOG,
                            acceptanceRule = "fixture",
                        )
                    ),
                    storedAt = Instant.parse("2026-11-06T12:00:00Z"),
                    expiresAt = Instant.parse("2026-11-07T12:00:00Z"),
                )
            )
        }
        val viewModel = viewModel(cache = cache)

        await { viewModel.state.value.result is ReadyResult }

        assertEquals(0, requests.size)
    }

    @Test
    fun `successful lookup feeds year evidence into the local result`() {
        seedReadyHistory(withManualYears = false)
        val viewModel = viewModel(
            transport = MetadataTransport { request, _, _ ->
                requests += request
                MetadataResponse(200, responseJson())
            },
        )
        await { viewModel.state.value.result is InsufficientMetadataResult }
        await { viewModel.state.value.tracks.isNotEmpty() }
        viewModel.state.value.tracks.take(20).forEach {
            viewModel.toggleSelection(it.songId)
        }

        viewModel.consultSelected()

        await {
            !viewModel.state.value.enrichmentLoading &&
                (viewModel.state.value.result?.diagnostic?.acceptedYearRecordings ?: 0) > 0
        }
        assertTrue(
            viewModel.state.value.result?.diagnostic?.acceptedYears.orEmpty().contains(2000)
        )
        assertEquals(20, requests.size)
    }

    private fun viewModel(
        transport: MetadataTransport = MetadataTransport { request, _, _ ->
            requests += request
            MetadataResponse(404)
        },
        cache: RecordingEvidenceCache = InMemoryRecordingEvidenceCache(),
    ): MusicalAgeViewModel {
        val engine = MusicalAgeEngine(
            policy = MusicalAgePolicy(),
            clock = MusicalAgeClock(
                Clock.fixed(asOf.atZone(zone).toInstant(), zone),
                zone,
            ),
        )
        val repository = MusicalAgeRepository(
            dao = database.musicalAgeDao,
            engine = engine,
        )
        val source = RoomSongMetadataSource(database.musicalAgeDao)
        val service = MusicBrainzEnrichmentService(
            transport = transport,
            metadataSource = source,
            cache = cache,
            policy = MusicBrainzPolicy(),
            clock = Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"), ZoneId.of("UTC")),
            rateLimiter = RequestRateLimiter { },
            jitterMillis = { 0L },
            providerGate = MusicBrainzProviderGate(),
            providerCooldown = MusicBrainzProviderCooldown(),
        )
        return MusicalAgeViewModel(
            context = RuntimeEnvironment.getApplication(),
            repository = repository,
            enrichmentService = service,
            manualEvidenceRepository = manual,
            evidenceCache = cache,
        )
    }

    private class RecordingCache(
        private val delegate: RecordingEvidenceCache = InMemoryRecordingEvidenceCache(),
    ) : RecordingEvidenceCache {
        val puts = CopyOnWriteArrayList<RecordingEvidenceCacheEntry>()

        override fun get(key: String, now: Instant): CacheLookup = delegate.get(key, now)

        override fun put(entry: RecordingEvidenceCacheEntry): Boolean {
            puts += entry
            return delegate.put(entry)
        }

        override fun put(
            entry: RecordingEvidenceCacheEntry,
            expectedGeneration: Long,
        ): Boolean {
            puts += entry
            return delegate.put(entry, expectedGeneration)
        }

        override fun clear(): Boolean = delegate.clear()

        override fun generation(): Long = delegate.generation()
    }

    private fun seedReadyHistory(withManualYears: Boolean = true) {
        runBlocking {
            database.dao.insert(ArtistEntity(id = "artist-1", name = "Fixture Artist"))
            for (track in 0 until 20) {
                val songId = "song-$track"
                database.dao.insert(
                    SongEntity(
                        id = songId,
                        title = "fixture",
                        duration = 240,
                        localPath = null,
                        year = null,
                    )
                )
                database.dao.insert(SongArtistMap(songId, "artist-1", 0))
                for (day in 1..3) {
                    database.dao.insert(
                        Event(
                            songId = songId,
                            timestamp = LocalDateTime.of(2026, 11, day, 12, 0),
                            playTime = 4 * 60_000L,
                        )
                    )
                }
                manual.put(
                    ManualEvidence(
                        songId = songId,
                        contentState = ContentEvidenceState.CONFIRMED_MUSIC,
                        chosenAt = Instant.parse("2026-11-06T12:00:00Z"),
                    )
                )
                if (withManualYears) {
                    manual.put(
                        ManualEvidence(
                            songId = songId,
                            recordingKey = "local:$songId",
                            year = 2000,
                            precision = YearEvidencePrecision.YEAR_ONLY,
                            resolvedVersion = RecordingVersion.STUDIO,
                            chosenAt = Instant.parse("2026-11-06T12:00:00Z"),
                        )
                    )
                }
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        repeat(400) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(25)
        }
        assertTrue("condition not reached", condition())
    }

    private fun responseJson(): String = """
        {
          "count": 1,
          "recordings": [
            {
              "id": "11111111-1111-1111-1111-111111111111",
              "score": 100,
              "title": "fixture",
              "length": 241000,
              "first-release-date": "2000-05-10",
              "artist-credit": [{"name": "Fixture Artist", "artist": {"name": "Fixture Artist"}}],
              "isrcs": ["BRAAA0000001"],
              "releases": [{
                "id": "22222222-2222-2222-2222-222222222222",
                "title": "Album",
                "date": "2000-05-10",
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
}
