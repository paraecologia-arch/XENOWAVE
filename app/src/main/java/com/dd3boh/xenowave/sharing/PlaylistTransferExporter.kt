/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.sharing

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object PlaylistTransferExporter {
    private const val EXPORT_DIRECTORY = "playlist-exports"
    private const val MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L

    fun write(context: Context, result: PlaylistExportResult.Success): Uri {
        val directory = File(context.cacheDir, EXPORT_DIRECTORY).apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - MAX_AGE_MILLIS
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) file.delete()
        }

        val safeName = result.playlist.name
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-')
            .take(40)
            .ifBlank { "playlist" }
        val file = File(directory, "XENOWAVE-$safeName.xenowave-playlist.json")
        file.writeText(result.json, Charsets.UTF_8)
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.FileProvider",
            file,
        )
    }
}
