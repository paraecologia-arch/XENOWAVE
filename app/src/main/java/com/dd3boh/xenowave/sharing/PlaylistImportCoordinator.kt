/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.sharing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds a validated import until the user confirms it in the UI. Nothing is
 * written to the database before that confirmation.
 */
object PlaylistImportCoordinator {
    private val mutablePending = MutableStateFlow<PortablePlaylist?>(null)
    val pending: StateFlow<PortablePlaylist?> = mutablePending.asStateFlow()

    fun offer(playlist: PortablePlaylist) {
        mutablePending.value = playlist
    }

    fun consume() {
        mutablePending.value = null
    }
}
