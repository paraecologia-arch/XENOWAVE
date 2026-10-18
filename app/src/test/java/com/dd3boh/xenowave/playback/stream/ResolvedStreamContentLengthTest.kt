package com.dd3boh.xenowave.playback.stream

import com.dd3boh.xenowave.db.entities.FormatEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolvedStreamContentLengthTest {

    @Test
    fun extractorLengthIsUsedWithoutProbe() {
        val stream = stream(contentLength = 100L)

        val result = stream.withKnownContentLength(persistedFormat = null) {
            error("A probe must not run when the extractor already supplied the length")
        }

        assertEquals(100L, result.format.contentLength)
    }

    @Test
    fun matchingPersistedFormatAvoidsProbe() {
        val stream = stream(contentLength = null)

        val result = stream.withKnownContentLength(persistedFormat = format(contentLength = 123L)) {
            error("A probe must not run when a matching persisted format has the length")
        }

        assertEquals(123L, result.format.contentLength)
    }

    @Test
    fun mismatchingPersistedFormatUsesProbe() {
        val stream = stream(contentLength = null)
        var probeCalls = 0

        val result = stream.withKnownContentLength(
            persistedFormat = format(contentLength = 123L, sampleRate = 48000),
        ) {
            probeCalls++
            456L
        }

        assertEquals(456L, result.format.contentLength)
        assertEquals(1, probeCalls)
    }

    @Test
    fun unknownLengthContinuesWithoutContentLengthWhenProbeFails() {
        val stream = stream(contentLength = null)

        val result = stream.withKnownContentLength(persistedFormat = null) { null }

        assertFalse(result.format.contentLength != null)
    }

    @Test
    fun invalidPersistedLengthIsIgnored() {
        val stream = stream(contentLength = null)
        var probeCalls = 0

        val result = stream.withKnownContentLength(persistedFormat = format(contentLength = 0L)) {
            probeCalls++
            789L
        }

        assertEquals(789L, result.format.contentLength)
        assertEquals(1, probeCalls)
    }

    @Test
    fun discoveredProbeLengthIsApplied() {
        val stream = stream(contentLength = null)

        val result = stream.withKnownContentLength(persistedFormat = null) { 987L }

        assertTrue(result.format.contentLength == 987L)
    }

    private fun stream(contentLength: Long?) = ResolvedStream(
        url = "https://example.invalid/audio",
        headers = emptyMap(),
        clientName = "WEB_REMIX",
        expiresAtEpochMs = Long.MAX_VALUE,
        format = ResolvedFormat(
            itag = 140,
            mimeType = "audio/mp4",
            codecs = "mp4a.40.2",
            bitrate = 128000,
            sampleRate = 44100,
            contentLength = contentLength,
            loudnessDb = null,
            perceptualLoudnessDb = null,
        ),
        rangePolicy = RangePolicy.Chunked(4096L),
        playbackTrackingUrl = null,
        videoLengthSeconds = null,
    )

    private fun format(
        contentLength: Long,
        sampleRate: Int? = 44100,
    ) = FormatEntity(
        id = "mediaId",
        itag = 140,
        mimeType = "audio/mp4",
        codecs = "mp4a.40.2",
        bitrate = 128000,
        sampleRate = sampleRate,
        contentLength = contentLength,
    )
}
