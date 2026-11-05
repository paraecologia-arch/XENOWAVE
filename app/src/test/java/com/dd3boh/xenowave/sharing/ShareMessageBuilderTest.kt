package com.dd3boh.xenowave.sharing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareMessageBuilderTest {

    @Test
    fun `normalizes a hostile title`() {
        assertEquals(
            "Minha lista favorita",
            ShareMessageBuilder.sanitizeTitle("  Minha\n\tlista   favorita  "),
        )
    }

    @Test
    fun `online playlist message keeps both official links`() {
        val message = ShareMessageBuilder.onlinePlaylist(
            template = "Listen: %1\$s\n%2\$s\nApp: %3\$s %4\$s\n%5\$s",
            visibilityHintTemplate = "Check visibility.",
            title = "Favoritas",
            playlistUrl = "https://music.youtube.com/playlist?list=PL123",
            appName = "XENOWAVE",
            downloadUrl = OfficialAppLinkProvider.DOWNLOAD_URL,
        )
        assertTrue(message.contains("https://music.youtube.com/playlist?list=PL123"))
        assertTrue(message.contains(OfficialAppLinkProvider.DOWNLOAD_URL))
        assertTrue(message.contains("Check visibility."))
        assertFalse(message.contains("/storage/"))
    }

    @Test
    fun `app invite uses the simple download page and app name`() {
        val message = ShareMessageBuilder.appInvite(
            template = "Use %1\$s: %2\$s",
            appName = "XENOWAVE",
            downloadUrl = OfficialAppLinkProvider.DOWNLOAD_URL,
        )
        assertEquals(
            "Use XENOWAVE: https://paraecologia-arch.github.io/XENOWAVE/",
            message,
        )
    }

    @Test
    fun `download destination is an https page on the official host`() {
        val url = java.net.URI(OfficialAppLinkProvider.DOWNLOAD_URL)
        assertEquals("https", url.scheme)
        assertEquals("paraecologia-arch.github.io", url.host)
        assertEquals("/XENOWAVE/", url.path)
        assertFalse(OfficialAppLinkProvider.DOWNLOAD_URL.contains("releases"))
    }

    @Test
    fun `local summary reports included and excluded counts`() {
        val message = ShareMessageBuilder.localPlaylistSummary(
            template = "%1\$s: %2\$d online, %3\$d excluded",
            title = "Lista",
            included = 4,
            excluded = 2,
        )
        assertEquals("Lista: 4 online, 2 excluded", message)
    }
}
