/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.update

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection

open class UpdateNetworkException(message: String, cause: Throwable? = null) : Exception(message, cause)

class UpdateRateLimitException : UpdateNetworkException("github-rate-limit")

class UpdateHttpException(val code: Int) : UpdateNetworkException("http-$code")

class UpdateTooLargeException : UpdateNetworkException("content-too-large")

interface UpdateHttpClient {
    fun getText(url: String, maxBytes: Long): String

    fun download(
        url: String,
        destination: File,
        maxBytes: Long,
        onProgress: (bytesWritten: Long) -> Unit,
    ): Long
}

/**
 * Minimal HTTPS client for the official GitHub release channel.
 *
 * Redirects are followed manually so every hop can be checked against an
 * allow-list of distribution hosts. No credentials are ever sent.
 */
class HttpsUpdateHttpClient : UpdateHttpClient {

    override fun getText(url: String, maxBytes: Long): String {
        val connection = openConnection(url)
        try {
            val declared = connection.contentLengthLong
            if (declared > maxBytes) throw UpdateTooLargeException()
            val bytes = connection.inputStream.use { readLimited(it, maxBytes) }
            return String(bytes, StandardCharsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    override fun download(
        url: String,
        destination: File,
        maxBytes: Long,
        onProgress: (bytesWritten: Long) -> Unit,
    ): Long {
        val connection = openConnection(url)
        try {
            val declared = connection.contentLengthLong
            if (declared > maxBytes) throw UpdateTooLargeException()

            var written = 0L
            var lastReported = 0L
            destination.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        if (written > maxBytes) throw UpdateTooLargeException()
                        output.write(buffer, 0, read)
                        if (written - lastReported >= PROGRESS_STEP_BYTES) {
                            lastReported = written
                            onProgress(written)
                        }
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            if (declared > 0 && written < declared) {
                // The stream ended before the declared size; treat as interrupted.
                throw UpdateNetworkException("truncated-download")
            }
            if (written == 0L) throw UpdateNetworkException("empty-download")
            onProgress(written)
            return written
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpsURLConnection {
        var current = validateUrl(url)
        repeat(MAX_REDIRECTS + 1) {
            val connection = current.toURL().openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.setRequestProperty("Accept", "application/vnd.github+json, application/json, */*")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", USER_AGENT)

            val code = connection.responseCode
            when {
                code in 300..399 -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location.isNullOrBlank()) {
                        throw UpdateNetworkException("redirect-without-location")
                    }
                    current = validateUrl(URI(current.toString()).resolve(location).toString())
                }

                code == 403 || code == 429 -> {
                    val remaining = connection.getHeaderField("X-RateLimit-Remaining")
                    connection.disconnect()
                    if (code == 429 || remaining == "0") throw UpdateRateLimitException()
                    throw UpdateHttpException(code)
                }

                code !in 200..299 -> {
                    connection.disconnect()
                    throw UpdateHttpException(code)
                }

                else -> return connection
            }
        }
        throw UpdateNetworkException("too-many-redirects")
    }

    private fun validateUrl(url: String): URI {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            throw UpdateNetworkException("invalid-url", e)
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            throw UpdateNetworkException("insecure-url")
        }
        if (uri.userInfo != null) throw UpdateNetworkException("credentials-in-url")
        val host = uri.host?.lowercase()
        if (host == null || host !in ALLOWED_HOSTS) {
            throw UpdateNetworkException("unexpected-host")
        }
        if (uri.port != -1 && uri.port != 443) {
            throw UpdateNetworkException("unexpected-port")
        }
        return uri
    }

    private fun readLimited(input: InputStream, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw UpdateTooLargeException()
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    companion object {
        val ALLOWED_HOSTS = setOf(
            "api.github.com",
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "github-releases.githubusercontent.com",
        )

        private const val MAX_REDIRECTS = 5
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val READ_TIMEOUT_MILLIS = 30_000
        private const val PROGRESS_STEP_BYTES = 256L * 1024L
        private const val USER_AGENT =
            "XENOWAVE-Updater/1 (+https://github.com/paraecologia-arch/XENOWAVE)"
    }
}
