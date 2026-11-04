/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.sharing

/**
 * Stable public distribution page. Prefer this over a frozen APK link:
 * GitHub's `/releases/latest` endpoint does not reliably point at prereleases.
 */
object OfficialAppLinkProvider {
    const val RELEASES_URL = "https://github.com/paraecologia-arch/XENOWAVE/releases"
}
