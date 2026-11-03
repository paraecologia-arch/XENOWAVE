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
        enqueueOneTime(
            context,
            forceDownload = false,
            forceCheck = false,
            replaceExisting = false,
        )
    }

    fun setAutoUpdateEnabled(context: Context, enabled: Boolean) {
        if (!UpdateInfo.isSupported) return
        if (enabled) {
            enqueuePeriodic(context)
            enqueueOneTime(
                context,
                forceDownload = false,
                forceCheck = false,
                replaceExisting = false,
            )
        } else {
            cancelPeriodic(context)
            WorkManager.getInstance(context).cancelUniqueWork(ONE_TIME_WORK_NAME)
        }
    }

    fun checkNow(context: Context, forceDownload: Boolean = false) {
        if (!UpdateInfo.isSupported) return
        enqueueOneTime(
            context,
            forceDownload = forceDownload,
            forceCheck = true,
            replaceExisting = true,
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
        forceDownload: Boolean,
        forceCheck: Boolean,
        replaceExisting: Boolean,
    ) {
        val request = OneTimeWorkRequestBuilder<UpdateWorker>()
            .setInputData(
                workDataOf(
                    UpdateWorker.KEY_FORCE_CHECK to forceCheck,
                    UpdateWorker.KEY_FORCE_DOWNLOAD to forceDownload,
                )
            )
            .setConstraints(defaultConstraints())
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
}
