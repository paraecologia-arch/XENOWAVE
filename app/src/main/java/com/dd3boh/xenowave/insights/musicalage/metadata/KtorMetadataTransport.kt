package com.dd3boh.xenowave.insights.musicalage.metadata

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Ktor/OkHttp transport used in production. Unlike HttpURLConnection,
 * cancelling the coroutine cancels the underlying OkHttp call and closes the
 * socket. The client is isolated per call: no cookies, no redirects, no
 * shared session.
 *
 * [allowedHosts] and [requireHttps] are test-only overrides; the production
 * module always uses the MusicBrainz allowlist and HTTPS.
 */
class KtorMetadataTransport(
    private val userAgent: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val allowedHosts: Set<String> = setOf("musicbrainz.org", "www.musicbrainz.org"),
    private val requireHttps: Boolean = true,
) : MetadataTransport {
    override suspend fun execute(
        request: MetadataRequest,
        maxResponseBytes: Long,
        timeoutMillis: Long,
    ): MetadataResponse {
        if (timeoutMillis <= 0L) {
            throw MetadataTransportException("Orçamento de tempo esgotado")
        }
        return try {
            withTimeout(timeoutMillis) {
                withContext(dispatcher) {
                    executeCancellable(request, maxResponseBytes)
                }
            }
        } catch (e: TimeoutCancellationException) {
            // Parent cancellation propagates; our own deadline becomes a
            // normal, expected transport failure.
            if (coroutineContext[Job]?.isActive == false) throw e
            throw MetadataTransportException("Tempo total da consulta esgotado", e)
        }
    }

    private suspend fun executeCancellable(
        request: MetadataRequest,
        maxResponseBytes: Long,
    ): MetadataResponse {
        val url = URL(request.url)
        if ((requireHttps && url.protocol != "https") ||
            url.host !in allowedHosts ||
            (requireHttps && url.port != -1 && url.port != 443) ||
            !url.path.startsWith("/ws/2/") ||
            url.userInfo != null
        ) {
            throw MetadataTransportException("URL de metadados não permitida")
        }

        val client = HttpClient(OkHttp) {
            followRedirects = false
            expectSuccess = false
            engine {
                config {
                    followRedirects(false)
                    retryOnConnectionFailure(false)
                }
            }
        }
        try {
            val response = client.get(url.toString()) {
                header("User-Agent", userAgent)
                header("Accept", "application/json")
                header("Accept-Encoding", "identity")
                header("Connection", "close")
                request.headers.forEach { (key, value) -> header(key, value) }
            }
            val headers = response.headers.entries()
                .associate { entry ->
                    entry.key.lowercase() to (entry.value.firstOrNull().orEmpty())
                }
            val body = readLimited(response.bodyAsChannel(), maxResponseBytes)
            return MetadataResponse(
                statusCode = response.status.value,
                body = body,
                headers = headers,
            )
        } finally {
            client.close()
        }
    }

    private suspend fun readLimited(
        channel: ByteReadChannel,
        maxBytes: Long,
    ): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var total = 0L
        while (!channel.isClosedForRead) {
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > maxBytes) {
                throw MetadataResponseTooLargeException(
                    "Resposta de metadados excedeu $maxBytes bytes"
                )
            }
            output.write(buffer, 0, read)
            coroutineContext.ensureActive()
        }
        return output.toString(Charsets.UTF_8.name())
    }
}
