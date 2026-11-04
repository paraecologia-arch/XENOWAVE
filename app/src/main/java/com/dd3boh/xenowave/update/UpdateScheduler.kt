/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.update

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.dd3boh.xenowave.constants.AutoUpdateEnabledKey
import com.dd3boh.xenowave.utils.dataStore
import com.dd3boh.xenowave.utils.get
import java.util.concurrent.TimeUnit

object UpdateScheduler {
    const val PERIODIC_WORK_NAME = "xenowave-update-periodic"
    const val ONE_TIME_WORK_NAME = "xenowave-update-once"
    const val DOWNLOAD_WORK_NAME = "xenowave-update-download"
    const val INSTALL_WORK_NAME = "xenowave-update-install"

    private const val PERIOD_HOURS = 12L
    private const val BACKOFF_MINUTES = 30L

    fun onAppStart(context: Context) {
        if (!UpdateInfo.isSupported) return
        val autoEnabled = context.dataStore[AutoUpdateEnabledKey] ?: true
        if (!autoEnabled) {
            cancelPeriodic(context)
            return
        }
        enqueuePeriodic(context)
    }

    fun setAutoUpdateEnabled(context: Context, enabled: Boolean) {
        if (!UpdateInfo.isSupported) return
        if (enabled) {
            enqueuePeriodic(context)
        } else {
            cancelPeriodic(context)
            WorkManager.getInstance(context).cancelUniqueWork(ONE_TIME_WORK_NAME)
        }
    }

    /**
     * Manual verification. It bypasses the foreground/periodic cooldown but
     * never starts a download by itself.
     */
    fun checkNow(context: Context) {
        if (!UpdateInfo.isSupported) return
        enqueueOneTime(
            context,
            forceCheck = true,
            replaceExisting = true,
        )
    }

    /**
     * Heavy work requested by an explicit "Update" tap. The worker validates
     * the persisted consent for versionCode + SHA-256 before opening any file
     * or PackageInstaller session.
     */
    fun downloadApproved(context: Context) {
        if (!UpdateInfo.isSupported) return
        val request = OneTimeWorkRequestBuilder<UpdateWorker>()
            .setInputData(workDataOf(UpdateWorker.KEY_DOWNLOAD_ONLY to true))
            .setConstraints(networkOnlyConstraints())
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                BACKOFF_MINUTES,
                TimeUnit.MINUTES,
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            DOWNLOAD_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun installNow(context: Context) {
        if (!UpdateInfo.isSupported) return
        val request = OneTimeWorkRequestBuilder<UpdateWorker>()
            .setInputData(workDataOf(UpdateWorker.KEY_INSTALL_ONLY to true))
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                BACKOFF_MINUTES,
                TimeUnit.MINUTES,
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            INSTALL_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancelApprovedUpdate(context: Context) {
        if (!UpdateInfo.isSupported) return
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(DOWNLOAD_WORK_NAME)
            cancelUniqueWork(INSTALL_WORK_NAME)
        }
    }

    private fun enqueuePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(defaultConstraints())
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                BACKOFF_MINUTES,
                TimeUnit.MINUTES,
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    private fun enqueueOneTime(
        context: Context,
        forceCheck: Boolean,
        replaceExisting: Boolean,
    ) {
        val request = OneTimeWorkRequestBuilder<UpdateWorker>()
            .setInputData(
                workDataOf(
                    UpdateWorker.KEY_FORCE_CHECK to forceCheck,
                )
            )
            .setConstraints(networkOnlyConstraints())
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                BACKOFF_MINUTES,
                TimeUnit.MINUTES,
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_TIME_WORK_NAME,
            if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private fun cancelPeriodic(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
    }

    private fun defaultConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

    private fun networkOnlyConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
}
