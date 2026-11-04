/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.update

import android.content.Context
import android.os.Build
import com.dd3boh.xenowave.constants.AutoUpdateEnabledKey
import com.dd3boh.xenowave.constants.LastForegroundUpdateCheckKey
import com.dd3boh.xenowave.constants.UpdateChannelKey
import com.dd3boh.xenowave.utils.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class UpdatePrompt(
    val versionCode: Int,
    val versionName: String?,
    val sha256: String,
    val sizeBytes: Long?,
    val installedVersionCode: Int,
    val installedVersionName: String,
)

/**
 * Coordinates discovery, the in-app "Update / Not now" decision and the
 * session-scoped suppression of an already dismissed prompt.
 *
 * Detection is automatic. Download and installation only happen after
 * [approve], which persists consent for one exact versionCode + SHA-256.
 */
object UpdatePromptController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checkMutex = Mutex()
    private val consentMutex = Mutex()

    private var checkInFlight = false
    private var dismissedVersionCode: Int? = null

    private val mutablePrompt = MutableStateFlow<UpdatePrompt?>(null)
    val prompt: StateFlow<UpdatePrompt?> = mutablePrompt.asStateFlow()

    /**
     * Light foreground verification. It runs when the activity becomes
     * visible, is cooldown-bound and never blocks navigation or playback.
     */
    fun onAppForeground(context: Context) {
        val app = context.applicationContext
        if (!UpdateInfo.isSupported) return

        scope.launch {
            checkMutex.withLock {
                val preferences = app.dataStore.data.first()
                var status = UpdateStatusStore.read(preferences)
                val installedNow = UpdateInfo.installedVersionCode(app)
                if (status.updateAvailable &&
                    status.availableVersionCode != null &&
                    installedNow >= status.availableVersionCode
                ) {
                    UpdateStatusStore.clearAvailable(app)
                    status = UpdateStatusStore.currentStatus(app)
                }
                consider(app, status)

                if (hasAcceptedTarget(status)) return@withLock

                val autoEnabled = preferences[AutoUpdateEnabledKey] ?: true
                if (!autoEnabled || checkInFlight) return@withLock
                if (status.phase in ACTIVE_PHASES) return@withLock

                val now = System.currentTimeMillis()
                val lastAttempt = maxOf(
                    preferences[LastForegroundUpdateCheckKey] ?: 0L,
                    status.lastCheckMillis,
                )
                if (!UpdatePolicy.isForegroundCheckDue(lastAttempt, now, force = false)) {
                    return@withLock
                }

                checkInFlight = true
                // Record the attempt before I/O so a crash cannot create a loop.
                UpdateStatusStore.setLastForegroundCheck(app, now)
                try {
                    UpdateStatusStore.setPhase(app, UpdatePhase.CHECKING)
                    val channel = UpdateChannel.fromString(preferences[UpdateChannelKey])
                        ?: UpdateChannel.BETA
                    val outcome = UpdateCheckGate.run {
                        UpdateRepository().checkForUpdate(
                            channel = channel,
                            installedVersionCode = UpdateInfo.installedVersionCode(app),
                            sdkInt = Build.VERSION.SDK_INT,
                        )
                    }
                    when (outcome) {
                        UpdateCheckOutcome.UpToDate -> {
                            UpdateStatusStore.setLastCheck(app, now)
                            val current = UpdateStatusStore.currentStatus(app)
                            if (hasAcceptedTarget(current)) {
                                if (current.phase !in ACTIVE_PHASES) {
                                    UpdateStatusStore.setPhase(app, UpdatePhase.IDLE)
                                }
                            } else {
                                dismissedVersionCode = null
                                UpdateStatusStore.clearAvailable(app)
                                UpdateStatusStore.setPhase(app, UpdatePhase.IDLE)
                            }
                        }

                        is UpdateCheckOutcome.Failed -> {
                            // A known update stays visible and usable. A failed
                            // network check must not hide it or block the app.
                            val current = UpdateStatusStore.currentStatus(app)
                            if (current.phase in ACTIVE_PHASES) {
                                // A download or install owns the status.
                            } else if (hasAcceptedTarget(current)) {
                                UpdateStatusStore.setPhase(app, UpdatePhase.IDLE)
                            } else {
                                UpdateStatusStore.setPhase(
                                    app,
                                    UpdatePhase.FAILED,
                                    error = outcome.reason,
                                )
                            }
                        }

                        is UpdateCheckOutcome.Available -> {
                            UpdateStatusStore.setAvailable(app, outcome.update)
                            UpdateStatusStore.setLastCheck(app, now)
                            val current = UpdateStatusStore.currentStatus(app)
                            if (current.phase !in ACTIVE_PHASES) {
                                UpdateStatusStore.setPhase(app, UpdatePhase.IDLE)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    UpdateStatusStore.setPhase(
                        app,
                        UpdatePhase.FAILED,
                        error = UpdateFailure.NETWORK,
                    )
                } finally {
                    checkInFlight = false
                }

                status = UpdateStatusStore.currentStatus(app)
                consider(app, status)
            }
        }
    }

    /**
     * Publishes or clears the prompt from the persisted state. The host calls
     * this after any status change, including a manual check or a worker.
     */
    fun consider(context: Context, status: UpdateStatus) {
        val versionCode = status.availableVersionCode
        val sha256 = status.apkSha256
        val installedVersionCode = UpdateInfo.installedVersionCode(context)

        if (!status.updateAvailable ||
            versionCode == null ||
            sha256 == null ||
            installedVersionCode >= versionCode ||
            dismissedVersionCode == versionCode
        ) {
            mutablePrompt.value = null
            return
        }

        mutablePrompt.value = UpdatePrompt(
            versionCode = versionCode,
            versionName = status.availableVersionName,
            sha256 = sha256,
            sizeBytes = status.apkSizeBytes,
            installedVersionCode = installedVersionCode,
            installedVersionName = UpdateInfo.installedVersionName(context),
        )
    }

    /**
     * Explicit consent: persist the target and start the heavy work.
     */
    fun approve(context: Context, prompt: UpdatePrompt) {
        val app = context.applicationContext
        scope.launch {
            consentMutex.withLock {
                val current = UpdateStatusStore.currentStatus(app)
                if (current.availableVersionCode != prompt.versionCode ||
                    !current.apkSha256.equals(prompt.sha256, ignoreCase = true)
                ) {
                    return@withLock
                }
                UpdateStatusStore.grantConsent(
                    context = app,
                    versionCode = prompt.versionCode,
                    sha256 = prompt.sha256,
                    applicationId = UpdateInfo.applicationId,
                )
                UpdateStatusStore.setPhase(app, UpdatePhase.IDLE)
                UpdateScheduler.downloadApproved(app)
            }
        }
    }

    fun approve(context: Context, status: UpdateStatus) {
        val versionCode = status.availableVersionCode ?: return
        val sha256 = status.apkSha256 ?: return
        approve(
            context,
            UpdatePrompt(
                versionCode = versionCode,
                versionName = status.availableVersionName,
                sha256 = sha256,
                sizeBytes = status.apkSizeBytes,
                installedVersionCode = UpdateInfo.installedVersionCode(context),
                installedVersionName = UpdateInfo.installedVersionName(context),
            ),
        )
    }

    /**
     * "Not now" for the offered version. No download or install session is
     * created, and the prompt is not shown again during this process.
     */
    fun decline(context: Context, prompt: UpdatePrompt) {
        val app = context.applicationContext
        dismissedVersionCode = prompt.versionCode
        mutablePrompt.value = null
        scope.launch {
            consentMutex.withLock {
                UpdateStatusStore.revokeConsent(app, prompt.versionCode, prompt.sha256)
                UpdateStatusStore.setPhase(app, UpdatePhase.IDLE)
                UpdateNotifications.cancel(app)
            }
        }
    }

    fun hide(prompt: UpdatePrompt) {
        dismissedVersionCode = prompt.versionCode
        mutablePrompt.value = null
    }

    fun retry(context: Context) {
        val app = context.applicationContext
        scope.launch {
            consentMutex.withLock {
                val current = UpdateStatusStore.currentStatus(app)
                val target = current.availableVersionCode ?: return@withLock
                val sha256 = current.apkSha256 ?: return@withLock
                if (!UpdatePolicy.hasConsent(
                        status = current,
                        versionCode = target,
                        sha256 = sha256,
                        applicationId = UpdateInfo.applicationId,
                    )
                ) {
                    return@withLock
                }
                UpdateStatusStore.setPhase(app, UpdatePhase.IDLE)
                UpdateScheduler.downloadApproved(app)
            }
        }
    }

    /**
     * Explicit cancellation revokes the accepted target and stops pending work.
     */
    fun cancel(context: Context, versionCode: Int?) {
        val app = context.applicationContext
        dismissedVersionCode = versionCode ?: dismissedVersionCode
        mutablePrompt.value = null
        UpdateInstallCoordinator.consume()
        scope.launch {
            consentMutex.withLock {
                UpdateStatusStore.clearConsent(app)
                UpdateScheduler.cancelApprovedUpdate(app)
                UpdateNotifications.cancel(app)
                UpdateStatusStore.setPhase(app, UpdatePhase.IDLE)
            }
        }
    }

    private val ACTIVE_PHASES = setOf(
        UpdatePhase.DOWNLOADING,
        UpdatePhase.READY_TO_INSTALL,
        UpdatePhase.WAITING_FOR_MUSIC,
        UpdatePhase.INSTALLING,
        UpdatePhase.PENDING_USER_ACTION,
    )

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
}
