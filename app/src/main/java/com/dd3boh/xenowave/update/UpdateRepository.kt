/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.update

import com.dd3boh.xenowave.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

sealed interface UpdateCheckOutcome {
    data class Available(val update: SelectedUpdate) : UpdateCheckOutcome
    data object UpToDate : UpdateCheckOutcome
    data class Failed(val reason: UpdateFailure) : UpdateCheckOutcome
}

enum class UpdateFailure {
    NETWORK,
    RATE_LIMITED,
    SERVER,
    INVALID_METADATA,
    INSTALL,
}

sealed interface DownloadOutcome {
    data class Success(val file: File) : DownloadOutcome
    data class Failed(val reason: DownloadFailure) : DownloadOutcome
}

enum class DownloadFailure {
    NETWORK,
    SIZE_MISMATCH,
    HASH_MISMATCH,
    TOO_LARGE,
    IO,
}

/**
 * Talks only to official GitHub releases of this repository.
 *
 * The release list is read in bounded pages; each candidate needs an
 * `update.json` asset and an APK asset whose name matches the metadata.
 * The winner is chosen by versionCode, never by tag text or date.
 */
class UpdateRepository(
    private val client: UpdateHttpClient = HttpsUpdateHttpClient(),
    private val applicationId: String = BuildConfig.APPLICATION_ID,
) {

    suspend fun checkForUpdate(
        channel: UpdateChannel,
        installedVersionCode: Int,
        sdkInt: Int,
    ): UpdateCheckOutcome = withContext(Dispatchers.IO) {
        try {
            val candidates = mutableListOf<ReleaseCandidate>()
            for (page in 1..MAX_PAGES) {
                val releases = fetchReleasePage(page)
                for (release in releases) {
                    if (release.draft) continue
                    if (!updateChannelAllows(release.prerelease, channel)) continue
                    if (candidates.size >= MAX_CANDIDATES) break

                    val metadataAsset = release.assets
                        .firstOrNull { it.name == METADATA_ASSET_NAME } ?: continue
                    val metadataText = try {
                        client.getText(metadataAsset.url, MAX_METADATA_BYTES)
                    } catch (e: UpdateNetworkException) {
                        continue
                    }
                    val metadata = (UpdateMetadataParser.parse(metadataText, applicationId)
                        as? UpdateMetadataResult.Valid)?.metadata ?: continue

                    val apkAsset = release.assets
                        .firstOrNull { it.name == metadata.apkName } ?: continue

                    candidates += ReleaseCandidate(
                        tagName = release.tagName,
                        prerelease = release.prerelease,
                        draft = release.draft,
                        metadata = metadata,
                        downloadUrl = apkAsset.url,
                    )
                }
                if (releases.size < RELEASES_PER_PAGE) break
            }

            val selected = selectUpdate(
                candidates = candidates,
                channel = channel,
                installedVersionCode = installedVersionCode,
                sdkInt = sdkInt,
                applicationId = applicationId,
            )
            if (selected == null) UpdateCheckOutcome.UpToDate else UpdateCheckOutcome.Available(selected)
        } catch (e: UpdateRateLimitException) {
            UpdateCheckOutcome.Failed(UpdateFailure.RATE_LIMITED)
        } catch (e: UpdateHttpException) {
            UpdateCheckOutcome.Failed(UpdateFailure.SERVER)
        } catch (e: UpdateNetworkException) {
            UpdateCheckOutcome.Failed(UpdateFailure.NETWORK)
        } catch (e: Exception) {
            UpdateCheckOutcome.Failed(UpdateFailure.NETWORK)
        }
    }

    suspend fun download(
        update: SelectedUpdate,
        destination: File,
        onProgress: (Int) -> Unit,
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        val metadata = update.metadata
        val parent = destination.parentFile ?: return@withContext DownloadOutcome.Failed(DownloadFailure.IO)
        if (!parent.exists() && !parent.mkdirs()) {
            return@withContext DownloadOutcome.Failed(DownloadFailure.IO)
        }

        if (destination.exists()) {
            val existingHash = runCatching { ApkVerifier.sha256(destination) }.getOrNull()
            if (destination.length() == metadata.sizeBytes && existingHash == metadata.sha256) {
                return@withContext DownloadOutcome.Success(destination)
            }
            destination.delete()
        }

        val temporary = File(parent, "${destination.name}.part")
        temporary.delete()
        try {
            val written = client.download(update.downloadUrl, temporary, MAX_DOWNLOAD_BYTES) { bytes ->
                val percent = ((bytes * 100L) / metadata.sizeBytes.coerceAtLeast(1L))
                    .coerceIn(0L, 100L)
                    .toInt()
                onProgress(percent)
            }
            if (written != metadata.sizeBytes || temporary.length() != metadata.sizeBytes) {
                temporary.delete()
                return@withContext DownloadOutcome.Failed(DownloadFailure.SIZE_MISMATCH)
            }
            if (ApkVerifier.sha256(temporary) != metadata.sha256) {
                temporary.delete()
                return@withContext DownloadOutcome.Failed(DownloadFailure.HASH_MISMATCH)
            }
            if (!temporary.renameTo(destination)) {
                temporary.delete()
                return@withContext DownloadOutcome.Failed(DownloadFailure.IO)
            }
            DownloadOutcome.Success(destination)
        } catch (e: UpdateRateLimitException) {
            temporary.delete()
            DownloadOutcome.Failed(DownloadFailure.NETWORK)
        } catch (e: UpdateTooLargeException) {
            temporary.delete()
            DownloadOutcome.Failed(DownloadFailure.TOO_LARGE)
        } catch (e: UpdateNetworkException) {
            temporary.delete()
            DownloadOutcome.Failed(DownloadFailure.NETWORK)
        } catch (e: Exception) {
            temporary.delete()
            DownloadOutcome.Failed(DownloadFailure.IO)
        }
    }

    private fun fetchReleasePage(page: Int): List<ReleaseInfo> {
        val url = "https://api.github.com/repos/$OWNER/$REPO/releases" +
            "?per_page=$RELEASES_PER_PAGE&page=$page"
        val body = client.getText(url, MAX_RELEASES_JSON_BYTES)
        val array = try {
            JSONArray(body)
        } catch (e: JSONException) {
            throw UpdateNetworkException("invalid-releases-json", e)
        }

        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    ReleaseInfo(
                        tagName = item.optString("tag_name"),
                        prerelease = item.optBoolean("prerelease", true),
                        draft = item.optBoolean("draft", true),
                        assets = parseAssets(item.optJSONArray("assets")),
                    )
                )
            }
        }
    }

    private fun parseAssets(array: JSONArray?): List<ReleaseAsset> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val asset = array.optJSONObject(index) ?: continue
                val url = asset.optString("browser_download_url")
                if (url.isBlank()) continue
                add(ReleaseAsset(name = asset.optString("name"), url = url))
            }
        }
    }

    private data class ReleaseInfo(
        val tagName: String,
        val prerelease: Boolean,
        val draft: Boolean,
        val assets: List<ReleaseAsset>,
    )

    private data class ReleaseAsset(val name: String, val url: String)

    companion object {
        const val OWNER = "paraecologia-arch"
        const val REPO = "XENOWAVE"
        const val METADATA_ASSET_NAME = "update.json"

        private const val RELEASES_PER_PAGE = 20
        private const val MAX_PAGES = 3
        private const val MAX_CANDIDATES = 15
        private const val MAX_METADATA_BYTES = 64L * 1024L
        private const val MAX_RELEASES_JSON_BYTES = 4L * 1024L * 1024L
        private const val MAX_DOWNLOAD_BYTES = UpdatePolicy.MAX_DOWNLOAD_BYTES
    }
}
