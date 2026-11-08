package com.dd3boh.xenowave.insights.musicalage.content

import com.dd3boh.xenowave.insights.musicalage.ContentEvidenceSource
import com.dd3boh.xenowave.insights.musicalage.ContentEvidenceState
import com.dd3boh.xenowave.insights.musicalage.ListeningKind
import com.dd3boh.xenowave.insights.musicalage.YearEvidencePrecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ContentClassifierTest {
    private val song = SongMetadata(
        songId = "song-1",
        title = "Fixture Song",
        artists = listOf("Fixture Artist"),
        durationMillis = 240_000L,
        isLocal = true,
        localPathExtension = "mp3",
    )

    @Test
    fun `playback metadata and audio extension never confirm music by themselves`() {
        val result = UnknownContentClassifier().classify(listOf(song)).getValue(song.songId)

        assertEquals(ContentEvidenceState.UNKNOWN, result.state)
        assertEquals(ListeningKind.UNKNOWN, result.kind)
        assertEquals(ContentEvidenceSource.NONE, result.source)
    }

    @Test
    fun `explicit confirmation produces music`() {
        val confirmed = mapOf(
            song.songId to listOf(
                ContentEvidence(
                    songId = song.songId,
                    state = ContentEvidenceState.CONFIRMED_MUSIC,
                    source = ContentEvidenceSource.LOCAL_METADATA,
                    reason = "fixture",
                )
            )
        )

        val result = ExplicitContentClassifier(confirmed).classify(listOf(song)).getValue(song.songId)

        assertEquals(ContentEvidenceState.CONFIRMED_MUSIC, result.state)
        assertEquals(ListeningKind.MUSIC, result.kind)
    }

    @Test
    fun `manual content correction wins over a weak explicit hint`() {
        val manual = InMemoryManualEvidenceStore().apply {
            put(
                ManualEvidence(
                    songId = song.songId,
                    contentState = ContentEvidenceState.CONFIRMED_MUSIC,
                    chosenAt = Instant.parse("2026-11-06T12:00:00Z"),
                )
            )
        }
        val weak = mapOf(
            song.songId to listOf(
                ContentEvidence(
                    songId = song.songId,
                    state = ContentEvidenceState.UNKNOWN,
                    source = ContentEvidenceSource.LOCAL_METADATA,
                    reason = "weak",
                )
            )
        )

        val result = ExplicitContentClassifier(weak, manual)
            .classify(listOf(song))
            .getValue(song.songId)

        assertEquals(ContentEvidenceState.CONFIRMED_MUSIC, result.state)
        assertEquals(ContentEvidenceSource.USER_CONFIRMED, result.source)
    }

    @Test
    fun `a new manual content confirmation replaces the previous edit`() {
        val manual = InMemoryManualEvidenceStore().apply {
            put(
                ManualEvidence(
                    songId = song.songId,
                    contentState = ContentEvidenceState.CONFIRMED_MUSIC,
                    chosenAt = Instant.parse("2026-11-06T12:00:00Z"),
                )
            )
            put(
                ManualEvidence(
                    songId = song.songId,
                    contentState = ContentEvidenceState.CONFIRMED_NON_MUSIC,
                    chosenAt = Instant.parse("2026-11-06T12:01:00Z"),
                )
            )
        }

        val result = ExplicitContentClassifier(manualEvidenceStore = manual)
            .classify(listOf(song))
            .getValue(song.songId)

        assertEquals(ContentEvidenceState.CONFIRMED_NON_MUSIC, result.state)
        assertEquals(ListeningKind.NON_MUSIC, result.kind)
    }

    @Test
    fun `contradictory explicit evidence remains a conflict`() {
        val confirmed = mapOf(
            song.songId to listOf(
                ContentEvidence(
                    songId = song.songId,
                    state = ContentEvidenceState.CONFIRMED_MUSIC,
                    source = ContentEvidenceSource.LOCAL_METADATA,
                    reason = "first",
                ),
                ContentEvidence(
                    songId = song.songId,
                    state = ContentEvidenceState.CONFIRMED_NON_MUSIC,
                    source = ContentEvidenceSource.LOCAL_METADATA,
                    reason = "second",
                ),
            )
        )

        val result = ExplicitContentClassifier(confirmed)
            .classify(listOf(song))
            .getValue(song.songId)

        assertEquals(ContentEvidenceState.CONFLICT, result.state)
        assertEquals(ListeningKind.UNKNOWN, result.kind)
    }

}
