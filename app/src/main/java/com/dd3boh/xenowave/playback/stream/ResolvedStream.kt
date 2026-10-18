/*
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.xenowave.playback.stream

/**
 * Contract passed from a [StreamResolver] to Media3/Download.
 *
 * This type is intentionally free of any YouTube/InnerTube/NewPipeExtractor dependency so that the
 * media layer never has to know how a stream was resolved.
 */
data class ResolvedStream(
    val url: String,
    val headers: Map<String, String>,
    val clientName: String,
    val expiresAtEpochMs: Long,
    val format: ResolvedFormat,
    val rangePolicy: RangePolicy,
    /**
     * Playback-tracking URL retained for parity with the legacy [com.dd3boh.xenowave.utils.YTPlayerUtils.PlaybackData]
     * and persisted into [com.dd3boh.xenowave.db.entities.FormatEntity].
     */
    val playbackTrackingUrl: String?,
    /**
     * Raw length-in-seconds retained for metadata recovery without exposing the legacy player
     * response. The value is intentionally kept as a String so the caller performs the same
     * conversion it historically did.
     */
    val videoLengthSeconds: String?,
)