package com.dd3boh.xenowave.insights.musicalage.data

import com.dd3boh.xenowave.insights.musicalage.RecordingVersion
import com.dd3boh.xenowave.insights.musicalage.YearEvidencePrecision
import com.dd3boh.xenowave.insights.musicalage.YearEvidenceState
import com.dd3boh.xenowave.insights.musicalage.content.ManualEvidence
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ManualEvidenceRepositoryTest {
    private val now = Instant.parse("2026-11-06T12:00:00Z")

    private fun directory() = java.io.File(
        System.getProperty("java.io.tmpdir"),
        "musical-age-manual-${System.nanoTime()}",
    ).apply { mkdirs() }

    @Test
    fun `manual correction survives a new repository instance`() = runBlocking {
        val dir = directory()
        try {
            val first = ManualEvidenceRepository(dir)
            assertTrue(
                first.put(
                    ManualEvidence(
                        songId = "song-1",
                        year = 1993,
                        precision = YearEvidencePrecision.YEAR_ONLY,
                        resolvedVersion = RecordingVersion.STUDIO,
                        chosenAt = now,
                    )
                )
            )

            val second = ManualEvidenceRepository(dir)
            assertTrue(second.load())
            val entry = second.snapshotStore()
                .forSongs(listOf("song-1"))
                .getValue("song-1")
                .single()
            assertEquals(1993, entry.year)
            assertEquals(RecordingVersion.STUDIO, entry.resolvedVersion)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `editing the same version replaces while a different version stays separate`() = runBlocking {
        val dir = directory()
        try {
            val repository = ManualEvidenceRepository(dir)
            repository.put(
                ManualEvidence(
                    songId = "song-1",
                    year = 1993,
                    precision = YearEvidencePrecision.YEAR_ONLY,
                    resolvedVersion = RecordingVersion.STUDIO,
                    chosenAt = now,
                )
            )
            repository.put(
                ManualEvidence(
                    songId = "song-1",
                    year = 1994,
                    precision = YearEvidencePrecision.RECORDING_RELEASE_DAY,
                    resolvedVersion = RecordingVersion.STUDIO,
                    chosenAt = now,
                )
            )
            assertEquals(1, repository.count())
            assertEquals(
                1994,
                repository.snapshotStore().forSongs(listOf("song-1"))
                    .getValue("song-1").single().year,
            )

            repository.put(
                ManualEvidence(
                    songId = "song-1",
                    year = 2000,
                    precision = YearEvidencePrecision.YEAR_ONLY,
                    resolvedVersion = RecordingVersion.LIVE,
                    chosenAt = now,
                )
            )
            assertEquals(2, repository.count())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `removal and corruption are recoverable`() = runBlocking {
        val dir = directory()
        try {
            val repository = ManualEvidenceRepository(dir)
            repository.put(
                ManualEvidence(
                    songId = "song-1",
                    contentState = com.dd3boh.xenowave.insights.musicalage.ContentEvidenceState.CONFIRMED_MUSIC,
                    chosenAt = now,
                )
            )
            assertTrue(repository.remove("song-1"))
            assertEquals(0, repository.count())

            dir.resolve("musical-age-manual-evidence-v1.json").writeText("{broken")
            val reopened = ManualEvidenceRepository(dir)
            assertEquals(false, reopened.load())
            assertEquals(0, reopened.count())
            // A failed load must not be followed by an overwrite that destroys
            // the unreadable previous file.
            assertEquals(
                false,
                reopened.put(
                    ManualEvidence(
                        songId = "song-2",
                        year = 1999,
                        precision = YearEvidencePrecision.YEAR_ONLY,
                        resolvedVersion = RecordingVersion.STUDIO,
                        chosenAt = now,
                    )
                ),
            )
            assertEquals(
                "{broken",
                dir.resolve("musical-age-manual-evidence-v1.json").readText(),
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
