package com.dd3boh.xenowave.insights.musicalage.data

import androidx.room.Room
import com.dd3boh.xenowave.db.InternalDatabase
import com.dd3boh.xenowave.db.entities.Event
import com.dd3boh.xenowave.db.entities.ArtistEntity
import com.dd3boh.xenowave.db.entities.SongArtistMap
import com.dd3boh.xenowave.db.entities.SongEntity
import com.dd3boh.xenowave.insights.musicalage.InsufficientMetadataResult
import com.dd3boh.xenowave.insights.musicalage.LocalOnlyRecordingYearResolver
import com.dd3boh.xenowave.insights.musicalage.MusicalAgeClock
import com.dd3boh.xenowave.insights.musicalage.MusicalAgeDataOrigin
import com.dd3boh.xenowave.insights.musicalage.MusicalAgeEngine
import com.dd3boh.xenowave.insights.musicalage.MusicalAgePolicy
import com.dd3boh.xenowave.insights.musicalage.MusicalAgeStatus
import com.dd3boh.xenowave.insights.musicalage.NoHistoryResult
import com.dd3boh.xenowave.insights.musicalage.RecordingYearResolver
import com.dd3boh.xenowave.insights.musicalage.ReadyResult
import com.dd3boh.xenowave.insights.musicalage.YearEvidence
import com.dd3boh.xenowave.insights.musicalage.YearEvidencePrecision
import com.dd3boh.xenowave.insights.musicalage.YearEvidenceState
import com.dd3boh.xenowave.insights.musicalage.content.ContentClassifier
import com.dd3boh.xenowave.insights.musicalage.content.ContentEvidence
import com.dd3boh.xenowave.insights.musicalage.content.UnknownContentClassifier
import com.dd3boh.xenowave.insights.musicalage.ContentEvidenceSource
import com.dd3boh.xenowave.insights.musicalage.ContentEvidenceState
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class MusicalAgeRepositoryTest {
    private lateinit var database: InternalDatabase
    private lateinit var repository: MusicalAgeRepository
    private lateinit var engine: MusicalAgeEngine

    private val zone = ZoneId.of("America/Sao_Paulo")
    private val asOf = LocalDateTime.of(2026, 11, 6, 12, 0)

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            InternalDatabase::class.java,
        ).allowMainThreadQueries().build()
        engine = MusicalAgeEngine(
            policy = MusicalAgePolicy(),
            clock = MusicalAgeClock(
                Clock.fixed(asOf.atZone(zone).toInstant(), zone),
                zone,
            ),
        )
        repository = MusicalAgeRepository(
            dao = database.musicalAgeDao,
            engine = engine,
            contentClassifier = musicClassifier(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `reads only the inclusive period`() = runBlocking {
        insertSong("song-boundary", year = 2000, durationSeconds = 240)
        val start = LocalDateTime.of(2026, 8, 9, 0, 0)
        insertEvent("song-boundary", start, 240_000L)
        insertEvent("song-boundary", start.minusNanos(1_000_000L), 240_000L)
        insertEvent("song-boundary", asOf, 240_000L)
        insertEvent("song-boundary", asOf.plusNanos(1_000_000L), 240_000L)

        val result = repository.diagnose(
            resolver = acceptedResolver(),
            dataOrigin = MusicalAgeDataOrigin.SYNTHETIC_FIXTURE,
        )

        // The Room read is bounded by the period, so out-of-period rows are
        // never loaded and are not counted as discarded by this source.
        assertEquals(2, result.diagnostic.totalEventsRead)
        assertEquals(2, result.diagnostic.eligibleEvents)
        assertEquals(0, result.diagnostic.beforePeriodEventsIgnored)
        assertEquals(0, result.diagnostic.futureEventsIgnored)
        assertEquals(LocalDateTime.of(2026, 8, 9, 0, 0), result.diagnostic.periodStart)
        assertEquals(asOf, result.diagnostic.periodEnd)
    }

    @Test
    fun `song year remains unverified without proven provenance`() = runBlocking {
        insertFixtureHistory(songCount = 20, year = 2000)

        val result = repository.diagnose(
            resolver = LocalOnlyRecordingYearResolver(),
            dataOrigin = MusicalAgeDataOrigin.SYNTHETIC_FIXTURE,
        )

        assertTrue(result is InsufficientMetadataResult)
        assertEquals(20, result.diagnostic.recordingsWithLocalYearHint)
        assertEquals(20, result.diagnostic.recordingsWithYearHintButUnverified)
        assertEquals(20, result.diagnostic.unverifiedYearRecordings)
        assertEquals(0, result.diagnostic.acceptedYearRecordings)
    }

    @Test
    fun `unknown content stays in the denominator and out of the numerator`() = runBlocking {
        insertFixtureHistory(songCount = 20, year = 2000)
        val unknownRepository = MusicalAgeRepository(
            dao = database.musicalAgeDao,
            engine = engine,
            contentClassifier = UnknownContentClassifier(),
        )

        val result = unknownRepository.diagnose(
            resolver = acceptedResolver(),
            dataOrigin = MusicalAgeDataOrigin.SYNTHETIC_FIXTURE,
        )

        assertTrue(result is InsufficientMetadataResult)
        assertEquals(20, result.diagnostic.distinctRecordings)
        assertEquals(20, result.diagnostic.unknownContentRecordings)
        assertEquals(0, result.diagnostic.acceptedYearRecordings)
        assertEquals(0.0, result.diagnostic.coverage, 0.0)
    }

    @Test
    fun `confirmed non music is excluded from eligible history`() = runBlocking {
        insertFixtureHistory(songCount = 20, year = 2000)
        val nonMusicRepository = MusicalAgeRepository(
            dao = database.musicalAgeDao,
            engine = engine,
            contentClassifier = contentClassifier(ContentEvidenceState.CONFIRMED_NON_MUSIC),
        )

        val result = nonMusicRepository.diagnose(
            resolver = acceptedResolver(),
            dataOrigin = MusicalAgeDataOrigin.SYNTHETIC_FIXTURE,
        )

        assertTrue(result is NoHistoryResult)
        assertEquals(60, result.diagnostic.nonMusicEvents)
        assertEquals(0, result.diagnostic.eligibleEvents)
    }

    @Test
    fun `clearing event history invalidates the ready result`() = runBlocking {
        insertFixtureHistory(songCount = 20, year = 2000)

        val beforeClear = repository.diagnose(
            resolver = acceptedResolver(),
            dataOrigin = MusicalAgeDataOrigin.SYNTHETIC_FIXTURE,
        )
        assertTrue(beforeClear is ReadyResult)

        database.dao.clearListenHistory()

        val afterClear = repository.diagnose(
            resolver = acceptedResolver(),
            dataOrigin = MusicalAgeDataOrigin.SYNTHETIC_FIXTURE,
        )
        assertTrue(afterClear is NoHistoryResult)
        assertEquals(0, afterClear.diagnostic.eligibleEvents)
    }

    @Test
    fun `diagnose does not write history or change songs`() = runBlocking {
        insertFixtureHistory(songCount = 20, year = 2000)
        val eventsBefore = database.dao.events().first()
        val songBefore = database.dao.song("song-0").first()

        repository.diagnose(
            resolver = acceptedResolver(),
            dataOrigin = MusicalAgeDataOrigin.SYNTHETIC_FIXTURE,
        )

        val eventsAfter = database.dao.events().first()
        val songAfter = database.dao.song("song-0").first()
        assertEquals(eventsBefore.size, eventsAfter.size)
        assertEquals(eventsBefore.map { it.event.id }, eventsAfter.map { it.event.id })
        assertEquals(songBefore, songAfter)
    }

    @Test
    fun `artist projection does not multiply events or weights`() = runBlocking {
        insertSong("song-multi-artist", year = 2000, durationSeconds = 240)
        database.dao.insert(ArtistEntity(id = "artist-a", name = "Artist A"))
        database.dao.insert(ArtistEntity(id = "artist-b", name = "Artist B"))
        database.dao.insert(SongArtistMap("song-multi-artist", "artist-a", 0))
        database.dao.insert(SongArtistMap("song-multi-artist", "artist-b", 1))
        for (day in 1..3) {
            insertEvent(
                songId = "song-multi-artist",
                timestamp = LocalDateTime.of(2026, 11, day, 12, 0),
                playTimeMillis = 4 * 60_000L,
            )
        }

        val result = repository.diagnose(
            resolver = acceptedResolver(),
            dataOrigin = MusicalAgeDataOrigin.SYNTHETIC_FIXTURE,
        )

        assertEquals(3, result.diagnostic.eligibleEvents)
        assertEquals(1, result.diagnostic.distinctRecordings)
        assertEquals(12.0, result.diagnostic.weightedMinutes, 0.0)
    }

    @Test
    fun `ready result carries the fixture counts and no private titles`() = runBlocking {
        insertFixtureHistory(songCount = 20, year = 2000)
        val result = repository.diagnose(
            resolver = acceptedResolver(),
            dataOrigin = MusicalAgeDataOrigin.SYNTHETIC_FIXTURE,
        )

        val ready = result as ReadyResult
        assertEquals(MusicalAgeStatus.READY, ready.status)
        assertEquals(44, ready.musicalAge)
        assertEquals(20, ready.eligibleRecordings)
        assertEquals(3, ready.activeDays)
        assertEquals(240.0, ready.weightedMinutes, 0.0)
        assertEquals(MusicalAgeDataOrigin.SYNTHETIC_FIXTURE, ready.diagnostic.dataOrigin)
        assertNotNull(ready.diagnostic.acceptedYears)
        assertTrue(ready.diagnostic.acceptedYears.all { it in 1850..2026 })
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    fun `repository reads the DAO on the injected dispatcher`() = runBlocking {
        val dispatcher = newSingleThreadContext("musical-age-test-io")
        try {
            val dao = object : MusicalAgeDao {
                override suspend fun listeningRowsBetween(
                    fromInclusive: LocalDateTime,
                    toInclusive: LocalDateTime,
                ): List<MusicalAgeListeningRow> {
                    assertTrue(
                        Thread.currentThread().name.startsWith("musical-age-test-io")
                    )
                    return emptyList()
                }

                override suspend fun songMetadata(songIds: List<String>): List<MusicalAgeSongRow> =
                    emptyList()

                override suspend fun songArtists(songIds: List<String>): List<MusicalAgeArtistRow> =
                    emptyList()

                override suspend fun eligibleSongsBetween(
                    fromInclusive: LocalDateTime,
                    toInclusive: LocalDateTime,
                    limit: Int,
                    offset: Int,
                ): List<MusicalAgeEligibleSongRow> = emptyList()

                override suspend fun eligibleSongCountBetween(
                    fromInclusive: LocalDateTime,
                    toInclusive: LocalDateTime,
                ): Int = 0

                override suspend fun songIdsBetween(
                    fromInclusive: LocalDateTime,
                    toInclusive: LocalDateTime,
                ): List<String> = emptyList()
            }
            val engine = MusicalAgeEngine(
                policy = MusicalAgePolicy(),
                clock = MusicalAgeClock(
                    Clock.fixed(asOf.atZone(zone).toInstant(), zone),
                    zone,
                ),
            )
            val result = MusicalAgeRepository(
                dao = dao,
                engine = engine,
                ioDispatcher = dispatcher,
            ).diagnose(
                resolver = LocalOnlyRecordingYearResolver(),
                dataOrigin = MusicalAgeDataOrigin.SYNTHETIC_FIXTURE,
            )

            assertTrue(result is NoHistoryResult)
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `history invalidation during read prevents a stale result`() {
        insertFixtureHistory(songCount = 20, year = 2000)
        val generation = InMemoryHistoryGeneration()
        val invalidatingDao = object : MusicalAgeDao by database.musicalAgeDao {
            override suspend fun listeningRowsBetween(
                fromInclusive: LocalDateTime,
                toInclusive: LocalDateTime,
            ): List<MusicalAgeListeningRow> {
                val rows = database.musicalAgeDao.listeningRowsBetween(
                    fromInclusive,
                    toInclusive,
                )
                generation.invalidate()
                return rows
            }
        }
        val invalidatingRepository = MusicalAgeRepository(
            dao = invalidatingDao,
            engine = engine,
            contentClassifier = musicClassifier(),
            historyGeneration = generation,
        )

        assertThrows(HistoryInvalidatedException::class.java) {
            runBlocking {
                invalidatingRepository.diagnose(
                    resolver = acceptedResolver(),
                    dataOrigin = MusicalAgeDataOrigin.SYNTHETIC_FIXTURE,
                )
            }
        }
    }

    @Test
    fun `resolver failure becomes a transient metadata state without crashing`() = runBlocking {
        insertFixtureHistory(songCount = 20, year = 2000)

        val result = repository.diagnose(
            resolver = RecordingYearResolver { throw IOException("offline fixture") },
            dataOrigin = MusicalAgeDataOrigin.SYNTHETIC_FIXTURE,
        )

        assertTrue(result is InsufficientMetadataResult)
        assertEquals(20, result.diagnostic.yearTransientErrorRecordings)
        assertEquals(0, result.diagnostic.acceptedYearRecordings)
    }

    @Test
    fun `more than 999 distinct song ids are queried in safe chunks`() = runBlocking {
        repeat(1_100) { index ->
            insertSong("song-$index", year = 2000, durationSeconds = 240)
            insertEvent(
                songId = "song-$index",
                timestamp = LocalDateTime.of(2026, 11, 1, 12, 0),
                playTimeMillis = 4 * 60_000L,
            )
        }

        val result = repository.diagnose(
            resolver = acceptedResolver(),
            dataOrigin = MusicalAgeDataOrigin.SYNTHETIC_FIXTURE,
        )

        assertEquals(1_100, result.diagnostic.totalEventsRead)
        assertEquals(1_100, result.diagnostic.distinctRecordings)
    }

    @Test
    fun `track review is bounded and paginated`() = runBlocking {
        insertFixtureHistory(songCount = 5, year = 2000)

        val first = repository.tracks(limit = 2, offset = 0)
        val second = repository.tracks(limit = 2, offset = 2)

        assertEquals(5, first.total)
        assertEquals(2, first.items.size)
        assertEquals(2, second.items.size)
        assertTrue(first.items.map { it.songId }.none { it in second.items.map { t -> t.songId } })
    }

    private fun insertFixtureHistory(songCount: Int, year: Int?) {
        for (track in 0 until songCount) {
            insertSong("song-$track", year = year, durationSeconds = 240)
            for (day in 1..3) {
                insertEvent(
                    songId = "song-$track",
                    timestamp = LocalDateTime.of(2026, 11, day, 12, 0),
                    playTimeMillis = 4 * 60_000L,
                )
            }
        }
    }

    private fun insertSong(songId: String, year: Int?, durationSeconds: Int) {
        database.dao.insert(
            SongEntity(
                id = songId,
                title = "fixture",
                duration = durationSeconds,
                localPath = null,
                year = year,
            )
        )
    }

    private fun insertEvent(
        songId: String,
        timestamp: LocalDateTime,
        playTimeMillis: Long,
    ) {
        database.dao.insert(
            Event(
                songId = songId,
                timestamp = timestamp,
                playTime = playTimeMillis,
            )
        )
    }

    private fun acceptedResolver(): RecordingYearResolver =
        RecordingYearResolver { recordings ->
            recordings.associate { recording ->
                recording.recordingKey to listOf(
                    YearEvidence(
                        recordingKey = recording.recordingKey,
                        year = 2000,
                        state = YearEvidenceState.ACCEPTED,
                        origin = "fixture",
                        precision = YearEvidencePrecision.RECORDING_RELEASE_YEAR,
                        resolvedVersion = "studio-original",
                        source = com.dd3boh.xenowave.insights.musicalage.YearEvidenceSource.CATALOG,
                        acceptanceRule = "fixture-v1",
                    )
                )
            }
        }

    private fun musicClassifier(): ContentClassifier =
        contentClassifier(ContentEvidenceState.CONFIRMED_MUSIC)

    private fun contentClassifier(state: ContentEvidenceState): ContentClassifier =
        ContentClassifier { songs ->
            songs.associate { song ->
                song.songId to ContentEvidence(
                    songId = song.songId,
                    state = state,
                    source = ContentEvidenceSource.USER_CONFIRMED,
                    reason = "fixture confirmation",
                )
            }
        }
}
