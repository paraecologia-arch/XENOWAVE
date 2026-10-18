package com.dd3boh.xenowave.playback

import androidx.media3.common.PlaybackException
import java.io.IOException

internal class StreamResolutionException(
    message: String,
    cause: Throwable,
    val resolutionErrorCode: Int,
) : IOException(message, cause)

internal data class PlaybackErrorInfo(
    val errorCode: Int,
    val errorCodeName: String,
    val rootExceptionType: String,
    val resolutionErrorCode: Int?,
)

internal fun PlaybackException.toPlaybackErrorInfo(): PlaybackErrorInfo {
    val chain = generateSequence(this as Throwable?) { it.cause }.toList()
    val resolutionFailure = chain.filterIsInstance<StreamResolutionException>().firstOrNull()
    val root = chain.lastOrNull() ?: this
    return PlaybackErrorInfo(
        errorCode = errorCode,
        errorCodeName = errorCodeName,
        rootExceptionType = root::class.java.simpleName,
        resolutionErrorCode = resolutionFailure?.resolutionErrorCode,
    )
}

internal fun PlaybackException.hasErrorCodeInCauseChain(code: Int): Boolean =
    generateSequence(this as Throwable?) { it.cause }
        .filterIsInstance<PlaybackException>()
        .any { it.errorCode == code }
