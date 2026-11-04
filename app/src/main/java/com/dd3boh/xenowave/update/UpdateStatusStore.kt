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
import com.dd3boh.xenowave.constants.LastForegroundUpdateCheckKey
import com.dd3boh.xenowave.constants.UpdateApkNameKey
import com.dd3boh.xenowave.constants.UpdateApkSha256Key
import com.dd3boh.xenowave.constants.UpdateApkSizeKey
import com.dd3boh.xenowave.constants.UpdateAvailableKey
import com.dd3boh.xenowave.constants.UpdateConsentApplicationIdKey
import com.dd3boh.xenowave.constants.UpdateConsentGrantedAtKey
import com.dd3boh.xenowave.constants.UpdateConsentSha256Key
import com.dd3boh.xenowave.constants.UpdateConsentVersionCodeKey
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
    val consentVersionCode: Int? = null,
    val consentApkSha256: String? = null,
    val consentApplicationId: String? = null,
    val consentGrantedAtMillis: Long = 0L,
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
        consentVersionCode = preferences[UpdateConsentVersionCodeKey]?.takeIf { it > 0 },
        consentApkSha256 = preferences[UpdateConsentSha256Key],
        consentApplicationId = preferences[UpdateConsentApplicationIdKey],
        consentGrantedAtMillis = preferences[UpdateConsentGrantedAtKey] ?: 0L,
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
            val previousVersion = it[UpdateVersionCodeKey]
            val previousSha = it[UpdateApkSha256Key]
            val sameTarget = previousVersion == metadata.versionCode &&
                previousSha?.equals(metadata.sha256, ignoreCase = true) == true
            val phase = UpdatePhase.entries.firstOrNull { candidate ->
                candidate.name == it[UpdatePhaseKey]
            }
            val targetInProgress = phase == UpdatePhase.DOWNLOADING ||
                phase == UpdatePhase.READY_TO_INSTALL ||
                phase == UpdatePhase.INSTALLING ||
                phase == UpdatePhase.PENDING_USER_ACTION
            if (!sameTarget && !targetInProgress) {
                clearConsent(it)
            }
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
            clearConsent(it)
            it[UpdateProgressKey] = 0
            if (it[UpdatePhaseKey] != UpdatePhase.INSTALLED.name) {
                it[UpdatePhaseKey] = UpdatePhase.IDLE.name
            }
        }
    }

    suspend fun setLastCheck(context: Context, timestampMillis: Long) {
        edit(context) { it[LastUpdateCheckKey] = timestampMillis }
    }

    suspend fun setLastForegroundCheck(context: Context, timestampMillis: Long) {
        edit(context) { it[LastForegroundUpdateCheckKey] = timestampMillis }
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

    suspend fun grantConsent(
        context: Context,
        versionCode: Int,
        sha256: String,
        applicationId: String,
        grantedAtMillis: Long = System.currentTimeMillis(),
    ) {
        edit(context) {
            it[UpdateConsentVersionCodeKey] = versionCode
            it[UpdateConsentSha256Key] = sha256.lowercase()
            it[UpdateConsentApplicationIdKey] = applicationId
            it[UpdateConsentGrantedAtKey] = grantedAtMillis
        }
    }

    /**
     * Revokes only the authorisation for this exact target. A prompt for a
     * newer release must never cancel a different accepted target by accident.
     */
    suspend fun revokeConsent(context: Context, versionCode: Int, sha256: String) {
        edit(context) {
            val sameTarget = it[UpdateConsentVersionCodeKey] == versionCode &&
                it[UpdateConsentSha256Key]?.equals(sha256, ignoreCase = true) == true
            if (sameTarget) clearConsent(it)
        }
    }

    suspend fun clearConsent(context: Context) {
        edit(context) { clearConsent(it) }
    }

    suspend fun currentStatus(context: Context): UpdateStatus =
        read(context.dataStore.data.first())

    private fun clearConsent(preferences: MutablePreferences) {
        preferences.remove(UpdateConsentVersionCodeKey)
        preferences.remove(UpdateConsentSha256Key)
        preferences.remove(UpdateConsentApplicationIdKey)
        preferences.remove(UpdateConsentGrantedAtKey)
    }

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
