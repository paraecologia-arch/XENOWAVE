/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.update

import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dd3boh.xenowave.constants.AutoUpdateEnabledKey
import com.dd3boh.xenowave.constants.UpdateAutoDownloadUnmeteredKey
import com.dd3boh.xenowave.constants.UpdateChannelKey
import com.dd3boh.xenowave.utils.dataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * Single periodic entry point for update checks, downloads and installation.
 *
 * WorkManager guarantees only one unique periodic work and one unique on-demand
 * work, so repeated app launches cannot create parallel pollers.
 */
class UpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!UpdateInfo.isSupported) return Result.success()

        val preferences = context.dataStore.data.first()
        val autoEnabled = preferences[AutoUpdateEnabledKey] ?: true
        val forceCheck = inputData.getBoolean(KEY_FORCE_CHECK, false)
        val forceDownload = inputData.getBoolean(KEY_FORCE_DOWNLOAD, false)
        val installOnly = inputData.getBoolean(KEY_INSTALL_ONLY, false)
        if (!autoEnabled && !forceCheck && !installOnly) return Result.success()

        val now = System.currentTimeMillis()
        val installedVersionCode = UpdateInfo.installedVersionCode(context)
        var status = UpdateStatusStore.read(preferences)

        // The package may have been replaced while the process was dead.
        if (status.pendingVersionCode != null && installedVersionCode >= status.pendingVersionCode) {
            UpdateStatusStore.clearPendingVersion(context)
            status = status.copy(pendingVersionCode = null, installAttemptMillis = 0L)
        }
        if (status.updateAvailable &&
            status.availableVersionCode != null &&
            installedVersionCode >= status.availableVersionCode
        ) {
            UpdateStatusStore.clearAvailable(context)
            status = status.copy(updateAvailable = false, availableVersionCode = null)
        }
        status = recoverStaleInstallSession(context, status, now)

        if (installOnly) {
            return finishKnownUpdate(context, status, installedVersionCode)
        }

        if (!UpdatePolicy.isCheckDue(status.lastCheckMillis, now, forceCheck || forceDownload)) {
            return finishKnownUpdate(context, status, installedVersionCode)
        }

        UpdateStatusStore.setPhase(context, UpdatePhase.CHECKING)
        val channel = UpdateChannel.fromString(preferences[UpdateChannelKey]) ?: UpdateChannel.BETA
        val repository = UpdateRepository()

        return when (val outcome = repository.checkForUpdate(
            channel = channel,
            installedVersionCode = installedVersionCode,
            sdkInt = Build.VERSION.SDK_INT,
        )) {
            UpdateCheckOutcome.UpToDate -> {
                UpdateStatusStore.clearAvailable(context)
                UpdateStatusStore.setLastCheck(context, now)
                UpdateStatusStore.setPhase(context, UpdatePhase.IDLE)
                Result.success()
            }

            is UpdateCheckOutcome.Failed -> {
                UpdateStatusStore.setPhase(context, UpdatePhase.FAILED, error = outcome.reason)
                if ((outcome.reason == UpdateFailure.NETWORK ||
                        outcome.reason == UpdateFailure.RATE_LIMITED) &&
                    runAttemptCount < MAX_ATTEMPTS
                ) {
                    Result.retry()
                } else {
                    Result.success()
                }
            }

            is UpdateCheckOutcome.Available -> {
                UpdateStatusStore.setAvailable(context, outcome.update)
                UpdateStatusStore.setLastCheck(context, now)
                processDownload(
                    context = context,
                    update = outcome.update,
                    preferences = preferences,
                    forceDownload = forceDownload,
                    installedVersionCode = installedVersionCode,
                )
            }
        }
    }

    private suspend fun recoverStaleInstallSession(
        context: Context,
        status: UpdateStatus,
        now: Long,
    ): UpdateStatus {
        val timeout = when (status.phase) {
            UpdatePhase.INSTALLING -> INSTALLING_STALE_MILLIS
            UpdatePhase.PENDING_USER_ACTION -> PENDING_STALE_MILLIS
            else -> return status
        }
        if (status.installAttemptMillis <= 0L) return status
        if (now - status.installAttemptMillis <= timeout) return status

        UpdateInstaller(context).abandonStaleSessions()
        UpdateInstallCoordinator.consume()
        UpdateStatusStore.clearPendingVersion(context)
        UpdateStatusStore.setPhase(context, UpdatePhase.IDLE)
        return status.copy(
            phase = UpdatePhase.IDLE,
            pendingVersionCode = null,
            installAttemptMillis = 0L,
        )
    }

    private suspend fun processDownload(
        context: Context,
        update: SelectedUpdate,
        preferences: androidx.datastore.preferences.core.Preferences,
        forceDownload: Boolean,
        installedVersionCode: Int,
    ): Result {
        val metadata = update.metadata
        val target = updateFile(context, metadata)
        val existingValid = target.isFile &&
            target.length() == metadata.sizeBytes &&
            runCatching { ApkVerifier.sha256(target) }.getOrNull() == metadata.sha256

        if (!existingValid) {
            val unmeteredOnly = preferences[UpdateAutoDownloadUnmeteredKey] ?: true
            if (!UpdatePolicy.shouldAutoDownload(
                    unmeteredNetwork = isUnmeteredNetwork(context),
                    unmeteredOnly = unmeteredOnly,
                    forceDownload = forceDownload,
                )
            ) {
                // Keep the update listed; the user can start the download explicitly.
                UpdateStatusStore.setPhase(context, UpdatePhase.IDLE)
                return Result.success()
            }

            UpdateStatusStore.setPhase(context, UpdatePhase.DOWNLOADING, progress = 0)
            val progress = MutableStateFlow(0)
            val outcome = coroutineScope {
                val progressJob = launch {
                    progress.collect { UpdateStatusStore.setProgress(context, it) }
                }
                try {
                    UpdateRepository().download(update, target) { progress.value = it }
                } finally {
                    progressJob.cancel()
                }
            }

            if (outcome is DownloadOutcome.Failed) {
                UpdateStatusStore.setPhase(
                    context = context,
                    phase = UpdatePhase.FAILED,
                    error = if (outcome.reason == DownloadFailure.NETWORK ||
                        outcome.reason == DownloadFailure.TOO_LARGE
                    ) {
                        UpdateFailure.NETWORK
                    } else {
                        UpdateFailure.INVALID_METADATA
                    },
                )
                return if (outcome.reason == DownloadFailure.NETWORK &&
                    runAttemptCount < MAX_ATTEMPTS
                ) {
                    Result.retry()
                } else {
                    Result.success()
                }
            }
            UpdateStatusStore.setProgress(context, 100)
        }

        return when (val verification = ApkVerifier.verify(
            context = context,
            apkFile = target,
            metadata = metadata,
            installedVersionCode = installedVersionCode,
        )) {
            is VerificationResult.Rejected -> {
                target.delete()
                UpdateStatusStore.setPhase(
                    context,
                    UpdatePhase.FAILED,
                    error = UpdateFailure.INVALID_METADATA,
                )
                Result.success()
            }

            VerificationResult.Ok -> installVerified(
                context = context,
                metadata = metadata,
                apkFile = target,
            )
        }
    }

    private suspend fun installVerified(
        context: Context,
        metadata: UpdateMetadata,
        apkFile: File,
    ): Result {
        val status = UpdateStatusStore.currentStatus(context)
        val installer = UpdateInstaller(context)
        val phase = status.phase
        val pending = if (phase == UpdatePhase.INSTALLING ||
            phase == UpdatePhase.PENDING_USER_ACTION
        ) {
            status.pendingVersionCode
        } else {
            null
        }

        return when (UpdatePolicy.canStartInstall(
            phase = phase,
            pendingVersionCode = pending,
            targetVersionCode = metadata.versionCode,
            musicActive = isMusicActive(context),
            canRequestPackageInstalls = installer.canRequestPackageInstalls(),
        )) {
            InstallDecision.NeedsPermission -> {
                UpdateStatusStore.setPhase(context, UpdatePhase.READY_TO_INSTALL)
                UpdateNotifications.showNeedsPermission(context)
                Result.success()
            }

            is InstallDecision.Defer -> {
                // Do not interrupt playback; the periodic worker will try again.
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
            }

            is InstallDecision.Skip -> Result.success()

            InstallDecision.Install -> {
                UpdateStatusStore.setPendingVersion(context, metadata.versionCode)
                UpdateStatusStore.markInstallAttempt(context, System.currentTimeMillis())
                UpdateStatusStore.setPhase(context, UpdatePhase.INSTALLING)
                when (val started = installer.install(apkFile, metadata.versionCode)) {
                    InstallStartResult.Started -> Result.success()

                    InstallStartResult.PermissionMissing -> {
                        UpdateStatusStore.clearPendingVersion(context)
                        UpdateStatusStore.setPhase(context, UpdatePhase.READY_TO_INSTALL)
                        UpdateNotifications.showNeedsPermission(context)
                        Result.success()
                    }

                    is InstallStartResult.Failed -> {
                        UpdateStatusStore.clearPendingVersion(context)
                        UpdateStatusStore.setPhase(context, UpdatePhase.FAILED, error = UpdateFailure.INSTALL)
                        Result.success()
                    }
                }
            }
        }
    }

    /**
     * When the check itself is not due, still try to finish an update that was
     * already downloaded and verified (for example after the user granted the
     * install permission).
     */
    private suspend fun finishKnownUpdate(
        context: Context,
        status: UpdateStatus,
        installedVersionCode: Int,
    ): Result {
        val metadataJson = status.metadataJson ?: return Result.success()
        val metadata = (UpdateMetadataParser.parse(metadataJson, UpdateInfo.applicationId)
            as? UpdateMetadataResult.Valid)?.metadata ?: return Result.success()
        if (metadata.versionCode <= installedVersionCode) return Result.success()
        if (status.apkName != metadata.apkName) return Result.success()

        val target = updateFile(context, metadata)
        val valid = target.isFile &&
            target.length() == metadata.sizeBytes &&
            runCatching { ApkVerifier.sha256(target) }.getOrNull() == metadata.sha256
        if (!valid) return Result.success()

        return when (val verification = ApkVerifier.verify(
            context = context,
            apkFile = target,
            metadata = metadata,
            installedVersionCode = installedVersionCode,
        )) {
            VerificationResult.Ok -> installVerified(context, metadata, target)
            is VerificationResult.Rejected -> {
                target.delete()
                Result.success()
            }
        }
    }

    private fun updateFile(context: Context, metadata: UpdateMetadata): File =
        File(File(context.filesDir, UPDATES_DIRECTORY), metadata.apkName)

    private fun isUnmeteredNetwork(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun isMusicActive(context: Context): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        return audio.isMusicActive
    }

    companion object {
        const val KEY_FORCE_CHECK = "forceCheck"
        const val KEY_FORCE_DOWNLOAD = "forceDownload"
        const val KEY_INSTALL_ONLY = "installOnly"
        const val UPDATES_DIRECTORY = "updates"

        private const val MAX_ATTEMPTS = 4
        private const val INSTALLING_STALE_MILLIS = 15L * 60L * 1000L
        private const val PENDING_STALE_MILLIS = 24L * 60L * 60L * 1000L
    }
}
