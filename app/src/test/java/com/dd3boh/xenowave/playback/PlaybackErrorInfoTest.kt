package com.dd3boh.xenowave.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackErrorInfoTest {
    @Test
    fun retainsResolutionFailureBehindMedia3SourceError() {
        val resolverError = PlaybackException(
            "Resolver rejected the source",
            IllegalStateException("provider failure"),
            PlaybackException.ERROR_CODE_REMOTE_ERROR,
        )
        val sourceError = PlaybackException(
            "Source error",
            StreamResolutionException(
                "Media stream resolution failed",
                resolverError,
                resolverError.errorCode,
            ),
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        )

        val info = sourceError.toPlaybackErrorInfo()

        assertEquals(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, info.errorCode)
        assertEquals(PlaybackException.ERROR_CODE_REMOTE_ERROR, info.resolutionErrorCode)
        assertEquals("IllegalStateException", info.rootExceptionType)
    }

    @Test
    fun findsOnlyMatchingPlaybackCodeInCauseChain() {
        val nested = PlaybackException(
            "offline",
            null,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        )
        val outer = PlaybackException(
            "source",
            nested,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        )

        assertTrue(outer.hasErrorCodeInCauseChain(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertFalse(outer.hasErrorCodeInCauseChain(PlaybackException.ERROR_CODE_DECODING_FAILED))
    }
}
