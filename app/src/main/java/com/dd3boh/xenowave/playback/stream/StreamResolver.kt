/*
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.xenowave.playback.stream

import com.dd3boh.xenowave.constants.AudioQuality

/**
 * Resolves a YouTube stream into a media-layer friendly [ResolvedStream].
 *
 * The interface stays independent of Android framework types and of the InnerTube models, so the
 * consumer (Media3/Download) can depend only on this contract.
 */
interface StreamResolver {
    suspend fun resolve(
        videoId: String,
        audioQuality: AudioQuality,
        isActiveNetworkMetered: Boolean,
        playlistId: String? = null,
    ): Result<ResolvedStream>
}