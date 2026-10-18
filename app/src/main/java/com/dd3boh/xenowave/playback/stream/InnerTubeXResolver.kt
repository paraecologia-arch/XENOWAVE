/*
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.xenowave.playback.stream

import android.util.Log
import com.dd3boh.xenowave.constants.AudioQuality
import com.metrolist.innertubex.extraction.ExtractedStream
import kotlin.time.Instant

/**
 * Stream resolver backed by InnerTubeX v0.2.1.
 *
 * This is the real extraction path for streaming: [InnerTubeXEngine] owns the extractor and this
 * resolver only translates its [ExtractedStream] into our media-layer independent [ResolvedStream].
 */
class InnerTubeXResolver(
    private val engine: InnerTubeXEngine = InnerTubeXEngine(),
) : StreamResolver {

    private val tag = "InnerTubeXResolver"

    override suspend fun resolve(
        videoId: String,
        audioQuality: AudioQuality,
        isActiveNetworkMetered: Boolean,
        playlistId: String?,
    ): Result<ResolvedStream> = runCatching {
        val stream = engine.resolve(
            videoId = videoId,
            playlistId = playlistId,
            audioQuality = audioQuality,
            isActiveNetworkMetered = isActiveNetworkMetered,
        )

        Log.d(
            tag,
            "resolved videoId=$videoId client=${stream.clientName} " +
                "profileId=${stream.profileId} requireBoundedRange=${stream.requireBoundedRange} " +
                "useRangeChunks=${stream.useRangeChunks} rangeChunkSizeBytes=${stream.rangeChunkSizeBytes} " +
                "headerCount=${stream.headers.size} " +
                "expiration=${if (stream.expiresAt != null) "present" else "absent"}",
        )

        stream.toResolvedStream()
    }
}

internal fun ExtractedStream.toResolvedStream(): ResolvedStream = mapExtractedStream(
    url = audioUrl,
    headers = headers,
    clientName = clientName,
    expiresAtEpochMs = expiresAtEpochMsFor(expiresAt),
    itag = itag,
    mimeType = mimeType.orEmpty(),
    codecs = codecs.orEmpty(),
    bitrate = bitrate ?: 0,
    sampleRate = sampleRate,
    contentLengthBytes = contentLengthBytes,
    loudnessDb = loudnessDb,
    perceptualLoudnessDb = perceptualLoudnessDb,
    rangePolicy = rangePolicyFor(useRangeChunks, requireBoundedRange, rangeChunkSizeBytes),
    playbackTrackingUrl = playbackTracking?.playbackUrl,
    videoLengthSeconds = mediaMetadata?.durationSeconds?.toString(),
)

internal fun mapExtractedStream(
    url: String,
    headers: Map<String, String>,
    clientName: String,
    expiresAtEpochMs: Long,
    itag: Int,
    mimeType: String,
    codecs: String,
    bitrate: Int,
    sampleRate: Int?,
    contentLengthBytes: Long?,
    loudnessDb: Double?,
    perceptualLoudnessDb: Double?,
    rangePolicy: RangePolicy,
    playbackTrackingUrl: String?,
    videoLengthSeconds: String?,
): ResolvedStream = ResolvedStream(
    url = url,
    headers = headers,
    clientName = clientName,
    expiresAtEpochMs = expiresAtEpochMs,
    format = ResolvedFormat(
        itag = itag,
        mimeType = mimeType,
        codecs = codecs,
        bitrate = bitrate,
        sampleRate = sampleRate,
        contentLength = contentLengthBytes,
        loudnessDb = loudnessDb,
        perceptualLoudnessDb = perceptualLoudnessDb,
    ),
    rangePolicy = rangePolicy,
    playbackTrackingUrl = playbackTrackingUrl,
    videoLengthSeconds = videoLengthSeconds,
)

internal fun rangePolicyFor(
    useRangeChunks: Boolean,
    requireBoundedRange: Boolean,
    rangeChunkSizeBytes: Long,
): RangePolicy = when {
    useRangeChunks && rangeChunkSizeBytes > 0 ->
        RangePolicy.Chunked(rangeChunkSizeBytes)

    requireBoundedRange && rangeChunkSizeBytes > 0 ->
        RangePolicy.Bounded(rangeChunkSizeBytes)

    else -> RangePolicy.None
}

internal fun expiresAtEpochMsFor(
    expiresAt: Instant?,
    nowMs: Long = System.currentTimeMillis(),
): Long = expiresAt?.toEpochMilliseconds() ?: nowMs + FALLBACK_EXPIRY_MS

internal const val FALLBACK_EXPIRY_MS = 5 * 60 * 1000L
