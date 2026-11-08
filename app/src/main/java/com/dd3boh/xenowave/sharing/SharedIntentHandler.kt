/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.sharing

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.core.net.toUri
import androidx.navigation.NavController
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.playback.PlayerConnection
import com.dd3boh.xenowave.youtubeNavigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * Handles text messages with two links (playlist + app download), direct
 * playlist links and portable playlist attachments.
 */
object SharedIntentHandler {
    private const val MAX_IMPORT_BYTES = PlaylistTransferFormat.MAX_JSON_BYTES

    suspend fun handle(
        context: Context,
        intent: Intent,
        navController: NavController,
        coroutineScope: CoroutineScope,
        playerConnection: PlayerConnection?,
        snackbarHostState: SnackbarHostState,
        onImport: (PortablePlaylist) -> Unit,
    ): Boolean {
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_VIEW) return false

        val stream = streamUri(intent)
        if (stream != null) {
            val document = readPortablePlaylist(context, stream)
            return when (document) {
                is PortableRead.Valid -> {
                    withContext(Dispatchers.Main.immediate) { onImport(document.playlist) }
                    true
                }

                is PortableRead.Invalid -> showInvalid(context, snackbarHostState)
            }
        }

        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (!text.isNullOrBlank()) {
            PlaylistLinkParser.find(text)?.let { link ->
                return navigate(
                    context,
                    navController,
                    coroutineScope,
                    playerConnection,
                    snackbarHostState,
                    link,
                )
            }
            val trimmed = text.trimStart()
            if (trimmed.startsWith("{")) {
                return when (val parsed = PlaylistTransferFormat.parse(trimmed)) {
                    is PlaylistParseResult.Valid -> {
                        withContext(Dispatchers.Main.immediate) { onImport(parsed.playlist) }
                        true
                    }

                    is PlaylistParseResult.Invalid -> showInvalid(context, snackbarHostState)
                }
            }
        }

        val data = intent.data
        if (data != null) {
            val link = PlaylistLinkParser.parse(data.toString())
            if (link != null) {
                return navigate(
                    context,
                    navController,
                    coroutineScope,
                    playerConnection,
                    snackbarHostState,
                    link,
                )
            }
            if (data.scheme.equals("content", ignoreCase = true)) {
                val document = readPortablePlaylist(context, data)
                return when (document) {
                    is PortableRead.Valid -> {
                        withContext(Dispatchers.Main.immediate) { onImport(document.playlist) }
                        true
                    }

                    is PortableRead.Invalid -> showInvalid(context, snackbarHostState)
                }
            }
        }

        return false
    }

    private suspend fun navigate(
        context: Context,
        navController: NavController,
        coroutineScope: CoroutineScope,
        playerConnection: PlayerConnection?,
        snackbarHostState: SnackbarHostState,
        link: PlaylistLink,
    ): Boolean = withContext(Dispatchers.Main.immediate) {
        youtubeNavigator(
            context,
            navController,
            coroutineScope,
            playerConnection,
            snackbarHostState,
            link.normalizedUrl.toUri(),
        )
    }

    private suspend fun readPortablePlaylist(context: Context, uri: Uri): PortableRead =
        withContext(Dispatchers.IO) {
            if (!uri.scheme.equals("content", ignoreCase = true)) {
                return@withContext PortableRead.Invalid
            }
            if (uri.authority == "${context.packageName}.FileProvider") {
                // Do not let another app bounce a URI back into our provider.
                return@withContext PortableRead.Invalid
            }
            val json = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    readLimited(input, MAX_IMPORT_BYTES + 1)
                }
            }.getOrNull() ?: return@withContext PortableRead.Invalid
            if (json.toByteArray(Charsets.UTF_8).size > MAX_IMPORT_BYTES) {
                return@withContext PortableRead.Invalid
            }
            when (val parsed = PlaylistTransferFormat.parse(json)) {
                is PlaylistParseResult.Valid -> PortableRead.Valid(parsed.playlist)
                is PlaylistParseResult.Invalid -> PortableRead.Invalid
            }
        }

    private fun readLimited(input: InputStream, maxBytes: Int): String {
        val buffer = ByteArray(8 * 1024)
        val output = java.io.ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw IllegalArgumentException("too-large")
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private suspend fun showInvalid(
        context: Context,
        snackbarHostState: SnackbarHostState,
    ): Boolean {
        withContext(Dispatchers.Main.immediate) {
            snackbarHostState.showSnackbar(
                message = context.getString(R.string.share_invalid_playlist_link),
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
        }
        return false
    }

    private fun streamUri(intent: Intent): Uri? {
        val fromExtra = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        return fromExtra ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
    }

    private sealed interface PortableRead {
        data class Valid(val playlist: PortablePlaylist) : PortableRead
        data object Invalid : PortableRead
    }
}
