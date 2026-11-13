/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.sharing

import java.util.Locale

/**
 * Pure message construction. Localized templates live in Android resources;
 * this object only normalizes user-controlled titles and fills placeholders.
 */
object ShareMessageBuilder {
    private val WHITESPACE = Regex("\\s+")

    fun sanitizeTitle(title: String, maxLength: Int = 120): String =
        title.replace(WHITESPACE, " ").trim().take(maxLength)

    fun appInvite(template: String, appName: String, downloadUrl: String): String =
        String.format(Locale.getDefault(), template, appName, downloadUrl)

    fun onlinePlaylist(
        template: String,
        visibilityHintTemplate: String,
        title: String,
        playlistUrl: String,
        appName: String,
        downloadUrl: String,
    ): String = String.format(
        Locale.getDefault(),
        template,
        sanitizeTitle(title),
        playlistUrl,
        appName,
        downloadUrl,
        visibilityHintTemplate,
    )

    fun localPlaylistSummary(
        template: String,
        title: String,
        included: Int,
        excluded: Int,
    ): String = String.format(
        Locale.getDefault(),
        template,
        sanitizeTitle(title),
        included,
        excluded,
    )

    fun localPlaylistAttachment(
        template: String,
        title: String,
        included: Int,
        excluded: Int,
    ): String = String.format(
        Locale.getDefault(),
        template,
        sanitizeTitle(title),
        included,
        excluded,
    )
}
