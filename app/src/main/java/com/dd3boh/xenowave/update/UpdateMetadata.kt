/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.update

import org.json.JSONException
import org.json.JSONArray
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
    val deltas: List<DeltaMetadata> = emptyList(),
)

data class DeltaMetadata(
    val fromVersionCode: Int,
    val fromApkSha256: String,
    val patchName: String,
    val patchSizeBytes: Long,
    val patchSha256: String,
    val format: String,
    val formatVersion: Int,
    val minClientVersionCode: Int? = null,
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
    if (deltas.isNotEmpty()) {
        put("deltas", JSONArray().apply {
            deltas.forEach { delta ->
                put(JSONObject().apply {
                    put("fromVersionCode", delta.fromVersionCode)
                    put("fromApkSha256", delta.fromApkSha256)
                    put("patchName", delta.patchName)
                    put("patchSizeBytes", delta.patchSizeBytes)
                    put("patchSha256", delta.patchSha256)
                    put("format", delta.format)
                    put("formatVersion", delta.formatVersion)
                    delta.minClientVersionCode?.let { put("minClientVersionCode", it) }
                })
            }
        })
    }
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
    const val MAX_PATCH_BYTES = 100L * 1024L * 1024L
    const val MIN_PATCH_BYTES = 4L * 1024L
    const val MAX_DELTAS = 4

    private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    private val VERSION_NAME_PATTERN =
        Regex("^[0-9]{1,4}(\\.[0-9]{1,4}){1,3}([-+][0-9A-Za-z.\\-]{1,32})?$")
    private val APK_NAME_PATTERN = Regex("^[A-Za-z0-9._-]{1,120}\\.apk$")
    private val PATCH_NAME_PATTERN = Regex("^[A-Za-z0-9._-]{1,120}\\.(xnd|patch)$")

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

        val deltas = parseDeltas(root.optJSONArray("deltas"), versionCode)

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
                deltas = deltas,
            )
        )
    }

    /**
     * Delta entries are optional and fail closed per entry. A malformed delta
     * never invalidates the root APK metadata, so existing clients keep working.
     */
    private fun parseDeltas(array: JSONArray?, targetVersionCode: Int): List<DeltaMetadata> {
        if (array == null || array.length() == 0) return emptyList()
        val result = ArrayList<DeltaMetadata>(minOf(array.length(), MAX_DELTAS))
        for (index in 0 until array.length()) {
            if (result.size >= MAX_DELTAS) break
            val item = array.optJSONObject(index) ?: continue
            val fromVersionCode = item.optInt("fromVersionCode", -1)
            if (fromVersionCode <= 0 || fromVersionCode >= targetVersionCode) continue
            val fromApkSha256 = item.optString("fromApkSha256").trim().lowercase()
            if (!SHA256_PATTERN.matches(fromApkSha256)) continue
            val patchName = item.optString("patchName").trim()
            if (!PATCH_NAME_PATTERN.matches(patchName)) continue
            val patchSizeBytes = item.optLong("patchSizeBytes", -1L)
            if (patchSizeBytes !in MIN_PATCH_BYTES..MAX_PATCH_BYTES) continue
            val patchSha256 = item.optString("patchSha256").trim().lowercase()
            if (!SHA256_PATTERN.matches(patchSha256)) continue
            val format = item.optString("format").trim().lowercase()
            val formatVersion = item.optInt("formatVersion", -1)
            if (format != DeltaCodecContract.FORMAT_NAME ||
                formatVersion != DeltaCodecContract.FORMAT_VERSION
            ) {
                continue
            }
            val minClientVersionCode = item.optInt("minClientVersionCode", 0)
                .takeIf { it > 0 }
            result += DeltaMetadata(
                fromVersionCode = fromVersionCode,
                fromApkSha256 = fromApkSha256,
                patchName = patchName,
                patchSizeBytes = patchSizeBytes,
                patchSha256 = patchSha256,
                format = format,
                formatVersion = formatVersion,
                minClientVersionCode = minClientVersionCode,
            )
        }
        return result
    }
}

/** Keeps the metadata module free of the Java applier implementation. */
object DeltaCodecContract {
    const val FORMAT_NAME = "xnd-cdc"
    const val FORMAT_VERSION = 1
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
    val deltaUrls: Map<String, String> = emptyMap(),
)

data class SelectedUpdate(
    val metadata: UpdateMetadata,
    val downloadUrl: String,
    val deltas: List<SelectedDelta> = emptyList(),
)

data class SelectedDelta(
    val metadata: DeltaMetadata,
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
    ?.let { candidate ->
        SelectedUpdate(
            metadata = candidate.metadata,
            downloadUrl = candidate.downloadUrl,
            deltas = candidate.metadata.deltas.mapNotNull { delta ->
                candidate.deltaUrls[delta.patchName]?.let { url ->
                    SelectedDelta(delta, url)
                }
            },
        )
    }
