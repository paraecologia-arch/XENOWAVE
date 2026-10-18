package com.dd3boh.xenowave.utils

import androidx.media3.exoplayer.offline.Download
import com.dd3boh.xenowave.playback.DownloadUtil
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class DownloadStateTest {
    private val completedAt = LocalDateTime.of(2026, 1, 1, 0, 0)

    @Test
    fun allTracksCompletedMeansAlbumCompleted() {
        assertEquals(
            Download.STATE_COMPLETED,
            getDownloadState(List(11) { completedAt }),
        )
    }

    @Test
    fun emptyAlbumShowsStopped() {
        assertEquals(
            Download.STATE_STOPPED,
            getDownloadState(emptyList()),
        )
    }

    @Test
    fun anyActiveTrackMeansAlbumDownloading() {
        val states = List(5) { completedAt } +
            List(2) { DownloadUtil.STATE_DOWNLOADING } +
            List(4) { null }

        assertEquals(
            Download.STATE_DOWNLOADING,
            getDownloadState(states),
        )
    }

    @Test
    fun missingOrFailedTrackDoesNotHideOtherFailuresAsActive() {
        val states = List(10) { completedAt } + listOf(null)

        assertEquals(
            Download.STATE_STOPPED,
            getDownloadState(states),
        )
    }

    @Test
    fun queuedAlbumShowsDownloading() {
        assertEquals(
            Download.STATE_DOWNLOADING,
            getDownloadState(List(11) { DownloadUtil.STATE_DOWNLOADING }),
        )
    }

    @Test
    fun staleOrFailedTrackShowsStopped() {
        assertEquals(
            Download.STATE_STOPPED,
            getDownloadState(listOf(DownloadUtil.STATE_INVALID)),
        )
    }
}
