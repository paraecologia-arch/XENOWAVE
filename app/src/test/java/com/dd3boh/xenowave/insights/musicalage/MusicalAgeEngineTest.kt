package com.dd3boh.xenowave.insights.musicalage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class MusicalAgeEngineTest {
    private val policy = MusicalAgePolicy()
    private val asOf = LocalDateTime.of(2026, 11, 6, 12, 0)
    private val zone = ZoneId.of("America/Sao_Paulo")

    private fun engine(): MusicalAgeEngine = MusicalAgeEngine(policy)

    private fun calculate(
        events: List<MusicalAgeEvent>,
        resolver: RecordingYearResolver,
        at: LocalDateTime = asOf,
    ): MusicalAgeResult = engine().calculateAt(
        events = events,
        asOf = at,
        resolver = resolver,
        dataOrigin = MusicalAgeDataOrigin.SYNTHETIC_FIXTURE,
    )

    private fun fixture(
        year: Int = 2000,
        count: Int = 20,
        dayCount: Int = 3,
        kind: ListeningKind = ListeningKind.MUSIC,
    ): List<MusicalAgeEvent> {
        val result = ArrayList<MusicalAgeEvent>()
        var eventId = 1L
        for (track in 0 until count) {
            for (day in 1..dayCount) {
                result += MusicalAgeEvent(
                    eventId = eventId++,
                    songId = "s-$track",
                    recordingKey = "local:s-$track",
                    timestamp = LocalDateTime.of(2026, 11, day, 12, 0),
                    playedMillis = 4 * 60_000L,
                    durationMillis = 4 * 60_000L,
                    kind = kind,
                )
            }
        }
        return result
    }

    private fun accepted(year: Int = 2000): RecordingYearResolver =
        RecordingYearResolver { recordings ->
            recordings.associate { recording ->
                recording.recordingKey to listOf(acceptedEvidence(recording.recordingKey, year))
            }
        }

    private fun acceptedForTrack(yearForTrack: (Int) -> Int): RecordingYearResolver =
        RecordingYearResolver { recordings ->
            recordings.associate { recording ->
                val track = recording.localSongId
                    ?.removePrefix("s-")
                    ?.toIntOrNull()
                    ?: 0
                recording.recordingKey to listOf(
                    acceptedEvidence(recording.recordingKey, yearForTrack(track))
                )
            }
        }

    private fun acceptedEvidence(recordingKey: String, year: Int) = YearEvidence(
        recordingKey = recordingKey,
        year = year,
        state = YearEvidenceState.ACCEPTED,
        origin = "fixture",
        precision = YearEvidencePrecision.RECORDING_RELEASE_YEAR,
        resolvedVersion = "studio-original",
        obtainedAt = Instant.parse("2026-11-06T12:00:00Z"),
        source = YearEvidenceSource.CATALOG,
        acceptanceRule = "fixture-v1",
    )

    private fun stateResolver(
        state: YearEvidenceState,
        year: Int? = 2000,
        resolvedVersion: String? = "studio-original",
    ): RecordingYearResolver = RecordingYearResolver { recordings ->
        recordings.associate { recording ->
            recording.recordingKey to listOf(
                YearEvidence(
                    recordingKey = recording.recordingKey,
                    year = year,
                    state = state,
                    origin = "fixture",
                    precision = YearEvidencePrecision.RECORDING_RELEASE_YEAR,
                    resolvedVersion = resolvedVersion,
                    source = if (state == YearEvidenceState.ACCEPTED) {
                        YearEvidenceSource.CATALOG
                    } else {
                        YearEvidenceSource.NONE
                    },
                    acceptanceRule = if (state == YearEvidenceState.ACCEPTED) {
                        "fixture-v1"
                    } else {
                        null
                    },
                )
            )
        }
    }

    private fun oneEvent(
        durationMillis: Long?,
        playedMillis: Long,
        eventId: Long = 1L,
        songId: String = "one",
    ) = MusicalAgeEvent(
        eventId = eventId,
        songId = songId,
        recordingKey = "local:$songId",
        timestamp = LocalDateTime.of(2026, 11, 1, 12, 0),
        playedMillis = playedMillis,
        durationMillis = durationMillis,
        kind = ListeningKind.MUSIC,
    )

    @Test
    fun `reference 2000 produces 44 years and millennial in 2026`() {
        val result = calculate(fixture(2000), accepted(2000)) as ReadyResult

        assertEquals(MusicalAgeStatus.READY, result.status)
        assertEquals(44, result.musicalAge)
        assertEquals(1982, result.musicalBirthYear)
        assertEquals(2000, result.referenceYear)
        assertEquals(MusicalGeneration.MILLENNIAL, result.generation)
        assertEquals(YearWindowSummary(1998, 2002, 240 * 60_000L, 1.0), result.dominantWindow)
        assertEquals(1.0, result.coverage, 0.0)
        assertEquals(1.0, result.concentration, 0.0)
        assertEquals(1.0, result.margin, 0.0)
        assertEquals(20, result.eligibleRecordings)
        assertEquals(3, result.activeDays)
        assertEquals(240.0, result.weightedMinutes, 0.0)
        assertEquals("musical-age-v1-proposta", result.algorithmVersion)
    }

    @Test
    fun `reference 1993 produces 51 years and generation x`() {
        val result = calculate(fixture(1993), accepted(1993)) as ReadyResult

        assertEquals(51, result.musicalAge)
        assertEquals(1975, result.musicalBirthYear)
        assertEquals(MusicalGeneration.GENERATION_X, result.generation)
    }

    @Test
    fun `reference 1970 produces 74 years and boomer`() {
        val result = calculate(fixture(1970), accepted(1970)) as ReadyResult

        assertEquals(74, result.musicalAge)
        assertEquals(1952, result.musicalBirthYear)
        assertEquals(MusicalGeneration.BOOMER, result.generation)
    }

    @Test
    fun `release 2024 produces 20 years and z without forcing alpha`() {
        val result = calculate(fixture(2024), accepted(2024)) as ReadyResult

        assertEquals(20, result.musicalAge)
        assertEquals(2006, result.musicalBirthYear)
        assertEquals(MusicalGeneration.GENERATION_Z, result.generation)
        assertEquals(2022, result.dominantWindow.fromYear)
        assertEquals(2026, result.dominantWindow.toYear)
    }

    @Test
    fun `release years 1850 and the current year are accepted`() {
        val oldest = calculate(fixture(1850), accepted(1850)) as ReadyResult
        assertEquals(1850, oldest.referenceYear)
        assertEquals(194, oldest.musicalAge)
        assertEquals(MusicalGeneration.CLASSICS, oldest.generation)

        val newest = calculate(fixture(2026), accepted(2026)) as ReadyResult
        assertEquals(2026, newest.referenceYear)
        assertEquals(18, newest.musicalAge)
        assertEquals(MusicalGeneration.GENERATION_Z, newest.generation)
    }

    @Test
    fun `no history produces no age or generation`() {
        val result = calculate(emptyList(), accepted())

        assertTrue(result is NoHistoryResult)
        assertEquals(MusicalAgeStatus.NO_HISTORY, result.status)
        assertEquals(0, result.diagnostic.eligibleEvents)
        assertEquals(0, result.diagnostic.distinctRecordings)
    }

    @Test
    fun `19 recordings do not reach the minimum of 20`() {
        val result = calculate(fixture(2000, count = 19), accepted())

        assertTrue(result is InsufficientHistoryResult)
        assertTrue(
            result.diagnostic.unmetRequirements.any {
                it.requirement == MusicalAgeRequirement.ELIGIBLE_RECORDINGS &&
                    it.actual == 19.0 && it.minimum == 20.0
            }
        )
    }

    @Test
    fun `two days do not reach the minimum of three`() {
        val result = calculate(fixture(2000, dayCount = 2), accepted())

        assertTrue(result is InsufficientHistoryResult)
        assertTrue(
            result.diagnostic.unmetRequirements.any {
                it.requirement == MusicalAgeRequirement.ACTIVE_DAYS &&
                    it.actual == 2.0 && it.minimum == 3.0
            }
        )
    }

    @Test
    fun `less than 120 weighted minutes does not produce an age`() {
        val events = fixture().map { it.copy(playedMillis = 60_000L) }
        val result = calculate(events, accepted())

        assertTrue(result is InsufficientHistoryResult)
        assertTrue(
            result.diagnostic.unmetRequirements.any {
                it.requirement == MusicalAgeRequirement.WEIGHTED_MINUTES
            }
        )
    }

    @Test
    fun `missing dates do not use the listening date as release year`() {
        val result = calculate(fixture(), stateResolver(YearEvidenceState.MISSING, year = null))

        assertTrue(result is InsufficientMetadataResult)
        assertEquals(0, result.diagnostic.acceptedYearRecordings)
        assertEquals(20, result.diagnostic.missingYearRecordings)
        assertTrue(
            result.diagnostic.unmetRequirements.any {
                it.requirement == MusicalAgeRequirement.DATED_RECORDINGS &&
                    it.actual == 0.0 && it.minimum == 15.0
            }
        )
    }

    @Test
    fun `coverage of exactly 70 percent passes when the other gates pass`() {
        val events = fixture(2000, count = 30)
        val result = calculate(events, accepted().withMissingTracks(21 until 30))

        val ready = result as ReadyResult
        assertEquals(0.7, ready.coverage, 1e-12)
        assertEquals(21, ready.diagnostic.acceptedYearRecordings)
        assertEquals(9, ready.diagnostic.missingYearRecordings)
    }

    @Test
    fun `coverage below 70 percent does not produce an age`() {
        val events = fixture(2000, count = 30)
        val result = calculate(events, accepted().withMissingTracks(20 until 30))

        assertTrue(result is InsufficientMetadataResult)
        assertEquals(20, result.diagnostic.acceptedYearRecordings)
        assertEquals(10, result.diagnostic.missingYearRecordings)
        assertTrue(
            result.diagnostic.unmetRequirements.any {
                it.requirement == MusicalAgeRequirement.COVERAGE
            }
        )
    }

    @Test
    fun `balanced 1980 and 2020 eras produce mixed eras not a fake average`() {
        val events = fixture(1980, count = 30)
        val result = calculate(
            events,
            acceptedForTrack { track -> if (track >= 15) 2020 else 1980 },
        )

        val mixed = result as MixedErasResult
        assertEquals(0.5, mixed.dominantWindow.share, 1e-12)
        assertEquals(0.0, mixed.diagnostic.unmetRequirements
            .first { it.requirement == MusicalAgeRequirement.NON_OVERLAPPING_MARGIN }.actual, 1e-12)
        assertEquals(2, mixed.eras.size)
        assertEquals(1978, mixed.dominantWindow.fromYear)
        assertEquals(180.0, mixed.eras[1].weightMinutes, 0.0)
        assertTrue(2020 in mixed.eras[1].fromYear..mixed.eras[1].toYear)
    }

    @Test
    fun `reordering events does not change the result`() {
        val normal = calculate(fixture(), accepted())
        val reversed = calculate(fixture().reversed(), accepted())

        assertEquals(normal, reversed)
    }

    @Test
    fun `duplicating the same event id does not double the time`() {
        val base = calculate(fixture(), accepted()) as ReadyResult
        val doubled = calculate(fixture() + fixture(), accepted()) as ReadyResult

        assertEquals(base.musicalAge, doubled.musicalAge)
        assertEquals(base.weightedMinutes, doubled.weightedMinutes, 0.0)
        assertEquals(base.eligibleRecordings, doubled.eligibleRecordings)
        assertEquals(60, doubled.diagnostic.duplicateEventsIgnored)
        assertEquals(base.diagnostic.eligibleEvents, doubled.diagnostic.eligibleEvents)
    }

    @Test
    fun `loops have a per recording and day cap`() {
        val events = fixture().toMutableList()
        var nextId = 10_000L
        repeat(100) {
            events += MusicalAgeEvent(
                eventId = nextId++,
                songId = "s-0",
                recordingKey = "local:s-0",
                timestamp = LocalDateTime.of(2026, 11, 1, 12, 0),
                playedMillis = 60 * 60_000L,
                durationMillis = 4 * 60_000L,
                kind = ListeningKind.MUSIC,
            )
        }

        val result = calculate(events, accepted()) as ReadyResult

        assertEquals(12 * 60_000L, result.diagnostic.maxDailyWeightMillis)
        assertEquals(248.0, result.weightedMinutes, 0.0)
    }

    @Test
    fun `two local ids confirmed as one recording share caps and count once`() {
        val events = ArrayList<MusicalAgeEvent>()
        var eventId = 1L
        for (songId in listOf("s-a", "s-b")) {
            for (day in 1..3) {
                events += MusicalAgeEvent(
                    eventId = eventId++,
                    songId = songId,
                    recordingKey = "mb:recording-1",
                    timestamp = LocalDateTime.of(2026, 11, day, 12, 0),
                    playedMillis = 4 * 60_000L,
                    durationMillis = 4 * 60_000L,
                    kind = ListeningKind.MUSIC,
                )
            }
        }
        val resolver = RecordingYearResolver { recordings ->
            recordings.associate { it.recordingKey to listOf(acceptedEvidence(it.recordingKey, 2000)) }
        }

        val result = calculate(events, resolver)

        assertEquals(1, result.diagnostic.distinctRecordings)
        assertEquals(24.0, result.diagnostic.weightedMinutes, 0.0)
        assertEquals(1, result.diagnostic.acceptedYearRecordings)
    }

    @Test
    fun `future release year is not accepted`() {
        val result = calculate(fixture(), stateResolver(YearEvidenceState.ACCEPTED, year = 2030))

        assertTrue(result is InsufficientMetadataResult)
        assertEquals(20, result.diagnostic.invalidYearRecordings)
        assertEquals(0, result.diagnostic.acceptedYearRecordings)
    }

    @Test
    fun `year before 1850 is not accepted`() {
        val result = calculate(fixture(), stateResolver(YearEvidenceState.ACCEPTED, year = 1849))

        assertTrue(result is InsufficientMetadataResult)
        assertEquals(20, result.diagnostic.invalidYearRecordings)
    }

    @Test
    fun `unverified evidence does not enter as accepted date`() {
        val result = calculate(fixture(), stateResolver(YearEvidenceState.UNVERIFIED))

        assertTrue(result is InsufficientMetadataResult)
        assertEquals(20, result.diagnostic.unverifiedYearRecordings)
        assertEquals(0, result.diagnostic.acceptedYearRecordings)
    }

    @Test
    fun `unresolved recording version does not enter as accepted date`() {
        val result = calculate(
            fixture(),
            stateResolver(YearEvidenceState.ACCEPTED, resolvedVersion = null),
        )

        assertTrue(result is InsufficientMetadataResult)
        assertEquals(20, result.diagnostic.unresolvedVersionRecordings)
        assertEquals(0, result.diagnostic.acceptedYearRecordings)
    }

    @Test
    fun `accepted evidence with unknown precision remains a candidate`() {
        val resolver = RecordingYearResolver { recordings ->
            recordings.associate { recording ->
                recording.recordingKey to listOf(
                    YearEvidence(
                        recordingKey = recording.recordingKey,
                        year = 2000,
                        state = YearEvidenceState.ACCEPTED,
                        origin = "fixture",
                        precision = YearEvidencePrecision.UNKNOWN,
                        resolvedVersion = "studio-original",
                        source = YearEvidenceSource.CATALOG,
                        acceptanceRule = "fixture-v1",
                    )
                )
            }
        }

        val result = calculate(fixture(), resolver)

        assertTrue(result is InsufficientMetadataResult)
        assertEquals(20, result.diagnostic.yearCandidateRecordings)
        assertEquals(0, result.diagnostic.acceptedYearRecordings)
    }

    @Test
    fun `transient metadata error does not fabricate a date`() {
        val result = calculate(
            fixture(),
            stateResolver(YearEvidenceState.TRANSIENT_ERROR, year = null),
        )

        assertTrue(result is InsufficientMetadataResult)
        assertEquals(20, result.diagnostic.yearTransientErrorRecordings)
        assertEquals(0, result.diagnostic.acceptedYearRecordings)
    }

    @Test
    fun `accepted evidence without provenance or acceptance rule is incomplete`() {
        val resolver = RecordingYearResolver { recordings ->
            recordings.associate { recording ->
                recording.recordingKey to listOf(
                    YearEvidence(
                        recordingKey = recording.recordingKey,
                        year = 2000,
                        state = YearEvidenceState.ACCEPTED,
                        origin = "fixture",
                        precision = YearEvidencePrecision.RECORDING_RELEASE_YEAR,
                        resolvedVersion = "studio-original",
                    )
                )
            }
        }

        val result = calculate(fixture(), resolver)

        assertTrue(result is InsufficientMetadataResult)
        assertEquals(20, result.diagnostic.incompleteEvidenceRecordings)
        assertEquals(0, result.diagnostic.acceptedYearRecordings)
    }

    @Test
    fun `contradictory content on one recording is not accepted`() {
        val events = fixture().toMutableList()
        events += MusicalAgeEvent(
            eventId = 10_000L,
            songId = "s-0",
            recordingKey = "local:s-0",
            timestamp = LocalDateTime.of(2026, 11, 1, 13, 0),
            playedMillis = 4 * 60_000L,
            durationMillis = 4 * 60_000L,
            kind = ListeningKind.UNKNOWN,
        )

        val result = calculate(events, accepted()) as ReadyResult

        assertEquals(19, result.diagnostic.acceptedYearRecordings)
        assertEquals(1, result.diagnostic.conflictingContentRecordings)
    }

    @Test
    fun `confirmed non music is excluded`() {
        val result = calculate(fixture(kind = ListeningKind.NON_MUSIC), accepted())

        assertTrue(result is NoHistoryResult)
        assertEquals(60, result.diagnostic.nonMusicEvents)
        assertEquals(0, result.diagnostic.eligibleEvents)
    }

    @Test
    fun `unknown content remains in the denominator without an accepted date`() {
        val result = calculate(fixture(kind = ListeningKind.UNKNOWN), accepted())

        assertTrue(result is InsufficientMetadataResult)
        assertEquals(20, result.diagnostic.distinctRecordings)
        assertEquals(20, result.diagnostic.unconfirmedContentRecordings)
        assertEquals(0, result.diagnostic.acceptedYearRecordings)
        assertEquals(0.0, result.diagnostic.coverage, 0.0)
    }

    @Test
    fun `year rollover uses the injected reference clock`() {
        val result = calculate(
            fixture(),
            accepted(),
            at = LocalDateTime.of(2027, 1, 1, 12, 0),
        ) as ReadyResult

        assertEquals(45, result.musicalAge)
        assertEquals(1982, result.musicalBirthYear)
    }

    @Test
    fun `tracks on both sides of a decade do not depend on fixed bins`() {
        val events = fixture(1999, count = 30)
        val result = calculate(
            events,
            acceptedForTrack { track -> if (track % 2 == 1) 2000 else 1999 },
        ) as ReadyResult

        assertEquals(1999, result.referenceYear)
        assertEquals(45, result.musicalAge)
    }

    @Test
    fun `conflicting years for the same recording are not silently resolved`() {
        val resolver = RecordingYearResolver { recordings ->
            recordings.associate { recording ->
                if (recording.recordingKey == "local:s-0") {
                    recording.recordingKey to listOf(
                        acceptedEvidence(recording.recordingKey, 2000),
                        acceptedEvidence(recording.recordingKey, 1980),
                    )
                } else {
                    recording.recordingKey to listOf(
                        acceptedEvidence(recording.recordingKey, 2000)
                    )
                }
            }
        }

        val result = calculate(fixture(), resolver) as ReadyResult

        assertEquals(19, result.diagnostic.acceptedYearRecordings)
        assertEquals(1, result.diagnostic.conflictingYearRecordings)
    }

    @Test
    fun `evidence linked to another recording is rejected before promotion`() {
        val resolver = RecordingYearResolver { recordings ->
            recordings.associate { recording ->
                recording.recordingKey to listOf(
                    acceptedEvidence(recording.recordingKey, 2000).copy(
                        recordingKey = "local:another-song",
                        canonicalRecordingId = "11111111-1111-1111-1111-111111111111",
                    )
                )
            }
        }

        val result = calculate(fixture(), resolver)

        assertTrue(result is InsufficientMetadataResult)
        assertEquals(20, result.diagnostic.invalidIdentityRecordings)
        assertEquals(0, result.diagnostic.acceptedYearRecordings)
    }

    @Test
    fun `divergent versions with the same year are a conflict`() {
        val resolver = RecordingYearResolver { recordings ->
            recordings.associate { recording ->
                recording.recordingKey to listOf(
                    acceptedEvidence(recording.recordingKey, 2000).copy(
                        resolvedVersion = "studio",
                    ),
                    acceptedEvidence(recording.recordingKey, 2000).copy(
                        resolvedVersion = "live",
                    ),
                )
            }
        }

        val result = calculate(fixture(), resolver)

        assertTrue(result is InsufficientMetadataResult)
        assertEquals(20, result.diagnostic.conflictingYearRecordings)
        assertEquals(0, result.diagnostic.acceptedYearRecordings)
    }

    @Test
    fun `generation boundaries follow the documented convention`() {
        assertEquals(MusicalGeneration.CLASSICS, MusicalGeneration.forBirthYear(1927))
        assertEquals(MusicalGeneration.SILENT, MusicalGeneration.forBirthYear(1928))
        assertEquals(MusicalGeneration.SILENT, MusicalGeneration.forBirthYear(1945))
        assertEquals(MusicalGeneration.BOOMER, MusicalGeneration.forBirthYear(1946))
        assertEquals(MusicalGeneration.BOOMER, MusicalGeneration.forBirthYear(1964))
        assertEquals(MusicalGeneration.GENERATION_X, MusicalGeneration.forBirthYear(1965))
        assertEquals(MusicalGeneration.GENERATION_X, MusicalGeneration.forBirthYear(1980))
        assertEquals(MusicalGeneration.MILLENNIAL, MusicalGeneration.forBirthYear(1981))
        assertEquals(MusicalGeneration.MILLENNIAL, MusicalGeneration.forBirthYear(1996))
        assertEquals(MusicalGeneration.GENERATION_Z, MusicalGeneration.forBirthYear(1997))
        assertEquals(MusicalGeneration.GENERATION_Z, MusicalGeneration.forBirthYear(2012))
        assertEquals(MusicalGeneration.ALPHA, MusicalGeneration.forBirthYear(2013))
    }

    @Test
    fun `period boundaries are inclusive at the start and at asOf`() {
        val start = policy.periodStart(asOf)
        val events = listOf(
            oneEvent(240_000L, 240_000L, eventId = 1, songId = "at-start")
                .copy(timestamp = start),
            oneEvent(240_000L, 240_000L, eventId = 2, songId = "before")
                .copy(timestamp = start.minusNanos(1)),
            oneEvent(240_000L, 240_000L, eventId = 3, songId = "at-asof")
                .copy(timestamp = asOf),
            oneEvent(240_000L, 240_000L, eventId = 4, songId = "after")
                .copy(timestamp = asOf.plusNanos(1)),
        )

        val result = calculate(events, accepted())

        assertEquals(2, result.diagnostic.eligibleEvents)
        assertEquals(1, result.diagnostic.beforePeriodEventsIgnored)
        assertEquals(1, result.diagnostic.futureEventsIgnored)
        assertEquals(2, result.diagnostic.totalEventsRead - result.diagnostic.eligibleEvents)
    }

    @Test
    fun `known duration uses min 30s max 10s half duration`() {
        assertEquals(1, eligibleCount(oneEvent(20_000L, 10_000L)))
        assertEquals(0, eligibleCount(oneEvent(20_000L, 9_999L)))
        assertEquals(1, eligibleCount(oneEvent(60_000L, 30_000L)))
        assertEquals(0, eligibleCount(oneEvent(60_000L, 29_999L)))
        assertEquals(1, eligibleCount(oneEvent(8_000L, 10_000L)))
        assertEquals(0, eligibleCount(oneEvent(8_000L, 9_999L)))
    }

    @Test
    fun `unknown duration requires 30 seconds`() {
        assertEquals(1, eligibleCount(oneEvent(null, 30_000L)))
        assertEquals(0, eligibleCount(oneEvent(null, 29_999L)))
    }

    @Test
    fun `event weight is capped at known duration or at five minutes when unknown`() {
        assertEquals(4.0, weightedMinutes(oneEvent(4 * 60_000L, 10 * 60_000L)), 0.0)
        assertEquals(5.0, weightedMinutes(oneEvent(null, 10 * 60_000L)), 0.0)
        assertEquals(5.0, weightedMinutes(oneEvent(10 * 60_000L, 10 * 60_000L)), 0.0)
    }

    @Test
    fun `daily cap is three times effective duration and at most fifteen minutes`() {
        val shortTracks = (1L..10L).map { id ->
            oneEvent(2 * 60_000L, 2 * 60_000L, eventId = id, songId = "short")
        }
        assertEquals(6.0, weightedMinutes(shortTracks), 0.0)

        val longTracks = (1L..10L).map { id ->
            oneEvent(5 * 60_000L, 5 * 60_000L, eventId = id, songId = "long")
        }
        assertEquals(15.0, weightedMinutes(longTracks), 0.0)
    }

    @Test
    fun `extreme play time does not overflow and is capped`() {
        val events = (1L..10_000L).map { id ->
            oneEvent(4 * 60_000L, Long.MAX_VALUE, eventId = id, songId = "extreme")
        }
        val result = calculate(events, accepted())

        assertEquals(12 * 60_000L, result.diagnostic.maxDailyWeightMillis)
        assertEquals(12.0, result.diagnostic.weightedMinutes, 0.0)
    }

    @Test
    fun `window tie break prefers the center closest to the global weighted median`() {
        val events = ArrayList<MusicalAgeEvent>()
        var id = 1L
        for (track in 0 until 20) {
            val songId = "tie-$track"
            for (day in 1..3) {
                events += MusicalAgeEvent(
                    eventId = id++,
                    songId = songId,
                    recordingKey = "local:$songId",
                    timestamp = LocalDateTime.of(2026, 11, day, 12, 0),
                    playedMillis = 5 * 60_000L,
                    durationMillis = 5 * 60_000L,
                    kind = ListeningKind.MUSIC,
                )
            }
        }
        val resolver = RecordingYearResolver { recordings ->
            recordings.associate { recording ->
                val track = recording.localSongId!!.removePrefix("tie-").toInt()
                val year = if (track < 10) 1990 else 2000
                recording.recordingKey to listOf(
                    acceptedEvidence(recording.recordingKey, year)
                )
            }
        }

        val result = calculate(events, resolver) as MixedErasResult

        assertEquals(1988, result.dominantWindow.fromYear)
        assertEquals(1992, result.dominantWindow.toYear)
    }

    @Test
    fun `non overlapping runner ignores an overlapping window with almost the same tracks`() {
        val events = ArrayList<MusicalAgeEvent>()
        var id = 1L

        fun addTrack(songId: String, perDayMillis: Long, eventsPerDay: Int) {
            for (day in 1..3) {
                repeat(eventsPerDay) {
                    events += MusicalAgeEvent(
                        eventId = id++,
                        songId = songId,
                        recordingKey = "local:$songId",
                        timestamp = LocalDateTime.of(2026, 11, day, 12, 0),
                        playedMillis = perDayMillis,
                        durationMillis = perDayMillis,
                        kind = ListeningKind.MUSIC,
                    )
                }
            }
        }

        repeat(5) { track -> addTrack("y2000-$track", 4 * 60_000L, eventsPerDay = 3) }
        events += MusicalAgeEvent(
            eventId = id++,
            songId = "y2003",
            recordingKey = "local:y2003",
            timestamp = LocalDateTime.of(2026, 11, 1, 12, 0),
            playedMillis = 5 * 60_000L,
            durationMillis = 5 * 60_000L,
            kind = ListeningKind.MUSIC,
        )
        repeat(18) { track -> addTrack("y1900-$track", 60_000L, eventsPerDay = 1) }

        val resolver = RecordingYearResolver { recordings ->
            recordings.associate { recording ->
                val year = when {
                    recording.localSongId!!.startsWith("y2000-") -> 2000
                    recording.localSongId == "y2003" -> 2003
                    else -> 1900
                }
                recording.recordingKey to listOf(
                    acceptedEvidence(recording.recordingKey, year)
                )
            }
        }

        val result = calculate(events, resolver)

        assertTrue(result is ReadyResult)
        assertEquals(1999, (result as ReadyResult).dominantWindow.fromYear)
        assertEquals(2003, result.dominantWindow.toYear)
    }

    @Test
    fun `injected clock supplies a fixed local calculation instant`() {
        val fixedClock = MusicalAgeClock(
            Clock.fixed(Instant.parse("2026-11-06T15:00:00Z"), zone),
            zone,
        )
        val result = MusicalAgeEngine(policy, fixedClock).calculate(
            events = fixture(),
            resolver = accepted(),
            dataOrigin = MusicalAgeDataOrigin.SYNTHETIC_FIXTURE,
        )

        assertEquals(LocalDateTime.of(2026, 11, 6, 12, 0), result.diagnostic.asOf)
        assertEquals(LocalDateTime.of(2026, 8, 9, 0, 0), result.diagnostic.periodStart)
        assertEquals(LocalDateTime.of(2026, 11, 6, 12, 0), result.diagnostic.periodEnd)
    }

    private fun eligibleCount(event: MusicalAgeEvent): Int =
        calculate(listOf(event), accepted()).diagnostic.eligibleEvents

    private fun weightedMinutes(event: MusicalAgeEvent): Double =
        calculate(listOf(event), accepted()).diagnostic.weightedMinutes

    private fun weightedMinutes(events: List<MusicalAgeEvent>): Double =
        calculate(events, accepted()).diagnostic.weightedMinutes

    private fun RecordingYearResolver.withMissingTracks(tracks: IntRange): RecordingYearResolver =
        run {
            val delegate = this
            RecordingYearResolver { recordings ->
            recordings.associate { recording ->
                val track = recording.localSongId
                    ?.removePrefix("s-")
                    ?.toIntOrNull()
                    ?: -1
                if (track in tracks) {
                    recording.recordingKey to listOf(
                        YearEvidence(
                            recordingKey = recording.recordingKey,
                            year = null,
                            state = YearEvidenceState.MISSING,
                            origin = "fixture-missing",
                        )
                    )
                } else {
                    val resolved = delegate.resolve(listOf(recording))[recording.recordingKey]
                    recording.recordingKey to (resolved ?: emptyList())
                }
            }
            }
        }
}
