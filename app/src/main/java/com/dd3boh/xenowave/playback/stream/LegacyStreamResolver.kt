/*
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.xenowave.playback.stream

import com.dd3boh.xenowave.constants.AudioQuality
import com.dd3boh.xenowave.utils.YTPlayerUtils
import com.zionhuang.innertube.models.response.PlayerResponse

/**
 * Adapter that keeps the existing legacy stream resolution ([YTPlayerUtils]) while exposing it
 * through the [StreamResolver] boundary. It must remain behaviorally equivalent to the legacy
 * path; it only translates [YTPlayerUtils.PlaybackData] into [ResolvedStream].
 */
class LegacyStreamResolver : StreamResolver {

    override suspend fun resolve(
        videoId: String,
        audioQuality: AudioQuality,
        isActiveNetworkMetered: Boolean,
        playlistId: String?,
    ): Result<ResolvedStream> =
        YTPlayerUtils.playerResponseForPlayback(
            videoId = videoId,
            playlistId = playlistId,
            audioQuality = audioQuality,
            isActiveNetworkMetered = isActiveNetworkMetered,
        ).map { it.toResolvedStream() }
}

/**
 * Maps the legacy player format into the media-layer independent [ResolvedFormat].
 *
 * The parsing below is intentionally identical to the one previously performed by
 * MusicService/DownloadUtil when persisting [com.dd3boh.xenowave.db.entities.FormatEntity].
 */
internal fun toResolvedFormat(
    format: PlayerResponse.StreamingData.Format,
    audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
): ResolvedFormat = ResolvedFormat(
    itag = format.itag,
    mimeType = format.mimeType.split(";")[0],
    codecs = format.mimeType.split("codecs=")[1].removeSurrounding("\""),
    bitrate = format.bitrate,
    sampleRate = format.audioSampleRate,
    contentLength = format.contentLength,
    loudnessDb = audioConfig?.loudnessDb,
    perceptualLoudnessDb = audioConfig?.perceptualLoudnessDb,
)

/**
 * Translates the legacy playback data into a [ResolvedStream].
 *
 * @param nowMs injectable clock for deterministic testing.
 */
internal fun YTPlayerUtils.PlaybackData.toResolvedStream(
    nowMs: Long = System.currentTimeMillis(),
): ResolvedStream = ResolvedStream(
    url = streamUrl,
    headers = mediaHeaders,
    clientName = clientName,
    expiresAtEpochMs = nowMs + streamExpiresInSeconds * 1000L,
    format = toResolvedFormat(format, audioConfig),
    rangePolicy = RangePolicy.LegacyChunked(RangePolicy.LEGACY_CHUNK_SIZE_BYTES),
    playbackTrackingUrl = playbackTracking?.videostatsPlaybackUrl?.baseUrl,
    videoLengthSeconds = videoDetails?.lengthSeconds,
)