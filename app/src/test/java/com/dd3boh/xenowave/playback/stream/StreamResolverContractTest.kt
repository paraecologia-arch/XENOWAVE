/*
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.xenowave.playback.stream

import com.dd3boh.xenowave.utils.YTPlayerUtils
import com.zionhuang.innertube.models.Thumbnail
import com.zionhuang.innertube.models.Thumbnails
import com.zionhuang.innertube.models.response.PlayerResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamResolverContractTest {

    private fun format(
        itag: Int = 140,
        mimeType: String = "audio/mp4; codecs=\"mp4a.40.2\"",
        bitrate: Int = 130000,
        contentLength: Long? = 12345678L,
        audioSampleRate: Int? = 44100,
    ) = PlayerResponse.StreamingData.Format(
        itag = itag,
        url = null,
        mimeType = mimeType,
        bitrate = bitrate,
        width = null,
        height = null,
        contentLength = contentLength,
        quality = "tiny",
        fps = null,
        qualityLabel = null,
        averageBitrate = null,
        audioQuality = "AUDIO_QUALITY_LOW",
        approxDurationMs = "240000",
        audioSampleRate = audioSampleRate,
        audioChannels = 2,
        loudnessDb = -13.0,
        lastModified = null,
        signatureCipher = null,
    )

    private fun audioConfig() = PlayerResponse.PlayerConfig.AudioConfig(
        loudnessDb = -14.0,
        perceptualLoudnessDb = -13.5,
    )

    private fun playbackData(
        audioConfig: PlayerResponse.PlayerConfig.AudioConfig? = audioConfig(),
        videoDetails: PlayerResponse.VideoDetails? = null,
        playbackTracking: PlayerResponse.PlaybackTracking? = null,
        format: PlayerResponse.StreamingData.Format = format(),
        streamUrl: String = "https://example.invalid/stream",
        streamExpiresInSeconds: Int = 60,
        clientName: String = "IOS",
        mediaHeaders: Map<String, String> = emptyMap(),
    ) = YTPlayerUtils.PlaybackData(
        audioConfig = audioConfig,
        videoDetails = videoDetails,
        playbackTracking = playbackTracking,
        format = format,
        streamUrl = streamUrl,
        streamExpiresInSeconds = streamExpiresInSeconds,
        clientName = clientName,
        mediaHeaders = mediaHeaders,
    )

    @Test
    fun mapsFormatToResolvedFormat() {
        val resolved = toResolvedFormat(format(), audioConfig())

        assertEquals(140, resolved.itag)
        assertEquals("audio/mp4", resolved.mimeType)
        assertEquals("mp4a.40.2", resolved.codecs)
        assertEquals(130000, resolved.bitrate)
        assertEquals(44100, resolved.sampleRate)
        assertEquals(12345678L, resolved.contentLength)
        assertEquals(-14.0, resolved.loudnessDb!!, 0.0)
        assertEquals(-13.5, resolved.perceptualLoudnessDb!!, 0.0)
    }

    @Test
    fun mapsExpiresInSecondsToEpochMillis() {
        val nowMs = 1_000_000L
        val resolved = playbackData(streamExpiresInSeconds = 2100)
            .toResolvedStream(nowMs = nowMs)

        assertEquals(nowMs + 2100 * 1000L, resolved.expiresAtEpochMs)
    }

    @Test
    fun propagatesHeadersExactly() {
        val headers = mapOf(
            "User-Agent" to "com.google.ios.youtube/20.10.4",
            "X-Custom" to "value",
        )

        val resolved = playbackData(mediaHeaders = headers).toResolvedStream(nowMs = 0L)

        assertEquals(headers, resolved.headers)
    }

    @Test
    fun propagatesClientName() {
        val resolved = playbackData(clientName = "WEB_REMIX").toResolvedStream(nowMs = 0L)

        assertEquals("WEB_REMIX", resolved.clientName)
    }

    @Test
    fun usesLegacyChunkedRangePolicy() {
        val resolved = playbackData().toResolvedStream(nowMs = 0L)

        assertTrue(resolved.rangePolicy is RangePolicy.LegacyChunked)
        assertEquals(
            RangePolicy.LEGACY_CHUNK_SIZE_BYTES,
            (resolved.rangePolicy as RangePolicy.LegacyChunked).chunkSizeBytes,
        )
    }

    @Test
    fun mapsMetadataFieldsForParity() {
        val videoDetails = PlayerResponse.VideoDetails(
            videoId = "video",
            title = "title",
            author = "author",
            channelId = "channel",
            lengthSeconds = "245",
            musicVideoType = null,
            viewCount = "1000",
            thumbnail = Thumbnails(
                thumbnails = listOf(Thumbnail(url = "https://example.invalid/thumb.jpg", width = 120, height = 90)),
            ),
        )
        val playbackTracking = PlayerResponse.PlaybackTracking(
            videostatsPlaybackUrl = PlayerResponse.PlaybackTracking.VideostatsPlaybackUrl(
                baseUrl = "https://example.invalid/track",
            ),
            videostatsWatchtimeUrl = null,
            atrUrl = null,
        )

        val resolved = playbackData(
            videoDetails = videoDetails,
            playbackTracking = playbackTracking,
        ).toResolvedStream(nowMs = 0L)

        assertEquals("245", resolved.videoLengthSeconds)
        assertEquals("https://example.invalid/track", resolved.playbackTrackingUrl)
    }

    @Test
    fun resolvedStreamDoesNotLeakInnertubeTypes() {
        val forbiddenPrefix = "com.zionhuang.innertube"

        listOf(
            ResolvedStream::class,
            ResolvedFormat::class,
            RangePolicy::class,
        ).forEach { type ->
            val qualifiedName = type.qualifiedName
            assertFalse(
                "${type.simpleName} lives in $qualifiedName",
                qualifiedName?.startsWith(forbiddenPrefix) == true,
            )
        }
    }
}