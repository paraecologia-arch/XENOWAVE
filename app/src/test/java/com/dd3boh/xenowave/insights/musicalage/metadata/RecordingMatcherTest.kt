package com.dd3boh.xenowave.insights.musicalage.metadata

import com.dd3boh.xenowave.insights.musicalage.YearEvidencePrecision
import com.dd3boh.xenowave.insights.musicalage.YearEvidenceState
import com.dd3boh.xenowave.insights.musicalage.content.SongMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class RecordingMatcherTest {
    private val matcher = RecordingMatcher()
    private val obtainedAt = Instant.parse("2026-11-06T12:00:00Z")
    private val today = LocalDate.of(2026, 11, 6)
    private val complete = SearchCompleteness(
        totalCount = 1,
        offset = 0,
        returnedCount = 1,
        limit = 10,
    )
    private val song = SongMetadata(
        songId = "song-1",
        title = "Fixture Song",
        artists = listOf("Fixture Artist"),
        durationMillis = 240_000L,
    )

    private fun candidate(
        id: String = "11111111-1111-1111-1111-111111111111",
        title: String = "Fixture Song",
        artist: String = "Fixture Artist",
        duration: Long? = 241_000L,
        date: String? = "1993-05-10",
        dateSource: CandidateDateSource = CandidateDateSource.RECORDING_FIRST_RELEASE,
        disambiguation: String? = null,
        corroboratingDate: String? = "1993-05-10",
        primaryType: String? = "Album",
        secondaryTypes: List<String> = emptyList(),
        score: Int = 100,
    ) = RecordingCandidate(
        providerId = id,
        title = title,
        artists = listOf(artist),
        durationMillis = duration,
        disambiguation = disambiguation,
        firstReleaseDate = date,
        dateSource = dateSource,
        releaseId = "22222222-2222-2222-2222-222222222222",
        releaseGroupId = "33333333-3333-3333-3333-333333333333",
        releaseTitle = "Fixture Album",
        releasePrimaryType = primaryType,
        releaseSecondaryTypes = secondaryTypes,
        corroboratingRelease = corroboratingDate?.let {
            CorroboratingRelease(
                releaseId = "22222222-2222-2222-2222-222222222222",
                releaseGroupId = "33333333-3333-3333-3333-333333333333",
                title = "Fixture Album",
                date = it,
                primaryType = primaryType ?: "Album",
                secondaryTypes = secondaryTypes,
            )
        },
        hasNonCompilationRelease = true,
        isrcs = listOf("BRAAA0000001"),
        score = score,
    )

    private fun match(
        local: SongMetadata = song,
        candidates: List<RecordingCandidate> = listOf(candidate()),
        completeness: SearchCompleteness = complete,
    ) = matcher.match(local, candidates, today, obtainedAt, completeness)

    @Test
    fun `title artist duration version date and release accept one candidate`() {
        val result = match()

        assertEquals(RecordingMatchState.ACCEPTED, result.state)
        assertEquals(YearEvidenceState.ACCEPTED, result.evidence.state)
        assertEquals(1993, result.evidence.year)
        assertEquals(YearEvidencePrecision.RECORDING_RELEASE_DAY, result.evidence.precision)
        assertEquals("unqualified", result.evidence.resolvedVersion)
        assertEquals("11111111-1111-1111-1111-111111111111", result.evidence.canonicalRecordingId)
        assertEquals("11111111-1111-1111-1111-111111111111", result.evidence.associationProof)
    }

    @Test
    fun `same title with a different primary artist is not accepted`() {
        val result = match(candidates = listOf(candidate(artist = "Another Artist")))

        assertEquals(RecordingMatchState.MISSING, result.state)
        assertTrue(result.reasons.any { it.contains("artista") })
    }

    @Test
    fun `a collaborator credit does not replace the primary artist`() {
        val result = match(
            local = song.copy(artists = listOf("Fixture Artist", "Guest")),
            candidates = listOf(candidate(artist = "Guest")),
        )

        assertEquals(RecordingMatchState.MISSING, result.state)
    }

    @Test
    fun `live candidate does not match a studio local title`() {
        val result = match(candidates = listOf(candidate(title = "Fixture Song (Live)")))

        assertEquals(RecordingMatchState.MISSING, result.state)
    }

    @Test
    fun `live local title matches live candidate with the same qualifier`() {
        val result = match(
            local = song.copy(title = "Fixture Song (Live)"),
            candidates = listOf(candidate(title = "Fixture Song (Live)")),
        )

        assertEquals(RecordingMatchState.ACCEPTED, result.state)
        assertEquals("live", result.evidence.resolvedVersion)
    }

    @Test
    fun `radio edit and extended qualifiers are preserved`() {
        assertEquals(
            RecordingMatchState.MISSING,
            match(local = song.copy(title = "Fixture Song (Radio Edit)"))
                .state,
        )
        assertEquals(
            RecordingMatchState.ACCEPTED,
            match(
                local = song.copy(title = "Fixture Song (Radio Edit)"),
                candidates = listOf(candidate(title = "Fixture Song (Radio Edit)")),
            ).state,
        )
        assertEquals(
            RecordingMatchState.MISSING,
            match(
                local = song.copy(title = "Fixture Song (Extended)"),
                candidates = listOf(candidate(title = "Fixture Song")),
            ).state,
        )
    }

    @Test
    fun `unknown qualifiers must be equal instead of being erased`() {
        val brazilian = song.copy(title = "Fixture Song (Brazilian Version)")
        val argentinian = candidate(title = "Fixture Song (Argentinian Version)")

        assertEquals(
            RecordingMatchState.MISSING,
            match(local = brazilian, candidates = listOf(argentinian)).state,
        )
    }

    @Test
    fun `catalog disambiguation is part of identity`() {
        val result = match(candidates = listOf(candidate(disambiguation = "live at Wembley")))

        assertEquals(RecordingMatchState.MISSING, result.state)
    }

    @Test
    fun `incompatible or missing duration blocks acceptance`() {
        assertEquals(RecordingMatchState.MISSING, match(candidates = listOf(candidate(duration = 300_000L))).state)
        assertEquals(RecordingMatchState.MISSING, match(candidates = listOf(candidate(duration = null))).state)
        assertEquals(
            RecordingMatchState.MISSING,
            match(local = song.copy(durationMillis = null)).state,
        )
    }

    @Test
    fun `release group date is only a candidate not a recording first release`() {
        val result = match(
            candidates = listOf(candidate(dateSource = CandidateDateSource.RELEASE_GROUP)),
        )

        assertEquals(RecordingMatchState.CANDIDATE, result.state)
        assertEquals(YearEvidenceState.CANDIDATE, result.evidence.state)
    }

    @Test
    fun `partial and future dates are handled conservatively`() {
        val yearOnly = match(candidates = listOf(candidate(date = "1993", corroboratingDate = "1993")))
        assertEquals(RecordingMatchState.ACCEPTED, yearOnly.state)
        assertEquals(YearEvidencePrecision.YEAR_ONLY, yearOnly.evidence.precision)

        val month = match(candidates = listOf(candidate(date = "1993-05", corroboratingDate = "1993-05")))
        assertEquals(RecordingMatchState.ACCEPTED, month.state)
        assertEquals(YearEvidencePrecision.RECORDING_RELEASE_MONTH, month.evidence.precision)

        val future = match(candidates = listOf(candidate(date = "2030", corroboratingDate = "2030")))
        assertEquals(RecordingMatchState.CANDIDATE, future.state)
    }

    @Test
    fun `impossible calendar date and future month are rejected`() {
        val impossible = match(
            candidates = listOf(candidate(date = "1993-02-31", corroboratingDate = "1993-02-31")),
        )
        assertEquals(RecordingMatchState.CANDIDATE, impossible.state)
        assertNull(impossible.evidence.year)

        val futureMonth = match(
            candidates = listOf(candidate(date = "2026-12", corroboratingDate = "2026-12")),
        )
        assertEquals(RecordingMatchState.CANDIDATE, futureMonth.state)
    }

    @Test
    fun `partial search cannot prove that no competitor exists`() {
        val result = match(
            completeness = SearchCompleteness(
                totalCount = 7,
                offset = 0,
                returnedCount = 1,
                limit = 1,
            ),
        )

        assertEquals(RecordingMatchState.CANDIDATE, result.state)
        assertTrue(result.reasons.any { it.contains("busca parcial") })
    }

    @Test
    fun `corroborating release must be known non compilation and match the date`() {
        val noRelease = match(candidates = listOf(candidate(corroboratingDate = null)))
        assertEquals(RecordingMatchState.CANDIDATE, noRelease.state)
        assertTrue(noRelease.reasons.any { it.contains("release") })

        val differentDate = match(candidates = listOf(candidate(corroboratingDate = "1994-05-10")))
        assertEquals(RecordingMatchState.CANDIDATE, differentDate.state)
        assertTrue(differentDate.reasons.any { it.contains("data") })
    }

    @Test
    fun `multiple compatible candidates remain ambiguous`() {
        val result = match(
            candidates = listOf(
                candidate(id = "11111111-1111-1111-1111-111111111111"),
                candidate(id = "44444444-4444-4444-4444-444444444444", date = "1994"),
            ),
            completeness = SearchCompleteness(2, 0, 2, 10),
        )

        assertEquals(RecordingMatchState.CONFLICT, result.state)
        assertEquals(YearEvidenceState.CONFLICT, result.evidence.state)
        assertEquals(setOf(1993, 1994), result.evidence.conflictingYears)
    }

    @Test
    fun `a high score does not rescue a wrong version`() {
        val result = match(candidates = listOf(candidate(title = "Fixture Song (Remix)", score = 100)))

        assertEquals(RecordingMatchState.MISSING, result.state)
    }

    @Test
    fun `remaster qualifier does not create a different recording`() {
        val result = match(candidates = listOf(candidate(title = "Fixture Song (Remastered 2011)", date = "1993", corroboratingDate = "1993")))

        assertEquals(RecordingMatchState.ACCEPTED, result.state)
    }

    @Test
    fun `a compilation only candidate is not accepted as first release`() {
        val result = match(
            candidates = listOf(
                candidate(corroboratingDate = null, secondaryTypes = listOf("Compilation")),
            ),
        )

        assertEquals(RecordingMatchState.CANDIDATE, result.state)
        assertTrue(result.reasons.any { it.contains("release") })
    }
}
