package com.dd3boh.xenowave.playback.stream

private val CONTENT_RANGE_TOTAL_BYTES = Regex("^bytes\\s+\\d+-\\d+/(\\d+)$", RegexOption.IGNORE_CASE)

fun parseContentRangeTotalBytes(contentRange: String): Long? =
    CONTENT_RANGE_TOTAL_BYTES.find(contentRange.trim())
        ?.groupValues
        ?.getOrNull(1)
        ?.toLongOrNull()
