/*
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.xenowave.playback.stream

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Instant

class InnerTubeXResolverTest {

    @Test
    fun normalStreamMapsToNoneRangePolicy() {
        assertEquals(
            RangePolicy.None,
            rangePolicyFor(useRangeChunks = false, requireBoundedRange = false, rangeChunkSizeBytes = 0L),
        )
    }

    @Test
    fun requireBoundedRangeMapsToBounded() {
        assertEquals(
            RangePolicy.Bounded(12345L),
            rangePolicyFor(useRangeChunks = false, requireBoundedRange = true, rangeChunkSizeBytes = 12345L),
        )
    }

    @Test
    fun useRangeChunksMapsToChunked() {
        assertEquals(
            RangePolicy.Chunked(524288L),
            rangePolicyFor(useRangeChunks = true, requireBoundedRange = false, rangeChunkSizeBytes = 524288L),
        )
    }

    @Test
    fun useRangeChunksWinsOverBoundedRange() {
        assertEquals(
            RangePolicy.Chunked(111L),
            rangePolicyFor(useRangeChunks = true, requireBoundedRange = true, rangeChunkSizeBytes = 111L),
        )
    }

    @Test
    fun zeroChunkSizeFallsBackToNone() {
        assertEquals(
            RangePolicy.None,
            rangePolicyFor(useRangeChunks = true, requireBoundedRange = true, rangeChunkSizeBytes = 0L),
        )
    }

    @Test
    fun extractedStreamMapsToResolvedStream() {
        val resolved = mapExtractedStream(
            url = "https://example.invalid/audio",
            headers = mapOf("User-Agent" to "ua", "Origin" to "origin"),
            clientName = "WEB_REMIX",
            expiresAtEpochMs = 123456789L,
            itag = 140,
            mimeType = "audio/mp4",
            codecs = "mp4a.40.2",
            bitrate = 130000,
            sampleRate = 44100,
            contentLengthBytes = 123456L,
            loudnessDb = -14.0,
            perceptualLoudnessDb = -13.5,
            rangePolicy = RangePolicy.Bounded(4096L),
            playbackTrackingUrl = "https://example.invalid/track",
            videoLengthSeconds = "245",
        )

        assertEquals("https://example.invalid/audio", resolved.url)
        assertEquals(mapOf("User-Agent" to "ua", "Origin" to "origin"), resolved.headers)
        assertEquals("WEB_REMIX", resolved.clientName)
        assertEquals(123456789L, resolved.expiresAtEpochMs)
        assertEquals(140, resolved.format.itag)
        assertEquals("audio/mp4", resolved.format.mimeType)
        assertEquals("mp4a.40.2", resolved.format.codecs)
        assertEquals(130000, resolved.format.bitrate)
        assertEquals(44100, resolved.format.sampleRate)
        assertEquals(123456L, resolved.format.contentLength)
        assertEquals(-14.0, resolved.format.loudnessDb!!, 0.0)
        assertEquals(-13.5, resolved.format.perceptualLoudnessDb!!, 0.0)
        assertEquals(RangePolicy.Bounded(4096L), resolved.rangePolicy)
        assertEquals("https://example.invalid/track", resolved.playbackTrackingUrl)
        assertEquals("245", resolved.videoLengthSeconds)
    }

    @Test
    fun extractedStreamPreservesUnknownContentLength() {
        val resolved = mapExtractedStream(
            url = "https://example.invalid/audio",
            headers = emptyMap(),
            clientName = "WEB_REMIX",
            expiresAtEpochMs = 123456789L,
            itag = 140,
            mimeType = "audio/mp4",
            codecs = "mp4a.40.2",
            bitrate = 130000,
            sampleRate = 44100,
            contentLengthBytes = null,
            loudnessDb = null,
            perceptualLoudnessDb = null,
            rangePolicy = RangePolicy.None,
            playbackTrackingUrl = null,
            videoLengthSeconds = null,
        )

        assertEquals(null, resolved.format.contentLength)
    }

    @Test
    fun nullExpirationUsesFallback() {
        assertEquals(
            1000L + FALLBACK_EXPIRY_MS,
            expiresAtEpochMsFor(expiresAt = null, nowMs = 1000L),
        )
    }

    @Test
    fun presentExpirationUsesIt() {
        val instant = Instant.fromEpochMilliseconds(999L)
        assertEquals(999L, expiresAtEpochMsFor(expiresAt = instant, nowMs = 1000L))
    }
}
