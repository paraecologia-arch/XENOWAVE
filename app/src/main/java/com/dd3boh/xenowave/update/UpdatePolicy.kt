/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.update

enum class UpdatePhase {
    IDLE,
    CHECKING,
    DOWNLOADING,
    READY_TO_INSTALL,
    INSTALLING,
    PENDING_USER_ACTION,
    FAILED,
    INSTALLED,
}

/**
 * Pure decisions used by the worker and by the settings screen.
 */
object UpdatePolicy {
    const val MIN_CHECK_INTERVAL_MILLIS = 6L * 60L * 60L * 1000L
    const val MAX_DOWNLOAD_BYTES = UpdateMetadataParser.MAX_APK_BYTES

    fun isCheckDue(lastCheckMillis: Long, nowMillis: Long, force: Boolean): Boolean =
        force || lastCheckMillis <= 0L ||
            nowMillis - lastCheckMillis >= MIN_CHECK_INTERVAL_MILLIS

    fun shouldAutoDownload(
        unmeteredNetwork: Boolean,
        unmeteredOnly: Boolean,
        forceDownload: Boolean,
    ): Boolean = forceDownload || !unmeteredOnly || unmeteredNetwork

    fun isVersionInstalled(pendingVersionCode: Int?, installedVersionCode: Int): Boolean =
        pendingVersionCode != null && installedVersionCode >= pendingVersionCode

    fun canStartInstall(
        phase: UpdatePhase,
        pendingVersionCode: Int?,
        targetVersionCode: Int,
        musicActive: Boolean,
        canRequestPackageInstalls: Boolean,
    ): InstallDecision = when {
        phase == UpdatePhase.INSTALLING || phase == UpdatePhase.PENDING_USER_ACTION ->
            InstallDecision.Skip("install-already-running")

        pendingVersionCode == targetVersionCode ->
            InstallDecision.Skip("install-already-pending")

        musicActive -> InstallDecision.Defer("music-playing")
        !canRequestPackageInstalls -> InstallDecision.NeedsPermission
        else -> InstallDecision.Install
    }
}

sealed interface InstallDecision {
    data object Install : InstallDecision
    data object NeedsPermission : InstallDecision
    data class Defer(val reason: String) : InstallDecision
    data class Skip(val reason: String) : InstallDecision
}
