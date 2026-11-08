package com.dd3boh.xenowave.insights.musicalage.metadata

import com.dd3boh.xenowave.insights.musicalage.ContentEvidenceState
import com.dd3boh.xenowave.insights.musicalage.LocalOnlyRecordingYearResolver
import com.dd3boh.xenowave.insights.musicalage.RecordingIdentity
import com.dd3boh.xenowave.insights.musicalage.RecordingVersion
import com.dd3boh.xenowave.insights.musicalage.YearEvidencePrecision
import com.dd3boh.xenowave.insights.musicalage.YearEvidenceSource
import com.dd3boh.xenowave.insights.musicalage.YearEvidenceState
import com.dd3boh.xenowave.insights.musicalage.content.InMemoryManualEvidenceStore
import com.dd3boh.xenowave.insights.musicalage.content.ManualEvidence
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class ManualEvidenceResolverTest {
    private val now = Instant.parse("2026-11-06T12:00:00Z")
    private val recording = RecordingIdentity("local:song-1", localSongId = "song-1")

    @Test
    fun `complete manual year becomes accepted with user provenance`() {
        val store = InMemoryManualEvidenceStore().apply {
            put(
                ManualEvidence(
                    songId = "song-1",
                    contentState = ContentEvidenceState.CONFIRMED_MUSIC,
                    year = 1993,
                    precision = YearEvidencePrecision.YEAR_ONLY,
                    resolvedVersion = RecordingVersion.STUDIO,
                    chosenAt = now,
                )
            )
        }

        val evidence = LocalOnlyRecordingYearResolver(manualEvidenceStore = store)
            .resolve(listOf(recording))
            .getValue("local:song-1")
            .single()

        assertEquals(YearEvidenceState.ACCEPTED, evidence.state)
        assertEquals(1993, evidence.year)
        assertEquals(YearEvidenceSource.USER_CONFIRMED, evidence.source)
        assertEquals("user-confirmed-v2", evidence.acceptanceRule)
    }

    @Test
    fun `manual year without resolved version remains a candidate`() {
        val store = InMemoryManualEvidenceStore().apply {
            put(
                ManualEvidence(
                    songId = "song-1",
                    year = 1993,
                    precision = YearEvidencePrecision.YEAR_ONLY,
                    chosenAt = now,
                )
            )
        }

        val evidence = LocalOnlyRecordingYearResolver(manualEvidenceStore = store)
            .resolve(listOf(recording))
            .getValue("local:song-1")
            .single()

        assertEquals(YearEvidenceState.CANDIDATE, evidence.state)
        assertEquals(YearEvidenceSource.USER_CONFIRMED, evidence.source)
    }

    @Test
    fun `manual contradictions remain visible`() {
        val store = InMemoryManualEvidenceStore().apply {
            put(
                ManualEvidence(
                    songId = "song-1",
                    year = 1993,
                    precision = YearEvidencePrecision.YEAR_ONLY,
                    resolvedVersion = RecordingVersion.STUDIO,
                    chosenAt = now,
                )
            )
            put(
                ManualEvidence(
                    songId = "song-1",
                    year = 2000,
                    precision = YearEvidencePrecision.YEAR_ONLY,
                    resolvedVersion = RecordingVersion.LIVE,
                    chosenAt = now,
                )
            )
        }

        val evidence = LocalOnlyRecordingYearResolver(manualEvidenceStore = store)
            .resolve(listOf(recording))
            .getValue("local:song-1")
            .single()

        assertEquals(YearEvidenceState.CONFLICT, evidence.state)
        assertEquals(setOf(1993, 2000), evidence.conflictingYears)
    }
}
