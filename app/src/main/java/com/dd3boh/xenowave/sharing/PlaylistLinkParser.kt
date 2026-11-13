/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.sharing

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

data class PlaylistLink(
    val host: String,
    val playlistId: String,
    val normalizedUrl: String,
)

/**
 * Extracts a YouTube/YouTube Music playlist link from a share message that may
 * contain the download link as well. The download link is never treated as a
 * playlist and the raw message text is never converted to a URI wholesale.
 */
object PlaylistLinkParser {
    private const val MAX_TEXT_LENGTH = 100_000
    private val URL_REGEX = Regex("""(?i)https://[^\s<>"']+""")
    private val PLAYLIST_ID = Regex("""^[A-Za-z0-9_-]{6,128}$""")
    private val PLAYLIST_HOSTS = setOf(
        "music.youtube.com",
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
    )

    fun find(text: String): PlaylistLink? {
        if (text.length > MAX_TEXT_LENGTH) return null
        val cleaned = text.replace("&amp;", "&")
        val candidates = URL_REGEX.findAll(cleaned)
            .map { match -> match.value.trimEnd('.', ',', ')', ']', '}', ';') }
            .mapNotNull(::parse)
            .toList()
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull {
            it.host == "music.youtube.com" && it.normalizedUrl.contains("/playlist")
        } ?: candidates.first()
    }

    fun parse(rawUrl: String): PlaylistLink? {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.userInfo != null || uri.port != -1) return null
        val host = uri.host?.lowercase() ?: return null
        if (host !in PLAYLIST_HOSTS) return null

        val path = uri.path?.trimEnd('/') ?: return null
        if (path != "/playlist" && path != "/watch") return null

        val playlistId = queryParameters(uri.rawQuery)["list"] ?: return null
        if (!PLAYLIST_ID.matches(playlistId)) return null

        return PlaylistLink(
            host = host,
            playlistId = playlistId,
            normalizedUrl = "https://music.youtube.com/playlist?list=" +
                URLEncoder.encode(playlistId, "UTF-8"),
        )
    }

    private fun queryParameters(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return buildMap {
            rawQuery.split('&').forEach { pair ->
                val separator = pair.indexOf('=')
                if (separator <= 0) return@forEach
                val key = URLDecoder.decode(pair.substring(0, separator), "UTF-8")
                val value = URLDecoder.decode(pair.substring(separator + 1), "UTF-8")
                if (key.isNotEmpty() && value.isNotEmpty()) put(key, value)
            }
        }
    }
}
