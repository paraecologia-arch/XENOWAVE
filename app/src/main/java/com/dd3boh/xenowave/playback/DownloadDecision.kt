package com.dd3boh.xenowave.playback

import androidx.media3.exoplayer.offline.Download
import java.time.LocalDateTime

enum class DownloadSource {
    ALBUM_BULK,
    ALBUM_TRACK_MENU,
    ARTIST_TRACK_MENU,
    OTHER,
}

enum class DownloadDecision {
    ENQUEUE,
    SKIP_ACTIVE,
    SKIP_COMPLETED,
    SKIP_REMOVING,
    RESET_STALE,
    RETRY,
}

internal fun downloadDecision(
    appState: LocalDateTime?,
    media3State: Int?,
): DownloadDecision {
    if (media3State == Download.STATE_COMPLETED || appState.isCompletedDownload()) {
        return DownloadDecision.SKIP_COMPLETED
    }

    if (media3State == Download.STATE_REMOVING) return DownloadDecision.SKIP_REMOVING

    if (media3State.isMedia3DownloadActive()) {
        return DownloadDecision.SKIP_ACTIVE
    }

    if (media3State == null) {
        return if (appState == DownloadUtil.STATE_DOWNLOADING) {
            DownloadDecision.RESET_STALE
        } else {
            DownloadDecision.ENQUEUE
        }
    }

    return if (media3State == Download.STATE_FAILED || media3State == Download.STATE_STOPPED) {
        if (appState == DownloadUtil.STATE_DOWNLOADING) {
            DownloadDecision.RESET_STALE
        } else {
            DownloadDecision.RETRY
        }
    } else {
        DownloadDecision.RESET_STALE
    }
}

internal fun Int?.isMedia3DownloadActive(): Boolean = when (this) {
    Download.STATE_QUEUED,
    Download.STATE_DOWNLOADING,
    Download.STATE_RESTARTING,
    -> true

    else -> false
}

internal fun LocalDateTime?.isCompletedDownload(): Boolean =
    this != null && this > DownloadUtil.STATE_DOWNLOADING

internal fun media3StateName(state: Int?): String = when (state) {
    Download.STATE_QUEUED -> "QUEUED"
    Download.STATE_STOPPED -> "STOPPED"
    Download.STATE_DOWNLOADING -> "DOWNLOADING"
    Download.STATE_COMPLETED -> "COMPLETED"
    Download.STATE_FAILED -> "FAILED"
    Download.STATE_REMOVING -> "REMOVING"
    Download.STATE_RESTARTING -> "RESTARTING"
    else -> "UNKNOWN"
}

internal fun appDownloadStateName(state: LocalDateTime?): String = when {
    state == null -> "NOT_DOWNLOADED"
    state == DownloadUtil.STATE_DOWNLOADING -> "ACTIVE"
    state.isCompletedDownload() -> "COMPLETED"
    else -> "INVALID"
}
