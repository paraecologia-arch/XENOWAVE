/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.update

import org.json.JSONException
import org.json.JSONObject

enum class UpdateChannel {
    BETA,
    STABLE;

    companion object {
        fun fromString(value: String?): UpdateChannel? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

data class UpdateMetadata(
    val schemaVersion: Int,
    val applicationId: String,
    val versionCode: Int,
    val versionName: String,
    val channel: UpdateChannel,
    val minSdk: Int,
    val apkName: String,
    val sizeBytes: Long,
    val sha256: String,
)

fun UpdateMetadata.toJsonString(): String = JSONObject().apply {
    put("schemaVersion", schemaVersion)
    put("applicationId", applicationId)
    put("versionCode", versionCode)
    put("versionName", versionName)
    put("channel", channel.name.lowercase())
    put("minSdk", minSdk)
    put("apkName", apkName)
    put("sizeBytes", sizeBytes)
    put("sha256", sha256)
}.toString()

sealed interface UpdateMetadataResult {
    data class Valid(val metadata: UpdateMetadata) : UpdateMetadataResult
    data class Invalid(val reason: String) : UpdateMetadataResult
}

/**
 * Contract published as the `update.json` asset of an official release.
 *
 * The SHA-256 describes the *signed* APK, never the unsigned build artifact.
 * Values are validated before any download is attempted.
 */
object UpdateMetadataParser {
    const val SCHEMA_VERSION = 1
    const val MAX_APK_BYTES = 250L * 1024L * 1024L
    const val MIN_APK_BYTES = 1L * 1024L * 1024L

    private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    private val VERSION_NAME_PATTERN =
        Regex("^[0-9]{1,4}(\\.[0-9]{1,4}){1,3}([-+][0-9A-Za-z.\\-]{1,32})?$")
    private val APK_NAME_PATTERN = Regex("^[A-Za-z0-9._-]{1,120}\\.apk$")

    fun parse(json: String, expectedApplicationId: String): UpdateMetadataResult {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            return UpdateMetadataResult.Invalid("invalid-json")
        }

        val schemaVersion = root.optInt("schemaVersion", -1)
        if (schemaVersion != SCHEMA_VERSION) {
            return UpdateMetadataResult.Invalid("unsupported-schema")
        }

        val applicationId = root.optString("applicationId").trim()
        if (applicationId != expectedApplicationId) {
            return UpdateMetadataResult.Invalid("wrong-application-id")
        }

        val versionCode = root.optInt("versionCode", -1)
        if (versionCode <= 0) {
            return UpdateMetadataResult.Invalid("invalid-version-code")
        }

        val versionName = root.optString("versionName").trim()
        if (!VERSION_NAME_PATTERN.matches(versionName)) {
            return UpdateMetadataResult.Invalid("invalid-version-name")
        }

        val channel = UpdateChannel.fromString(root.optString("channel"))
            ?: return UpdateMetadataResult.Invalid("invalid-channel")

        val minSdk = root.optInt("minSdk", -1)
        if (minSdk !in 1..100) {
            return UpdateMetadataResult.Invalid("invalid-min-sdk")
        }

        val apkName = root.optString("apkName").trim()
        if (!APK_NAME_PATTERN.matches(apkName)) {
            return UpdateMetadataResult.Invalid("invalid-apk-name")
        }

        val sizeBytes = root.optLong("sizeBytes", -1L)
        if (sizeBytes !in MIN_APK_BYTES..MAX_APK_BYTES) {
            return UpdateMetadataResult.Invalid("invalid-size")
        }

        val sha256 = root.optString("sha256").trim().lowercase()
        if (!SHA256_PATTERN.matches(sha256)) {
            return UpdateMetadataResult.Invalid("invalid-sha256")
        }

        return UpdateMetadataResult.Valid(
            UpdateMetadata(
                schemaVersion = schemaVersion,
                applicationId = applicationId,
                versionCode = versionCode,
                versionName = versionName,
                channel = channel,
                minSdk = minSdk,
                apkName = apkName,
                sizeBytes = sizeBytes,
                sha256 = sha256,
            )
        )
    }
}

/**
 * A release is only offered when it is compatible with the selected channel.
 * The stable channel never receives prereleases.
 */
fun updateChannelAllows(prerelease: Boolean, channel: UpdateChannel): Boolean =
    channel != UpdateChannel.STABLE || !prerelease

data class ReleaseCandidate(
    val tagName: String,
    val prerelease: Boolean,
    val draft: Boolean,
    val metadata: UpdateMetadata,
    val downloadUrl: String,
)

data class SelectedUpdate(
    val metadata: UpdateMetadata,
    val downloadUrl: String,
)

/**
 * Picks the highest versionCode among the official, compatible releases.
 * The tag ordering is deliberately ignored: tags are text and not a version.
 */
fun selectUpdate(
    candidates: List<ReleaseCandidate>,
    channel: UpdateChannel,
    installedVersionCode: Int,
    sdkInt: Int,
    applicationId: String,
): SelectedUpdate? = candidates.asSequence()
    .filter { !it.draft }
    .filter { updateChannelAllows(it.prerelease, channel) }
    .filter { it.metadata.applicationId == applicationId }
    .filter { it.metadata.versionCode > installedVersionCode }
    .filter { it.metadata.minSdk <= sdkInt }
    .filter { !(channel == UpdateChannel.STABLE && it.metadata.channel == UpdateChannel.BETA) }
    .maxByOrNull { it.metadata.versionCode }
    ?.let { SelectedUpdate(it.metadata, it.downloadUrl) }
