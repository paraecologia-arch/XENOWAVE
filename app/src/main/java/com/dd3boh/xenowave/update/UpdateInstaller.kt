/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.dd3boh.xenowave.BuildConfig
import java.io.File

sealed interface InstallStartResult {
    data object Started : InstallStartResult
    data object PermissionMissing : InstallStartResult
    data class Failed(val reason: String) : InstallStartResult
}

/**
 * Installs the verified APK through [PackageInstaller].
 *
 * Data is preserved because this is a normal package update of the same
 * applicationId signed with the same key. The app never uninstalls itself first.
 */
class UpdateInstaller(private val context: Context) {

    fun canRequestPackageInstalls(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    fun settingsIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            )
        } else {
            Intent(Settings.ACTION_SETTINGS)
        }

    fun abandonStaleSessions() {
        val installer = context.packageManager.packageInstaller
        runCatching {
            installer.mySessions.forEach { session ->
                runCatching { installer.abandonSession(session.sessionId) }
            }
        }
    }

    fun install(apkFile: File, expectedVersionCode: Int): InstallStartResult {
        if (!canRequestPackageInstalls()) return InstallStartResult.PermissionMissing
        if (!apkFile.isFile) return InstallStartResult.Failed("missing-apk")

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Updating our own package may proceed without an extra confirmation
                // when the platform grants UPDATE_PACKAGES_WITHOUT_USER_ACTION.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }

        val sessionId = try {
            installer.createSession(params)
        } catch (t: Throwable) {
            return InstallStartResult.Failed("create-session")
        }

        val session = try {
            installer.openSession(sessionId)
        } catch (t: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            return InstallStartResult.Failed("open-session")
        }

        try {
            apkFile.inputStream().use { input ->
                session.openWrite("base.apk", 0, apkFile.length()).use { output ->
                    input.copyTo(output)
                    output.flush()
                    session.fsync(output)
                }
            }

            val statusIntent = Intent(context, UpdateInstallReceiver::class.java).apply {
                action = UpdateInstallReceiver.ACTION_INSTALL_STATUS
                setPackage(context.packageName)
                putExtra(UpdateInstallReceiver.EXTRA_VERSION_CODE, expectedVersionCode)
            }
            val statusPendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                statusIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag(),
            )
            session.commit(statusPendingIntent.intentSender)
            return InstallStartResult.Started
        } catch (t: Throwable) {
            runCatching { session.abandon() }
            return InstallStartResult.Failed(t.javaClass.simpleName)
        } finally {
            runCatching { session.close() }
        }
    }

    private fun mutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }

    companion object {
        val applicationId = BuildConfig.APPLICATION_ID
    }
}
