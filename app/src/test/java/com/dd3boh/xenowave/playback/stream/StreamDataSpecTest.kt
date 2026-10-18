/*
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.xenowave.playback.stream

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StreamDataSpecTest {

    private val uri: Uri = Uri.parse("https://example.invalid/stream")

    private fun dataSpec(
        position: Long = 0L,
        length: Long = C.LENGTH_UNSET.toLong(),
        headers: Map<String, String> = emptyMap(),
    ) = DataSpec.Builder()
        .setUri(uri)
        .setPosition(position)
        .setLength(length)
        .setHttpRequestHeaders(headers)
        .build()

    private fun stream(
        headers: Map<String, String>,
        rangePolicy: RangePolicy,
    ) = ResolvedStream(
        url = "https://example.invalid/stream",
        headers = headers,
        clientName = "WEB_REMIX",
        expiresAtEpochMs = Long.MAX_VALUE,
        format = ResolvedFormat(
            itag = 140,
            mimeType = "audio/mp4",
            codecs = "mp4a.40.2",
            bitrate = 130000,
            sampleRate = 44100,
            contentLength = 123456L,
            loudnessDb = null,
            perceptualLoudnessDb = null,
        ),
        rangePolicy = rangePolicy,
        playbackTrackingUrl = null,
        videoLengthSeconds = null,
    )

    @Test
    fun positionZeroIsPreserved() {
        val result = dataSpec(position = 0L, length = 100L)
            .withResolvedStream(stream(emptyMap(), RangePolicy.None))

        assertEquals(0L, result.position)
        assertEquals(100L, result.length)
    }

    @Test
    fun position524288IsPreserved() {
        val result = dataSpec(position = 524288L, length = 524288L)
            .withResolvedStream(stream(emptyMap(), RangePolicy.None))

        assertEquals(524288L, result.position)
    }

    @Test
    fun position1048576IsPreserved() {
        val result = dataSpec(position = 1048576L, length = 524288L)
            .withResolvedStream(stream(emptyMap(), RangePolicy.None))

        assertEquals(1048576L, result.position)
    }

    @Test
    fun headersAreMergedWithExistingOnes() {
        val result = dataSpec(headers = mapOf("Range" to "bytes=0-0"))
            .withResolvedStream(
                stream(
                    headers = mapOf("User-Agent" to "ua", "Origin" to "origin"),
                    rangePolicy = RangePolicy.None,
                ),
            )

        assertEquals("bytes=0-0", result.httpRequestHeaders["Range"])
        assertEquals("ua", result.httpRequestHeaders["User-Agent"])
        assertEquals("origin", result.httpRequestHeaders["Origin"])
    }

    @Test
    fun noneRangePolicyDoesNotLimitLength() {
        val result = dataSpec(position = 524288L, length = 999999L)
            .withResolvedStream(stream(emptyMap(), RangePolicy.None))

        assertEquals(999999L, result.length)
        assertEquals(524288L, result.position)
    }

    @Test
    fun boundedRangeLimitsUnsetLengthWithoutChangingPosition() {
        val result = dataSpec(position = 524288L, length = C.LENGTH_UNSET.toLong())
            .withResolvedStream(stream(emptyMap(), RangePolicy.Bounded(4096L)))

        assertEquals(524288L, result.position)
        assertEquals(4096L, result.length)
    }

    @Test
    fun chunkedRangeLimitsKnownLengthToMin() {
        val result = dataSpec(position = 1048576L, length = 100000L)
            .withResolvedStream(stream(emptyMap(), RangePolicy.Chunked(524288L)))

        assertEquals(1048576L, result.position)
        assertEquals(100000L, result.length)
    }

    @Test
    fun chunkedRangeCapsLengthWhenDeclaredSmaller() {
        val result = dataSpec(position = 0L, length = 100000L)
            .withResolvedStream(stream(emptyMap(), RangePolicy.Chunked(4096L)))

        assertEquals(0L, result.position)
        assertEquals(4096L, result.length)
    }
}
