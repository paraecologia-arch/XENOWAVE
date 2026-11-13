/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.update.delta

import android.content.Context
import java.io.File

data class InstalledApk(
    val path: String,
    val monolithic: Boolean,
)

object InstalledApkProvider {
    /**
     * `sourceDir` is the read-only base APK. The public core flavor is
     * monolithic; split installs skip delta and use the existing full path.
     */
    fun get(context: Context): InstalledApk {
        val info = context.applicationInfo
        return InstalledApk(
            path = info.sourceDir,
            monolithic = info.splitSourceDirs.isNullOrEmpty(),
        )
    }

    fun sha256(path: String): String? =
        runCatching { DeltaCodec.sha256Hex(File(path)) }.getOrNull()
}
