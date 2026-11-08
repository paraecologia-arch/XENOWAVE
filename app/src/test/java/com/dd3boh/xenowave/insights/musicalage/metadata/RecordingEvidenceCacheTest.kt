package com.dd3boh.xenowave.insights.musicalage.metadata

import com.dd3boh.xenowave.insights.musicalage.YearEvidence
import com.dd3boh.xenowave.insights.musicalage.YearEvidencePrecision
import com.dd3boh.xenowave.insights.musicalage.YearEvidenceSource
import com.dd3boh.xenowave.insights.musicalage.YearEvidenceState
import com.dd3boh.xenowave.insights.musicalage.content.SongMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RecordingEvidenceCacheTest {
    private val now = Instant.parse("2026-11-06T12:00:00Z")
    private val evidence = YearEvidence(
        recordingKey = "local:song-1",
        year = 1993,
        state = YearEvidenceState.ACCEPTED,
        origin = "fixture",
        precision = YearEvidencePrecision.YEAR_ONLY,
        resolvedVersion = "studio",
        source = YearEvidenceSource.CATALOG,
        acceptanceRule = "fixture-v1",
    )
    private val key = "a".repeat(64)
    private val entry = RecordingEvidenceCacheEntry(
        key = key,
        kind = CacheEntryKind.CONFIRMED,
        evidence = listOf(evidence),
        storedAt = now,
        expiresAt = now.plusSeconds(60),
    )

    @Test
    fun `in memory cache hits before expiry and reports expiration`() {
        val cache = InMemoryRecordingEvidenceCache()
        cache.put(entry)

        val hit = cache.get(key, now.plusSeconds(30))
        assertTrue(hit.hit)
        assertEquals(entry, hit.entry)

        val expired = cache.get(key, now.plusSeconds(61))
        assertFalse(expired.hit)
        assertTrue(expired.expired)
        assertEquals(entry, expired.entry)
    }

    @Test
    fun `file cache survives a new instance and tolerates corruption`() {
        val dir = java.io.File(
            System.getProperty("java.io.tmpdir"),
            "musical-age-cache-test-${System.nanoTime()}",
        ).apply { mkdirs() }
        try {
            FileRecordingEvidenceCache(dir).put(entry)
            val reopened = FileRecordingEvidenceCache(dir)
            assertTrue(reopened.get(key, now.plusSeconds(30)).hit)

            dir.resolve("musical-age-evidence-cache-v1.json").writeText("{broken")
            val corrupted = FileRecordingEvidenceCache(dir)
            assertFalse(corrupted.get(key, now.plusSeconds(30)).hit)
            corrupted.put(entry)
            assertTrue(corrupted.get(key, now.plusSeconds(30)).hit)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `cache key changes with identity and matching policy`() {
        val song = SongMetadata(
            songId = "song-1",
            title = "Fixture Song",
            artists = listOf("Fixture Artist"),
            durationMillis = 240_000L,
        )
        val first = RecordingEvidenceCacheKey.forMetadata(song, "policy-v1")
        val second = RecordingEvidenceCacheKey.forMetadata(song.copy(title = "Other"), "policy-v1")
        val third = RecordingEvidenceCacheKey.forMetadata(song, "policy-v2")

        assertFalse(first == second)
        assertFalse(first == third)
    }

    @Test
    fun `in memory cache evicts oldest entries at the quota`() {
        val cache = InMemoryRecordingEvidenceCache(maxEntries = 1)
        cache.put(entry)
        cache.put(
            entry.copy(
                key = "b".repeat(64),
                storedAt = now.plusSeconds(1),
                expiresAt = now.plusSeconds(60),
            )
        )

        assertFalse(cache.get(key, now.plusSeconds(2)).hit)
        assertTrue(cache.get("b".repeat(64), now.plusSeconds(2)).hit)
    }

    @Test
    fun `file cache rejects an oversized file without reading it as text`() {
        val dir = java.io.File(
            System.getProperty("java.io.tmpdir"),
            "musical-age-cache-oversize-${System.nanoTime()}",
        ).apply { mkdirs() }
        try {
            val file = dir.resolve("musical-age-evidence-cache-v1.json")
            file.writeBytes(ByteArray(3 * 1024 * 1024) { 'x'.code.toByte() })
            val cache = FileRecordingEvidenceCache(dir, maxBytes = 1024)

            assertFalse(cache.get(key, now).hit)
            assertTrue(cache.hadCorruptFile())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `invalid key or schema is not persisted`() {
        val dir = java.io.File(
            System.getProperty("java.io.tmpdir"),
            "musical-age-cache-schema-${System.nanoTime()}",
        ).apply { mkdirs() }
        try {
            val cache = FileRecordingEvidenceCache(dir)
            assertFalse(cache.put(entry.copy(key = "short")))
            assertFalse(cache.put(entry.copy(schemaVersion = 99)))
            assertTrue(cache.put(entry))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a clear prevents an older operation from repopulating the cache`() {
        val cache = InMemoryRecordingEvidenceCache()
        val generation = cache.generation()
        assertTrue(cache.clear())

        assertFalse(cache.put(entry, generation))
        assertFalse(cache.get(key, now).hit)
        assertTrue(cache.put(entry, cache.generation()))
        assertTrue(cache.get(key, now).hit)
    }
}
