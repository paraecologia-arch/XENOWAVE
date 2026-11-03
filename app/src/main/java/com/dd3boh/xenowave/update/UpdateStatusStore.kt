/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.dd3boh.xenowave.BuildConfig
import com.dd3boh.xenowave.constants.LastUpdateCheckKey
import com.dd3boh.xenowave.constants.LastVersionKey
import com.dd3boh.xenowave.constants.UpdateApkNameKey
import com.dd3boh.xenowave.constants.UpdateApkSha256Key
import com.dd3boh.xenowave.constants.UpdateApkSizeKey
import com.dd3boh.xenowave.constants.UpdateAvailableKey
import com.dd3boh.xenowave.constants.UpdateDownloadUrlKey
import com.dd3boh.xenowave.constants.UpdateErrorKey
import com.dd3boh.xenowave.constants.UpdateInstallAttemptKey
import com.dd3boh.xenowave.constants.UpdateMetadataJsonKey
import com.dd3boh.xenowave.constants.UpdatePendingVersionCodeKey
import com.dd3boh.xenowave.constants.UpdatePhaseKey
import com.dd3boh.xenowave.constants.UpdateProgressKey
import com.dd3boh.xenowave.constants.UpdateVersionCodeKey
import com.dd3boh.xenowave.constants.UpdateVersionNameKey
import com.dd3boh.xenowave.utils.dataStore
import kotlinx.coroutines.flow.first

data class UpdateStatus(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val progress: Int = 0,
    val updateAvailable: Boolean = false,
    val availableVersionCode: Int? = null,
    val availableVersionName: String? = null,
    val apkName: String? = null,
    val apkSizeBytes: Long? = null,
    val apkSha256: String? = null,
    val downloadUrl: String? = null,
    val metadataJson: String? = null,
    val pendingVersionCode: Int? = null,
    val installAttemptMillis: Long = 0L,
    val error: UpdateFailure? = null,
    val lastCheckMillis: Long = 0L,
)

object UpdateStatusStore {
    fun read(preferences: Preferences): UpdateStatus = UpdateStatus(
        phase = UpdatePhase.entries.firstOrNull {
            it.name == preferences[UpdatePhaseKey]
        } ?: UpdatePhase.IDLE,
        progress = preferences[UpdateProgressKey] ?: 0,
        updateAvailable = preferences[UpdateAvailableKey] ?: false,
        availableVersionCode = preferences[UpdateVersionCodeKey],
        availableVersionName = preferences[UpdateVersionNameKey],
        apkName = preferences[UpdateApkNameKey],
        apkSizeBytes = preferences[UpdateApkSizeKey],
        apkSha256 = preferences[UpdateApkSha256Key],
        downloadUrl = preferences[UpdateDownloadUrlKey],
        metadataJson = preferences[UpdateMetadataJsonKey],
        pendingVersionCode = preferences[UpdatePendingVersionCodeKey]?.takeIf { it > 0 },
        installAttemptMillis = preferences[UpdateInstallAttemptKey] ?: 0L,
        error = UpdateFailure.entries.firstOrNull {
            it.name == preferences[UpdateErrorKey]
        },
        lastCheckMillis = preferences[LastUpdateCheckKey] ?: 0L,
    )

    suspend fun setPhase(
        context: Context,
        phase: UpdatePhase,
        progress: Int = 0,
        error: UpdateFailure? = null,
    ) {
        edit(context) {
            it[UpdatePhaseKey] = phase.name
            it[UpdateProgressKey] = progress.coerceIn(0, 100)
            if (error == null) {
                it.remove(UpdateErrorKey)
            } else {
                it[UpdateErrorKey] = error.name
            }
        }
    }

    suspend fun setProgress(context: Context, progress: Int) {
        edit(context) {
            it[UpdateProgressKey] = progress.coerceIn(0, 100)
        }
    }

    suspend fun setAvailable(context: Context, update: SelectedUpdate) {
        val metadata = update.metadata
        edit(context) {
            it[UpdateAvailableKey] = true
            it[UpdateVersionCodeKey] = metadata.versionCode
            it[UpdateVersionNameKey] = metadata.versionName
            it[LastVersionKey] = metadata.versionName
            it[UpdateApkNameKey] = metadata.apkName
            it[UpdateApkSizeKey] = metadata.sizeBytes
            it[UpdateApkSha256Key] = metadata.sha256
            it[UpdateDownloadUrlKey] = update.downloadUrl
            it[UpdateMetadataJsonKey] = metadata.toJsonString()
        }
    }

    suspend fun clearAvailable(context: Context) {
        edit(context) {
            it[UpdateAvailableKey] = false
            it.remove(UpdateVersionCodeKey)
            it.remove(UpdateVersionNameKey)
            it.remove(UpdateApkNameKey)
            it.remove(UpdateApkSizeKey)
            it.remove(UpdateApkSha256Key)
            it.remove(UpdateDownloadUrlKey)
            it.remove(UpdateMetadataJsonKey)
            it.remove(UpdatePendingVersionCodeKey)
            it.remove(UpdateInstallAttemptKey)
            it[UpdateProgressKey] = 0
            if (it[UpdatePhaseKey] != UpdatePhase.INSTALLED.name) {
                it[UpdatePhaseKey] = UpdatePhase.IDLE.name
            }
        }
    }

    suspend fun setLastCheck(context: Context, timestampMillis: Long) {
        edit(context) { it[LastUpdateCheckKey] = timestampMillis }
    }

    suspend fun setPendingVersion(context: Context, versionCode: Int) {
        edit(context) { it[UpdatePendingVersionCodeKey] = versionCode }
    }

    suspend fun clearPendingVersion(context: Context) {
        edit(context) {
            it.remove(UpdatePendingVersionCodeKey)
            it.remove(UpdateInstallAttemptKey)
        }
    }

    suspend fun markInstallAttempt(context: Context, timestampMillis: Long) {
        edit(context) { it[UpdateInstallAttemptKey] = timestampMillis }
    }

    suspend fun currentStatus(context: Context): UpdateStatus =
        read(context.dataStore.data.first())

    private suspend fun edit(context: Context, block: suspend (MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}

object UpdateInfo {
    val applicationId: String get() = BuildConfig.APPLICATION_ID

    /**
     * Only the public core release channel is self-updatable.
     * Debug builds use a different applicationId, and the full flavor has a
     * different capability set that must not be replaced by the core APK.
     */
    val isSupported: Boolean
        get() = !BuildConfig.APPLICATION_ID.endsWith(DEBUG_SUFFIX) &&
            BuildConfig.FLAVOR == SUPPORTED_FLAVOR

    fun installedVersionCode(context: Context): Int {
        val info = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return BuildConfig.VERSION_CODE
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }

    fun installedVersionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
            ?: BuildConfig.VERSION_NAME
    } catch (e: PackageManager.NameNotFoundException) {
        BuildConfig.VERSION_NAME
    }

    const val DEBUG_SUFFIX = ".debug"
    const val SUPPORTED_FLAVOR = "core"
}
