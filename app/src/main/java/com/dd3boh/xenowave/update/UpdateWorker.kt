/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.update

import android.content.Context
import android.media.AudioManager
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dd3boh.xenowave.constants.AutoUpdateEnabledKey
import com.dd3boh.xenowave.constants.UpdateChannelKey
import com.dd3boh.xenowave.update.delta.DeltaCodec
import com.dd3boh.xenowave.update.delta.DeltaPolicy
import com.dd3boh.xenowave.update.delta.InstalledApkProvider
import com.dd3boh.xenowave.utils.dataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.File

/**
 * Entry point for update discovery, approved downloads and installation.
 *
 * WorkManager unique work names prevent parallel discovery, download and
 * install jobs for the same app.
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
        val downloadOnly = inputData.getBoolean(KEY_DOWNLOAD_ONLY, false)
        val installOnly = inputData.getBoolean(KEY_INSTALL_ONLY, false)
        if (!autoEnabled && !forceCheck && !installOnly && !downloadOnly) return Result.success()

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

        if (downloadOnly) {
            if (status.phase == UpdatePhase.INSTALLING ||
                status.phase == UpdatePhase.PREPARING ||
                status.phase == UpdatePhase.PENDING_USER_ACTION
            ) {
                return Result.success()
            }
            return downloadKnownUpdate(context, status, installedVersionCode)
        }

        if (installOnly) {
            return finishKnownUpdate(context, status, installedVersionCode)
        }

        // A download or an installation in progress must not be replaced by a
        // discovery run. Discovery never confirms a version on the user's behalf.
        if (status.phase in ACTIVE_PHASES) {
            return Result.success()
        }

        if (!UpdatePolicy.isCheckDue(status.lastCheckMillis, now, forceCheck)) {
            return finishKnownUpdate(context, status, installedVersionCode)
        }

        UpdateStatusStore.setPhase(context, UpdatePhase.CHECKING)
        val channel = UpdateChannel.fromString(preferences[UpdateChannelKey]) ?: UpdateChannel.BETA
        val repository = UpdateRepository()

        return when (val outcome = UpdateCheckGate.run {
                repository.checkForUpdate(
                    channel = channel,
                    installedVersionCode = installedVersionCode,
                    sdkInt = Build.VERSION.SDK_INT,
                )
            }) {
            UpdateCheckOutcome.UpToDate -> {
                UpdateStatusStore.setLastCheck(context, now)
                val current = UpdateStatusStore.currentStatus(context)
                if (hasAcceptedTarget(current)) {
                    if (current.phase !in ACTIVE_PHASES) {
                        UpdateStatusStore.setPhase(context, UpdatePhase.IDLE)
                    }
                } else {
                    UpdateStatusStore.clearAvailable(context)
                    UpdateStatusStore.setPhase(context, UpdatePhase.IDLE)
                }
                Result.success()
            }

            is UpdateCheckOutcome.Failed -> {
                val current = UpdateStatusStore.currentStatus(context)
                if (current.phase in ACTIVE_PHASES) {
                    // A download or install owns the status.
                } else if (hasAcceptedTarget(current)) {
                    UpdateStatusStore.setPhase(context, UpdatePhase.IDLE)
                } else {
                    UpdateStatusStore.setPhase(
                        context,
                        UpdatePhase.FAILED,
                        error = outcome.reason,
                    )
                }
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
                // Discovery never downloads. The dedicated download work
                // starts only after the user's "Atualizar" consent.
                val current = UpdateStatusStore.currentStatus(context)
                if (current.phase !in ACTIVE_PHASES) {
                    UpdateStatusStore.setPhase(context, UpdatePhase.IDLE)
                }
                Result.success()
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
        installedVersionCode: Int,
    ): Result {
        val metadata = update.metadata
        val current = UpdateStatusStore.currentStatus(context)
        if (!UpdatePolicy.hasConsent(
                status = current,
                metadata = metadata,
                applicationId = UpdateInfo.applicationId,
            )
        ) {
            UpdateStatusStore.setPhase(context, UpdatePhase.IDLE)
            return Result.success()
        }

        val target = updateFile(context, metadata)
        val existingValid = target.isFile &&
            target.length() == metadata.sizeBytes &&
            runCatching { ApkVerifier.sha256(target) }.getOrNull() == metadata.sha256

        if (!existingValid) {
            if (tryDelta(context, update, current, installedVersionCode, target)) {
                // Target was reconstructed and verified by hash/size.
            } else {
                val fullResult = downloadFull(context, update, target)
                if (fullResult != null) return fullResult
            }
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

    /**
     * Attempts one compatible delta. Returns true only when the target APK was
     * reconstructed with the exact size/hash. Any non-cancel failure is recorded
     * so the same broken patch is not retried forever; the caller then uses the
     * normal full APK exactly once.
     */
    private suspend fun tryDelta(
        context: Context,
        update: SelectedUpdate,
        status: UpdateStatus,
        installedVersionCode: Int,
        target: File,
    ): Boolean {
        if (update.deltas.isEmpty()) return false
        val installed = InstalledApkProvider.get(context)
        if (!installed.monolithic) return false
        val installedSha = withContext(Dispatchers.IO) {
            InstalledApkProvider.sha256(installed.path)
        } ?: return false
        val delta = DeltaPolicy.select(
            deltas = update.deltas,
            installedVersionCode = installedVersionCode,
            installedApkSha256 = installedSha,
            target = update.metadata,
            failedDeltaId = status.failedDeltaId,
        ) ?: return false

        val requiredSpace = update.metadata.sizeBytes + delta.metadata.patchSizeBytes + 16L * 1024L * 1024L
        if (context.filesDir.usableSpace < requiredSpace) return false

        val patchFile = File(File(context.filesDir, UPDATES_DIRECTORY), delta.metadata.patchName)
        val economy = DeltaPolicy.economyPercent(delta.metadata, update.metadata.sizeBytes)
        UpdateStatusStore.setArtifactPlan(
            context = context,
            kind = ARTIFACT_DELTA,
            downloadSizeBytes = delta.metadata.patchSizeBytes,
            economyPercent = economy,
        )
        UpdateStatusStore.setPhase(context, UpdatePhase.DOWNLOADING, progress = 0)
        val progress = MutableStateFlow(0)
        val outcome = coroutineScope {
            val progressJob = launch {
                progress.collect { UpdateStatusStore.setProgress(context, it) }
            }
            try {
                UpdateRepository().downloadPatch(delta, patchFile) { progress.value = it }
            } finally {
                progressJob.cancel()
            }
        }
        if (outcome is DownloadOutcome.Failed) {
            patchFile.delete()
            if (outcome.reason != DownloadFailure.NETWORK) {
                UpdateStatusStore.markDeltaFailed(
                    context,
                    DeltaPolicy.deltaId(delta.metadata, update.metadata),
                )
            }
            return false
        }

        UpdateStatusStore.setProgress(context, 100)
        UpdateStatusStore.setPhase(context, UpdatePhase.PREPARING, progress = 0)
        return try {
            withContext(Dispatchers.IO) {
                DeltaCodec.applyPatch(
                    File(installed.path),
                    patchFile,
                    target,
                    update.metadata.sizeBytes,
                )
            }
            currentCoroutineContext().ensureActive()
            val exact = target.isFile &&
                target.length() == update.metadata.sizeBytes &&
                runCatching { ApkVerifier.sha256(target) }.getOrNull() == update.metadata.sha256
            if (!exact) throw IOException("reconstructed APK mismatch")
            patchFile.delete()
            true
        } catch (cancelled: CancellationException) {
            patchFile.delete()
            target.delete()
            throw cancelled
        } catch (failure: Throwable) {
            patchFile.delete()
            target.delete()
            UpdateStatusStore.markDeltaFailed(
                context,
                DeltaPolicy.deltaId(delta.metadata, update.metadata),
            )
            false
        }
    }

    /** Runs the existing full-APK path. Null means "continue to verification". */
    private suspend fun downloadFull(
        context: Context,
        update: SelectedUpdate,
        target: File,
    ): Result? {
        val metadata = update.metadata
        val requiredSpace = metadata.sizeBytes + 8L * 1024L * 1024L
        if (context.filesDir.usableSpace < requiredSpace) {
            UpdateStatusStore.setPhase(context, UpdatePhase.FAILED, error = UpdateFailure.STORAGE)
            return Result.success()
        }
        UpdateStatusStore.setArtifactPlan(
            context = context,
            kind = ARTIFACT_FULL,
            downloadSizeBytes = metadata.sizeBytes,
            economyPercent = null,
        )
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
            val failure = when (outcome.reason) {
                DownloadFailure.NETWORK -> UpdateFailure.NETWORK
                DownloadFailure.IO,
                DownloadFailure.TOO_LARGE -> UpdateFailure.STORAGE

                DownloadFailure.SIZE_MISMATCH,
                DownloadFailure.HASH_MISMATCH -> UpdateFailure.INVALID_METADATA
            }
            UpdateStatusStore.setPhase(context, UpdatePhase.FAILED, error = failure)
            return if (outcome.reason == DownloadFailure.NETWORK &&
                runAttemptCount < MAX_ATTEMPTS
            ) {
                Result.retry()
            } else {
                Result.success()
            }
        }
        UpdateStatusStore.setProgress(context, 100)
        return null
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
            hasConsent = UpdatePolicy.hasConsent(
                status = status,
                metadata = metadata,
                applicationId = UpdateInfo.applicationId,
            ),
        )) {
            InstallDecision.NeedsConsent -> {
                // The user has not accepted this exact target. Keep the update
                // listed, but never create a PackageInstaller session.
                UpdateStatusStore.setPhase(context, UpdatePhase.IDLE)
                Result.success()
            }

            InstallDecision.NeedsPermission -> {
                UpdateStatusStore.setPhase(context, UpdatePhase.READY_TO_INSTALL)
                UpdateNotifications.showNeedsPermission(context)
                Result.success()
            }

            is InstallDecision.Defer -> {
                // Do not interrupt playback; retry after the user pauses it.
                UpdateStatusStore.setPhase(context, UpdatePhase.WAITING_FOR_MUSIC)
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
            }

            is InstallDecision.Skip -> Result.success()

            InstallDecision.Install -> {
                // Never keep a previous session from another attempt around.
                installer.abandonStaleSessions()
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
     * Starts the download after the user accepted this exact target. It reuses
     * the persisted release metadata so a rate-limited GitHub check cannot stop
     * an update that was already authorised.
     */
    private suspend fun downloadKnownUpdate(
        context: Context,
        status: UpdateStatus,
        installedVersionCode: Int,
    ): Result {
        val metadataJson = status.metadataJson ?: return Result.success()
        val metadata = (UpdateMetadataParser.parse(metadataJson, UpdateInfo.applicationId)
            as? UpdateMetadataResult.Valid)?.metadata ?: return Result.success()
        if (metadata.versionCode <= installedVersionCode) return Result.success()
        if (!UpdatePolicy.hasConsent(
                status = status,
                metadata = metadata,
                applicationId = UpdateInfo.applicationId,
            )
        ) {
            UpdateStatusStore.setPhase(context, UpdatePhase.IDLE)
            return Result.success()
        }
        val downloadUrl = status.downloadUrl ?: return Result.success()
        val deltaUrls = UpdateStatusStore.deltaUrls(status)
        val deltas = metadata.deltas.mapNotNull { delta ->
            deltaUrls[delta.patchName]?.let { url -> SelectedDelta(delta, url) }
        }
        return processDownload(
            context = context,
            update = SelectedUpdate(metadata, downloadUrl, deltas),
            installedVersionCode = installedVersionCode,
        )
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
        if (!UpdatePolicy.hasConsent(
                status = status,
                metadata = metadata,
                applicationId = UpdateInfo.applicationId,
            )
        ) {
            return Result.success()
        }

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

    private fun isMusicActive(context: Context): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        return audio.isMusicActive
    }

    private fun hasAcceptedTarget(status: UpdateStatus): Boolean =
        status.availableVersionCode?.let { versionCode ->
            status.apkSha256?.let { sha256 ->
                UpdatePolicy.hasConsent(
                    status = status,
                    versionCode = versionCode,
                    sha256 = sha256,
                    applicationId = UpdateInfo.applicationId,
                )
            }
        } == true

    companion object {
        const val KEY_FORCE_CHECK = "forceCheck"
        const val KEY_DOWNLOAD_ONLY = "downloadOnly"
        const val KEY_INSTALL_ONLY = "installOnly"
        const val UPDATES_DIRECTORY = "updates"
        const val ARTIFACT_DELTA = "delta"
        const val ARTIFACT_FULL = "full"

        private const val MAX_ATTEMPTS = 4
        private const val INSTALLING_STALE_MILLIS = 15L * 60L * 1000L
        private const val PENDING_STALE_MILLIS = 24L * 60L * 60L * 1000L

        private val ACTIVE_PHASES = setOf(
            UpdatePhase.DOWNLOADING,
            UpdatePhase.PREPARING,
            UpdatePhase.READY_TO_INSTALL,
            UpdatePhase.WAITING_FOR_MUSIC,
            UpdatePhase.INSTALLING,
            UpdatePhase.PENDING_USER_ACTION,
        )
    }
}
