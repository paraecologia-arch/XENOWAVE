/*
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.xenowave.playback.stream

/**
 * Format information carried by a [ResolvedStream], independent of any InnerTube model.
 */
data class ResolvedFormat(
    val itag: Int,
    val mimeType: String,
    val codecs: String,
    val bitrate: Int,
    val sampleRate: Int?,
    val contentLength: Long?,
    val loudnessDb: Double?,
    val perceptualLoudnessDb: Double?,
)

/**
 * Describes how Media3 should request bytes from the resolved stream.
 */
sealed interface RangePolicy {

    /**
     * No byte-range restriction: Media3 keeps its own position/length unchanged.
     */
    data object None : RangePolicy

    /**
     * Requests must be bounded to [maxBytes].
     */
    data class Bounded(val maxBytes: Long) : RangePolicy

    /**
     * Requests must be split into chunks of at most [chunkSizeBytes].
     */
    data class Chunked(val chunkSizeBytes: Long) : RangePolicy

    /**
     * Legacy fixed chunk policy.
     */
    data class LegacyChunked(val chunkSizeBytes: Long) : RangePolicy

    companion object {
        /**
         * Legacy chunk size kept in sync with the value historically used by [com.dd3boh.xenowave.playback.MusicService].
         */
        const val LEGACY_CHUNK_SIZE_BYTES = 512 * 1024L
    }
}

/**
 * Returns the byte length cap this policy imposes, or null when no restriction applies.
 */
fun RangePolicy.boundedBytes(): Long? = when (this) {
    is RangePolicy.None -> null
    is RangePolicy.Bounded -> maxBytes
    is RangePolicy.Chunked -> chunkSizeBytes
    is RangePolicy.LegacyChunked -> chunkSizeBytes
}
