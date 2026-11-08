package com.dd3boh.xenowave.insights.musicalage.metadata

import com.dd3boh.xenowave.insights.musicalage.content.SongMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class MusicBrainzJsonParserTest {
    private val recordingId = "11111111-1111-1111-1111-111111111111"
    private val releaseId = "22222222-2222-2222-2222-222222222222"
    private val groupId = "33333333-3333-3333-3333-333333333333"

    private fun recordingObject(
        id: String = recordingId,
        firstReleaseDate: String? = "1993-05-10",
        releaseGroupDate: String = "1993-05-10",
    ): String = """
        {
          "id": "$id",
          "score": 100,
          "title": "Fixture Song",
          "length": 241000,
          "first-release-date": ${firstReleaseDate?.let { "\"$it\"" } ?: "null"},
          "artist-credit": [
            {"name": "Fixture Artist", "artist": {"id": "artist-id", "name": "Fixture Artist"}}
          ],
          "isrcs": ["BRAAA0000001"],
          "releases": [
            {
              "id": "$releaseId",
              "title": "Fixture Album",
              "date": "1994-01-01",
              "release-group": {
                "id": "$groupId",
                "first-release-date": "$releaseGroupDate",
                "primary-type": "Album",
                "secondary-types": ["Compilation"]
              }
            }
          ]
        }
    """.trimIndent()

    private fun json(
        id: String = recordingId,
        firstReleaseDate: String? = "1993-05-10",
        releaseGroupDate: String = "1993-05-10",
    ): String = """{"recordings":[${recordingObject(id, firstReleaseDate, releaseGroupDate)}]}"""

    @Test
    fun `parses recording first release and linked identifiers`() {
        val candidate = MusicBrainzJsonParser.parseRecordingSearch(json(), 10).single()

        assertEquals(recordingId, candidate.providerId)
        assertEquals("Fixture Song", candidate.title)
        assertEquals(listOf("Fixture Artist"), candidate.artists)
        assertEquals(241_000L, candidate.durationMillis)
        assertEquals("1993-05-10", candidate.firstReleaseDate)
        assertEquals(CandidateDateSource.RECORDING_FIRST_RELEASE, candidate.dateSource)
        assertEquals(releaseId, candidate.releaseId)
        assertEquals(groupId, candidate.releaseGroupId)
        assertEquals(listOf("BRAAA0000001"), candidate.isrcs)
        assertEquals(false, candidate.hasNonCompilationRelease)
    }

    @Test
    fun `release group date is labelled as such and not promoted`() {
        val candidate = MusicBrainzJsonParser.parseRecordingSearch(
            json(firstReleaseDate = null, releaseGroupDate = "1992"),
            10,
        ).single()

        assertEquals("1992", candidate.firstReleaseDate)
        assertEquals(CandidateDateSource.RELEASE_GROUP, candidate.dateSource)
    }

    @Test
    fun `invalid musicbrainz id and malformed json are rejected`() {
        val invalid = MusicBrainzJsonParser.parseRecordingSearch(json(id = "not-a-uuid"), 10)
        assertTrue(invalid.isEmpty())

        try {
            MusicBrainzJsonParser.parseRecordingSearch("{", 10)
            throw AssertionError("expected MetadataParseException")
        } catch (_: MetadataParseException) {
            // expected
        }
    }

    @Test
    fun `candidate count is capped`() {
        val combined = """{"recordings":[
            ${recordingObject()},
            ${recordingObject(id = "44444444-4444-4444-4444-444444444444")}
        ]}"""

        val parsed = MusicBrainzJsonParser.parseRecordingSearch(combined, 1)
        assertEquals(1, parsed.size)
    }

    @Test
    fun `query escapes lucene syntax and encodes the search`() {
        assertEquals("""a\:b\-c""", MusicBrainzQuery.escapeLucene("a:b-c"))
        val url = MusicBrainzQuery.searchUrl(
            SongMetadata(
                songId = "song-1",
                title = "A \"Song\" & More",
                artists = listOf("A/B"),
                durationMillis = 240_000L,
            ),
            10,
        )
        assertTrue(url.startsWith("https://musicbrainz.org/ws/2/recording/?query="))
        assertTrue(url.contains("limit=10"))
        assertTrue(url.contains("%5C"))
    }

    @Test
    fun `retry after accepts seconds and http date`() {
        assertEquals(
            5_000L,
            retryAfterMillis(mapOf("retry-after" to "5"), 1_000L),
        )
        assertEquals(
            0L,
            retryAfterMillis(
                mapOf("retry-after" to "Wed, 21 Oct 2015 07:28:00 GMT"),
                1_500_000_000_000L,
            ),
        )
    }

    @Test
    fun `curated public compilation-only sample is not promoted to first release`() {
        val publicSample = """
            {
              "recordings": [{
                "id": "03e4c323-ccf9-441e-99e6-3e1f652565b1",
                "score": 100,
                "title": "Hey Jude",
                "length": 427000,
                "first-release-date": "2001",
                "artist-credit": [{"name": "The Beatles", "artist": {"name": "The Beatles"}}],
                "releases": [{
                  "id": "04a2f7f1-1061-4a5e-bdae-32f893323822",
                  "title": "1",
                  "date": "2015-11-06",
                  "release-group": {
                    "id": "4f539c70-1a19-37a3-ba14-979f11fc0686",
                    "primary-type": "Album",
                    "secondary-types": ["Compilation"]
                  }
                }]
              }]
            }
        """.trimIndent()
        val candidate = MusicBrainzJsonParser.parseRecordingSearch(publicSample, 10).single()
        val result = RecordingMatcher().match(
            com.dd3boh.xenowave.insights.musicalage.content.SongMetadata(
                songId = "song-1",
                title = "Hey Jude",
                artists = listOf("The Beatles"),
                durationMillis = 427_000L,
            ),
            listOf(candidate),
            today = LocalDate.of(2026, 11, 6),
            obtainedAt = Instant.parse("2026-11-06T12:00:00Z"),
            search = SearchCompleteness.unknown(1, 10),
        )

        assertEquals(RecordingMatchState.CANDIDATE, result.state)
        assertEquals(false, candidate.hasNonCompilationRelease)
    }

    @Test
    fun `search completeness preserves count offset and discarded reasons`() {
        val page = MusicBrainzJsonParser.parseRecordingSearchPage(
            """
            {"count": 7, "offset": 0, "recordings": [
                ${recordingObject()},
                "not an object"
            ]}
            """.trimIndent(),
            10,
        )

        assertEquals(7, page.completeness.totalCount)
        assertEquals(2, page.completeness.returnedCount)
        assertTrue(!page.completeness.isComplete)
        assertEquals(1, page.discarded.size)
    }

    @Test
    fun `wrong field types discard only the candidate`() {
        val page = MusicBrainzJsonParser.parseRecordingSearchPage(
            """
            {"count": 1, "recordings": [
                {"id": "$recordingId", "title": "Fixture Song", "artist-credit": "not-an-array"}
            ]}
            """.trimIndent(),
            10,
        )

        assertTrue(page.candidates.isEmpty())
        assertEquals(1, page.discarded.size)
    }

    @Test
    fun `root structure with wrong recordings type is a metadata error`() {
        try {
            MusicBrainzJsonParser.parseRecordingSearchPage("""{"recordings": "nope"}""", 10)
            throw AssertionError("expected MetadataParseException")
        } catch (_: MetadataParseException) {
            // expected
        }
    }

    @Test
    fun `release without release-group types does not corroborate the date`() {
        val page = MusicBrainzJsonParser.parseRecordingSearchPage(
            """
            {"count": 1, "recordings": [{
              "id": "$recordingId",
              "title": "Fixture Song",
              "length": 241000,
              "first-release-date": "1993-05-10",
              "artist-credit": [{"name": "Fixture Artist"}],
              "releases": [{
                "id": "$releaseId",
                "title": "Fixture Album",
                "date": "1993-05-10"
              }]
            }]}
            """.trimIndent(),
            10,
        )
        val candidate = page.candidates.single()

        assertNull(candidate.corroboratingRelease)
        assertEquals(null, candidate.hasNonCompilationRelease)
    }

    @Test
    fun `known non compilation release with the same date corroborates`() {
        val page = MusicBrainzJsonParser.parseRecordingSearchPage(
            """
            {"count": 1, "recordings": [{
              "id": "$recordingId",
              "title": "Fixture Song",
              "length": 241000,
              "first-release-date": "1993-05-10",
              "artist-credit": [{"name": "Fixture Artist"}],
              "releases": [{
                "id": "$releaseId",
                "title": "Fixture Album",
                "date": "1993-05-10",
                "release-group": {
                  "id": "$groupId",
                  "primary-type": "Album",
                  "secondary-types": []
                }
              }]
            }]}
            """.trimIndent(),
            10,
        )
        val candidate = page.candidates.single()

        assertNotNull(candidate.corroboratingRelease)
        assertEquals("1993-05-10", candidate.corroboratingRelease?.date)
        assertEquals(true, candidate.hasNonCompilationRelease)
    }
}
