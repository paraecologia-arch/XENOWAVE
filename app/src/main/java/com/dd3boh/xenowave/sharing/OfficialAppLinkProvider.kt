/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.sharing

/**
 * Stable public destinations for invitations.
 *
 * [DOWNLOAD_URL] is the verified GitHub Pages download page. It asks for
 * consent and links straight to the signed core APK, so recipients do not
 * need to understand the GitHub Releases interface. [RELEASES_URL] stays as
 * the technical reference and as a fallback for documentation.
 */
object OfficialAppLinkProvider {
    const val DOWNLOAD_URL = "https://paraecologia-arch.github.io/XENOWAVE/"
    const val RELEASES_URL = "https://github.com/paraecologia-arch/XENOWAVE/releases"
}
