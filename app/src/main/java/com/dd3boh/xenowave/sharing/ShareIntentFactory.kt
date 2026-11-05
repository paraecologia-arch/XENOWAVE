/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.sharing

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

object ShareIntentFactory {
    fun shareText(
        context: Context,
        chooserTitle: String,
        text: String,
        title: String? = null,
    ) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            title?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        startChooser(context, chooserTitle, intent)
    }

    fun sharePlaylistFile(
        context: Context,
        chooserTitle: String,
        text: String,
        file: Uri,
        title: String? = null,
    ) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = PlaylistTransferFormat.MIME_TYPE
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_STREAM, file)
            title?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            clipData = ClipData.newRawUri(null, file)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startChooser(context, chooserTitle, intent)
    }

    private fun startChooser(context: Context, chooserTitle: String, intent: Intent) {
        val chooser = Intent.createChooser(intent, chooserTitle)
        if (context !is Activity) {
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
