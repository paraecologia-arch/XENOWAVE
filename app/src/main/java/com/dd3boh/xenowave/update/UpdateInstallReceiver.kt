/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.dd3boh.xenowave.backup.RestoreConsumerGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives the result of our own PackageInstaller session.
 *
 * It is declared non-exported and only accepts the explicit action used by
 * [UpdateInstaller]; it never accepts an APK or a command from other apps.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return
        if (!RestoreConsumerGuard.canOpenData()) {
            // Do not open DataStore while the restore state is unresolved.
            // The installer result is discarded; the restore safety wins.
            return
        }

        val versionCode = intent.getIntExtra(EXTRA_VERSION_CODE, 0)
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        val confirmationIntent = confirmationIntent(intent)
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        UpdateStatusStore.setPendingVersion(context, versionCode)
                        UpdateStatusStore.setPhase(context, UpdatePhase.PENDING_USER_ACTION)
                        UpdateInstallCoordinator.setPending(confirmationIntent, versionCode)
                        UpdateNotifications.ensureChannel(context)
                        UpdateNotifications.showPendingConfirmation(
                            context,
                            versionCode,
                            confirmationIntent,
                        )
                    }

                    PackageInstaller.STATUS_SUCCESS -> {
                        UpdateInstallCoordinator.consume()
                        UpdateStatusStore.clearPendingVersion(context)
                        UpdateStatusStore.clearAvailable(context)
                        UpdateStatusStore.setPhase(context, UpdatePhase.INSTALLED)
                        UpdateNotifications.cancel(context)
                    }

                    else -> {
                        UpdateInstallCoordinator.consume()
                        UpdateStatusStore.clearPendingVersion(context)
                        UpdateStatusStore.setPhase(
                            context,
                            UpdatePhase.FAILED,
                            error = UpdateFailure.INSTALL,
                        )
                        UpdateNotifications.showFailure(context)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun confirmationIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    companion object {
        const val ACTION_INSTALL_STATUS =
            "com.dd3boh.xenowave.action.UPDATE_INSTALL_STATUS"
        const val EXTRA_VERSION_CODE = "versionCode"
    }
}
