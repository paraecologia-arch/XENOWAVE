package com.dd3boh.xenowave.playback

import androidx.media3.exoplayer.offline.Download
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class DownloadDecisionTest {
    private val completedAt = LocalDateTime.of(2026, 1, 1, 0, 0)

    @Test
    fun enqueuesWhenNoDownloadExists() {
        assertEquals(
            DownloadDecision.ENQUEUE,
            downloadDecision(appState = null, media3State = null),
        )
    }

    @Test
    fun skipsCompletedDownloads() {
        assertEquals(
            DownloadDecision.SKIP_COMPLETED,
            downloadDecision(completedAt, Download.STATE_COMPLETED),
        )
        assertEquals(
            DownloadDecision.SKIP_COMPLETED,
            downloadDecision(completedAt, null),
        )
    }

    @Test
    fun skipsOnlyRealActiveDownloads() {
        listOf(
            Download.STATE_QUEUED,
            Download.STATE_DOWNLOADING,
            Download.STATE_RESTARTING,
        ).forEach { state ->
            assertEquals(
                DownloadDecision.SKIP_ACTIVE,
                downloadDecision(null, state),
            )
        }
    }

    @Test
    fun retriesTerminalDownloads() {
        assertEquals(
            DownloadDecision.RETRY,
            downloadDecision(null, Download.STATE_FAILED),
        )
        assertEquals(
            DownloadDecision.RETRY,
            downloadDecision(DownloadUtil.STATE_INVALID, Download.STATE_STOPPED),
        )
    }

    @Test
    fun resetsStaleActiveStateWhenDownloadIndexHasNoRequest() {
        assertEquals(
            DownloadDecision.RESET_STALE,
            downloadDecision(DownloadUtil.STATE_DOWNLOADING, null),
        )
    }

    @Test
    fun resetsStaleActiveStateWhenMedia3Failed() {
        assertEquals(
            DownloadDecision.RESET_STALE,
            downloadDecision(DownloadUtil.STATE_DOWNLOADING, Download.STATE_FAILED),
        )
    }

    @Test
    fun doesNotEnqueueWhileRemovalIsInProgress() {
        assertEquals(
            DownloadDecision.SKIP_REMOVING,
            downloadDecision(null, Download.STATE_REMOVING),
        )
    }

    @Test
    fun mixedAlbumBatchKeepsPerTrackSemantics() {
        val appStates = listOf(
            completedAt,
            DownloadUtil.STATE_DOWNLOADING,
            null,
            DownloadUtil.STATE_INVALID,
        )
        val media3States = listOf(
            Download.STATE_COMPLETED,
            Download.STATE_QUEUED,
            null,
            Download.STATE_FAILED,
        )

        val decisions = appStates.zip(media3States) { appState, media3State ->
            downloadDecision(appState, media3State)
        }

        assertEquals(
            listOf(
                DownloadDecision.SKIP_COMPLETED,
                DownloadDecision.SKIP_ACTIVE,
                DownloadDecision.ENQUEUE,
                DownloadDecision.RETRY,
            ),
            decisions,
        )
    }

    @Test
    fun allDownloadEntrypointsUseTheSameDecisionSemantics() {
        val entrypoints = listOf(
            DownloadSource.ALBUM_BULK,
            DownloadSource.ALBUM_TRACK_MENU,
            DownloadSource.ARTIST_TRACK_MENU,
        )

        assertEquals(
            List(entrypoints.size) { DownloadDecision.ENQUEUE },
            entrypoints.map { downloadDecision(null, null) },
        )
    }
}
