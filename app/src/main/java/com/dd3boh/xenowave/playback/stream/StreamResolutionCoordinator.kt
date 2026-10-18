package com.dd3boh.xenowave.playback.stream

import com.dd3boh.xenowave.constants.AudioQuality
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

sealed interface StreamResolutionEvent {
    val mediaId: String

    data class CacheHit(
        override val mediaId: String,
        val clientName: String,
        val rangePolicy: RangePolicy,
        val remainingTtlMs: Long,
    ) : StreamResolutionEvent

    data class Expired(override val mediaId: String) : StreamResolutionEvent
    data class Started(override val mediaId: String) : StreamResolutionEvent
    data class Joined(override val mediaId: String) : StreamResolutionEvent

    data class Succeeded(
        override val mediaId: String,
        val durationMs: Long,
        val clientName: String,
        val rangePolicy: RangePolicy,
        val contentLengthKnown: Boolean,
    ) : StreamResolutionEvent

    data class Failed(
        override val mediaId: String,
        val durationMs: Long,
        val errorType: String,
    ) : StreamResolutionEvent
}

/**
 * Coordinates stream resolutions by media id.
 *
 * A ready [ResolvedStream] is reused while it is valid. While a resolution is running, additional
 * callers for the same media id join that resolution instead of starting a duplicate extraction.
 * Failures and expired values are never retained, so a later request can retry normally.
 */
class StreamResolutionCoordinator(
    private val resolver: StreamResolver,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val nowNs: () -> Long = System::nanoTime,
    private val onEvent: (StreamResolutionEvent) -> Unit = {},
) {
    private val resolvedStreams = ConcurrentHashMap<String, ResolvedStream>()
    private val inFlightResolutions = ConcurrentHashMap<String, CompletableDeferred<Result<ResolvedStream>>>()

    suspend fun resolve(
        videoId: String,
        audioQuality: AudioQuality,
        isActiveNetworkMetered: Boolean,
        playlistId: String? = null,
        transform: suspend (ResolvedStream) -> ResolvedStream = { it },
    ): Result<ResolvedStream> {
        val cachedStream = resolvedStreams[videoId]
        if (cachedStream != null && cachedStream.expiresAtEpochMs > nowMs()) {
            onEvent(
                StreamResolutionEvent.CacheHit(
                    mediaId = videoId,
                    clientName = cachedStream.clientName,
                    rangePolicy = cachedStream.rangePolicy,
                    remainingTtlMs = cachedStream.expiresAtEpochMs - nowMs(),
                )
            )
            return Result.success(cachedStream)
        }
        if (cachedStream != null) {
            resolvedStreams.remove(videoId, cachedStream)
            onEvent(StreamResolutionEvent.Expired(videoId))
        }

        val newResolution = CompletableDeferred<Result<ResolvedStream>>()
        val existingResolution = inFlightResolutions.putIfAbsent(videoId, newResolution)
        if (existingResolution != null) {
            onEvent(StreamResolutionEvent.Joined(videoId))
            return existingResolution.await()
        }

        scope.launch {
            val startedAtNs = nowNs()
            onEvent(StreamResolutionEvent.Started(videoId))
            val result = runCatching {
                val resolvedStream = resolver.resolve(
                    videoId = videoId,
                    audioQuality = audioQuality,
                    isActiveNetworkMetered = isActiveNetworkMetered,
                    playlistId = playlistId,
                ).getOrThrow()
                transform(resolvedStream)
            }
            result
                .onSuccess { stream ->
                    if (stream.expiresAtEpochMs > nowMs()) {
                        resolvedStreams[videoId] = stream
                    }
                    onEvent(
                        StreamResolutionEvent.Succeeded(
                            mediaId = videoId,
                            durationMs = elapsedMs(startedAtNs),
                            clientName = stream.clientName,
                            rangePolicy = stream.rangePolicy,
                            contentLengthKnown = stream.format.contentLength != null,
                        )
                    )
                }
                .onFailure { throwable ->
                    onEvent(
                        StreamResolutionEvent.Failed(
                            mediaId = videoId,
                            durationMs = elapsedMs(startedAtNs),
                            errorType = throwable.javaClass.simpleName,
                        )
                    )
                }
            newResolution.complete(result)
            inFlightResolutions.remove(videoId, newResolution)
        }

        return newResolution.await()
    }

    private fun elapsedMs(startedAtNs: Long): Long =
        ((nowNs() - startedAtNs).coerceAtLeast(0L) + 500_000L) / 1_000_000L
}
