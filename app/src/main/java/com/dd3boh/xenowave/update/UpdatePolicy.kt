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
    WAITING_FOR_MUSIC,
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
    const val FOREGROUND_CHECK_INTERVAL_MILLIS = 15L * 60L * 1000L
    const val MAX_DOWNLOAD_BYTES = UpdateMetadataParser.MAX_APK_BYTES

    fun isCheckDue(lastCheckMillis: Long, nowMillis: Long, force: Boolean): Boolean =
        force || lastCheckMillis <= 0L ||
            nowMillis - lastCheckMillis >= MIN_CHECK_INTERVAL_MILLIS

    /**
     * Foreground checks are cheap and happen when the app becomes visible.
     * The short cooldown prevents duplicate GitHub requests while the user
     * moves between screens or quickly backgrounds and reopens the app.
     * "Check now" bypasses it with [force].
     */
    fun isForegroundCheckDue(lastCheckMillis: Long, nowMillis: Long, force: Boolean): Boolean =
        force || lastCheckMillis <= 0L ||
            nowMillis - lastCheckMillis >= FOREGROUND_CHECK_INTERVAL_MILLIS

    fun isVersionInstalled(pendingVersionCode: Int?, installedVersionCode: Int): Boolean =
        pendingVersionCode != null && installedVersionCode >= pendingVersionCode

    fun hasConsent(
        status: UpdateStatus,
        versionCode: Int,
        sha256: String,
        applicationId: String,
    ): Boolean =
        status.consentVersionCode == versionCode &&
            status.consentApkSha256?.equals(sha256, ignoreCase = true) == true &&
            status.consentApplicationId == applicationId &&
            applicationId.isNotBlank()

    fun hasConsent(
        status: UpdateStatus,
        metadata: UpdateMetadata,
        applicationId: String,
    ): Boolean = metadata.applicationId == applicationId &&
        hasConsent(status, metadata.versionCode, metadata.sha256, applicationId)

    fun canStartInstall(
        phase: UpdatePhase,
        pendingVersionCode: Int?,
        targetVersionCode: Int,
        musicActive: Boolean,
        canRequestPackageInstalls: Boolean,
        hasConsent: Boolean,
    ): InstallDecision = when {
        !hasConsent -> InstallDecision.NeedsConsent

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
    data object NeedsConsent : InstallDecision
    data class Defer(val reason: String) : InstallDecision
    data class Skip(val reason: String) : InstallDecision
}
