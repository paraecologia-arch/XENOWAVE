/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.sharing

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class ExportSong(
    val videoId: String,
    val title: String,
    val artists: List<String>,
    val isLocal: Boolean,
)

data class PortableTrack(
    val videoId: String,
    val title: String,
    val artists: List<String>,
)

data class PortablePlaylist(
    val name: String,
    val tracks: List<PortableTrack>,
    val excludedLocalCount: Int,
)

sealed interface PlaylistExportResult {
    data class Success(
        val json: String,
        val playlist: PortablePlaylist,
    ) : PlaylistExportResult

    data class Failure(val reason: String) : PlaylistExportResult
}

sealed interface PlaylistParseResult {
    data class Valid(val playlist: PortablePlaylist) : PlaylistParseResult
    data class Invalid(val reason: String) : PlaylistParseResult
}

/**
 * Portable, versioned reference document. It contains only public YouTube
 * video identifiers, titles and artist names: no local paths, account data,
 * cookies, history or playback statistics.
 */
object PlaylistTransferFormat {
    const val MIME_TYPE = "application/vnd.xenowave.playlist+json"
    const val SCHEMA_VERSION = 1
    const val KIND = "xenowave-playlist-references"
    const val MAX_TRACKS = 500
    const val MAX_JSON_BYTES = 1_000_000
    private const val MAX_TITLE_LENGTH = 200
    private const val MAX_ARTIST_LENGTH = 100
    private const val MAX_ARTISTS = 20
    private val VIDEO_ID = Regex("""^[A-Za-z0-9_-]{6,32}$""")

    fun export(playlistName: String, songs: List<ExportSong>): PlaylistExportResult {
        val included = ArrayList<PortableTrack>(minOf(songs.size, MAX_TRACKS))
        var excluded = 0
        for (song in songs) {
            if (song.isLocal || !VIDEO_ID.matches(song.videoId)) {
                excluded++
                continue
            }
            if (included.size >= MAX_TRACKS) {
                return PlaylistExportResult.Failure("too-many-tracks")
            }
            included += PortableTrack(
                videoId = song.videoId,
                title = sanitize(song.title, MAX_TITLE_LENGTH),
                artists = song.artists
                    .asSequence()
                    .map { sanitize(it, MAX_ARTIST_LENGTH) }
                    .filter { it.isNotBlank() }
                    .take(MAX_ARTISTS)
                    .toList(),
            )
        }
        if (included.isEmpty()) {
            return PlaylistExportResult.Failure("no-online-tracks")
        }

        val playlist = PortablePlaylist(
            name = sanitize(playlistName, 120).ifBlank { "XENOWAVE playlist" },
            tracks = included,
            excludedLocalCount = excluded,
        )
        val json = JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("kind", KIND)
            put("name", playlist.name)
            put("excludedLocalCount", playlist.excludedLocalCount)
            put("tracks", JSONArray().apply {
                playlist.tracks.forEach { track ->
                    put(JSONObject().apply {
                        put("videoId", track.videoId)
                        put("title", track.title)
                        put("artists", JSONArray(track.artists))
                    })
                }
            })
        }.toString(2)
        if (json.toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES) {
            return PlaylistExportResult.Failure("too-large")
        }
        return PlaylistExportResult.Success(json, playlist)
    }

    fun parse(json: String): PlaylistParseResult {
        if (json.toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES) {
            return PlaylistParseResult.Invalid("too-large")
        }
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            return PlaylistParseResult.Invalid("invalid-json")
        }
        if (root.optInt("schemaVersion", -1) != SCHEMA_VERSION) {
            return PlaylistParseResult.Invalid("unsupported-schema")
        }
        if (root.optString("kind") != KIND) {
            return PlaylistParseResult.Invalid("invalid-kind")
        }
        val name = sanitize(root.optString("name"), 120)
        if (name.isBlank()) return PlaylistParseResult.Invalid("invalid-name")

        val tracksJson = root.optJSONArray("tracks") ?: return PlaylistParseResult.Invalid("missing-tracks")
        if (tracksJson.length() !in 1..MAX_TRACKS) {
            return PlaylistParseResult.Invalid("invalid-track-count")
        }
        val tracks = ArrayList<PortableTrack>(tracksJson.length())
        for (index in 0 until tracksJson.length()) {
            val item = tracksJson.optJSONObject(index)
                ?: return PlaylistParseResult.Invalid("invalid-track")
            val videoId = item.optString("videoId")
            if (!VIDEO_ID.matches(videoId)) {
                return PlaylistParseResult.Invalid("invalid-video-id")
            }
            val title = sanitize(item.optString("title"), MAX_TITLE_LENGTH)
            if (title.isBlank()) return PlaylistParseResult.Invalid("invalid-title")

            val artistsJson = item.optJSONArray("artists")
            val artists = ArrayList<String>(artistsJson?.length() ?: 0)
            if (artistsJson != null) {
                if (artistsJson.length() > MAX_ARTISTS) {
                    return PlaylistParseResult.Invalid("too-many-artists")
                }
                for (artistIndex in 0 until artistsJson.length()) {
                    val artist = sanitize(artistsJson.optString(artistIndex), MAX_ARTIST_LENGTH)
                    if (artist.isNotBlank()) artists += artist
                }
            }
            tracks += PortableTrack(videoId, title, artists)
        }

        return PlaylistParseResult.Valid(
            PortablePlaylist(
                name = name,
                tracks = tracks,
                excludedLocalCount = root.optInt("excludedLocalCount", 0).coerceAtLeast(0),
            )
        )
    }

    private fun sanitize(value: String, maxLength: Int): String =
        value.replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxLength)
}
