package com.dd3boh.xenowave.playback.stream

import com.dd3boh.xenowave.db.entities.FormatEntity

/**
 * Completes a stream's content length without issuing a network probe when a length is already
 * available from the extractor or from a persisted format for the same media and stream format.
 */
internal fun ResolvedStream.withKnownContentLength(
    persistedFormat: FormatEntity?,
    probeContentLength: () -> Long?,
): ResolvedStream {
    val contentLength = format.contentLength
        ?.takeIf { it > 0L }
        ?: persistedFormat
            ?.takeIf { it.matches(format) }
            ?.contentLength
            ?.takeIf { it > 0L }
        ?: probeContentLength()
            ?.takeIf { it > 0L }
        ?: return this

    return copy(format = format.copy(contentLength = contentLength))
}

private fun FormatEntity.matches(format: ResolvedFormat): Boolean =
    itag == format.itag &&
        mimeType == format.mimeType &&
        codecs == format.codecs &&
        bitrate == format.bitrate &&
        sampleRate == format.sampleRate
