/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.update.delta

import com.dd3boh.xenowave.update.DeltaMetadata
import com.dd3boh.xenowave.update.SelectedDelta
import com.dd3boh.xenowave.update.UpdateMetadata

object DeltaPolicy {
    const val MIN_SAVINGS_PERCENT = 20

    fun isSupported(delta: DeltaMetadata): Boolean =
        delta.format.equals(DeltaCodec.FORMAT_NAME, ignoreCase = true) &&
            delta.formatVersion == DeltaCodec.FORMAT_VERSION

    fun matchesInstalledBase(
        delta: DeltaMetadata,
        installedVersionCode: Int,
        installedApkSha256: String,
    ): Boolean =
        delta.fromVersionCode == installedVersionCode &&
            delta.fromApkSha256.equals(installedApkSha256, ignoreCase = true) &&
            (delta.minClientVersionCode == null ||
                installedVersionCode >= delta.minClientVersionCode)

    fun hasEnoughSaving(delta: DeltaMetadata, fullApkSizeBytes: Long): Boolean =
        delta.patchSizeBytes > 0L &&
            fullApkSizeBytes > 0L &&
            delta.patchSizeBytes * 100L <=
            fullApkSizeBytes * (100L - MIN_SAVINGS_PERCENT)

    fun select(
        deltas: List<SelectedDelta>,
        installedVersionCode: Int,
        installedApkSha256: String,
        target: UpdateMetadata,
        failedDeltaId: String?,
    ): SelectedDelta? = deltas.asSequence()
        .filter { isSupported(it.metadata) }
        .filter { matchesInstalledBase(it.metadata, installedVersionCode, installedApkSha256) }
        .filter { hasEnoughSaving(it.metadata, target.sizeBytes) }
        .filter { deltaId(it.metadata, target) != failedDeltaId }
        .minByOrNull { it.metadata.patchSizeBytes }

    fun economyPercent(delta: DeltaMetadata, fullApkSizeBytes: Long): Int {
        if (fullApkSizeBytes <= 0L) return 0
        return (((fullApkSizeBytes - delta.patchSizeBytes) * 100L) / fullApkSizeBytes)
            .toInt()
            .coerceIn(0, 99)
    }

    fun deltaId(delta: DeltaMetadata, target: UpdateMetadata): String =
        "${delta.fromApkSha256}:${delta.patchSha256}:${target.sha256}"
}
