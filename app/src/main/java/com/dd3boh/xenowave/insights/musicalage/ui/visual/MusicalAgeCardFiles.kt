/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.insights.musicalage.ui.visual

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.insights.musicalage.ReadyResult
import com.dd3boh.xenowave.sharing.OfficialAppLinkProvider
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Narrow FileProvider surface and temporary-file lifecycle for exported
 * musical-age cards. Only `cache/musical-age-cards` is exposed; the rest of
 * the cache, databases, files and backups remain outside the provider.
 */
object MusicalAgeCardFiles {
    const val DIRECTORY_NAME = "musical-age-cards"
    const val MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L
    private const val MIME_TYPE = "image/png"

    fun displayName(format: MusicalAgeCardFormat): String =
        "XENOWAVE-idade-musical-${format.suffix}.png"

    /**
     * Public display name used by the gallery. The provider/share copy adds an
     * opaque operation id so a later export can never replace an earlier one.
     */
    internal fun exportFileName(
        format: MusicalAgeCardFormat,
        operationId: String,
    ): String = "XENOWAVE-idade-musical-${format.suffix}-${safeOperationId(operationId)}.png"

    fun write(
        context: Context,
        bitmap: Bitmap,
        format: MusicalAgeCardFormat,
        operationId: String,
        nowMillis: Long = System.currentTimeMillis(),
        encoder: (Bitmap, OutputStream) -> Boolean = { source, output ->
            source.compress(Bitmap.CompressFormat.PNG, 100, output)
        },
    ): File {
        val directory = File(context.cacheDir, DIRECTORY_NAME).apply { mkdirs() }
        cleanup(directory, nowMillis, MAX_AGE_MILLIS)

        val target = File(directory, exportFileName(format, operationId))
        check(!target.exists()) { "Export target already exists" }
        val temporary = File(directory, "${target.name}.part")
        try {
            FileOutputStream(temporary).use { output ->
                check(encoder(bitmap, output)) {
                    "PNG encoding failed"
                }
                output.fd.sync()
            }
            check(temporary.renameTo(target)) { "Could not publish the card file" }
            return target
        } catch (error: Exception) {
            temporary.delete()
            // The target is unique to this operation, so this can only remove
            // our own partial publication, never an earlier shared card.
            target.delete()
            throw error
        }
    }

    private fun safeOperationId(value: String): String {
        val sanitized = value.filter { it.isLetterOrDigit() }.take(64)
        return sanitized.ifEmpty {
            UUID.randomUUID().toString().replace("-", "")
        }
    }

    fun cleanup(
        directory: File,
        nowMillis: Long,
        maxAgeMillis: Long,
    ) {
        if (!directory.isDirectory) return
        val cutoff = nowMillis - maxAgeMillis
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }

    fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.FileProvider",
        file,
    )

    fun shareChooser(
        context: Context,
        uri: Uri,
        result: ReadyResult,
    ): Intent {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, shareText(context, result))
            putExtra(
                Intent.EXTRA_SUBJECT,
                context.getString(R.string.musical_age_card_share_subject),
            )
            clipData = ClipData.newRawUri("XENOWAVE musical age card", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(
            share,
            context.getString(R.string.musical_age_card_chooser),
        ).apply {
            if (context !is android.app.Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    fun shareText(context: Context, result: ReadyResult): String = context.getString(
        R.string.musical_age_card_share_text,
        result.musicalAge,
        result.generation.label,
        result.dominantWindow.fromYear,
        result.dominantWindow.toYear,
        OfficialAppLinkProvider.DOWNLOAD_URL,
    )

    /**
     * Gallery insertion is offered only where MediaStore scoped storage does
     * not require a broad storage permission (Android 10+). Older versions
     * keep the share path instead of asking for WRITE_EXTERNAL_STORAGE.
     */
    fun canSaveToGallery(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
}
