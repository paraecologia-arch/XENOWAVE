/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.update

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serialises GitHub release checks inside the process.
 *
 * A foreground check and a WorkManager check can be requested at the same
 * moment when the app opens. They must not issue duplicate requests or race to
 * overwrite the same status entry.
 */
internal object UpdateCheckGate {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
}
