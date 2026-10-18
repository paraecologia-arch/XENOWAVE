/*
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.xenowave.playback.stream

import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec

/**
 * Applies the resolved stream's URL, headers and range policy to [this] DataSpec.
 *
 * The absolute byte position carried by [DataSpec.position] is preserved; only the requested
 * length is capped when the stream's [RangePolicy] demands bounded/chunked requests. The
 * [DataSpec.subrange] offset is always 0 so that Media3 keeps the current position.
 */
fun DataSpec.withResolvedStream(resolvedStream: ResolvedStream): DataSpec {
    val withUrl = withUri(resolvedStream.url.toUri())
    val withHeaders = withUrl.withRequestHeaders(
        httpRequestHeaders + resolvedStream.headers,
    )

    val maxLength = resolvedStream.rangePolicy.boundedBytes()
        ?: return withHeaders

    val boundedLength = if (length == C.LENGTH_UNSET.toLong()) {
        maxLength
    } else {
        minOf(length, maxLength)
    }

    return withHeaders.subrange(0L, boundedLength)
}
