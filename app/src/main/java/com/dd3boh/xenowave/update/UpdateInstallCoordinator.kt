/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.update

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the system confirmation intent received from PackageInstaller so the
 * foreground update screen can open it immediately. The notification uses the
 * same intent when the app is in the background.
 */
object UpdateInstallCoordinator {
    data class PendingConfirmation(
        val intent: Intent,
        val versionCode: Int,
    )

    private val mutablePending = MutableStateFlow<PendingConfirmation?>(null)
    val pending: StateFlow<PendingConfirmation?> = mutablePending.asStateFlow()

    fun setPending(intent: Intent?, versionCode: Int) {
        mutablePending.value = intent?.let { PendingConfirmation(it, versionCode) }
    }

    fun consume() {
        mutablePending.value = null
    }
}
