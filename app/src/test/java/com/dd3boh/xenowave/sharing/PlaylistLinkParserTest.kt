package com.dd3boh.xenowave.sharing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistLinkParserTest {

    @Test
    fun `picks playlist from a two link message`() {
        val text = """
            Ouça minha playlist no XENOWAVE:
            https://music.youtube.com/playlist?list=PL1234567890abcdef

            Baixe o app: https://github.com/paraecologia-arch/XENOWAVE/releases
        """.trimIndent()

        val link = PlaylistLinkParser.find(text)
        assertEquals("music.youtube.com", link?.host)
        assertEquals("PL1234567890abcdef", link?.playlistId)
        assertEquals(
            "https://music.youtube.com/playlist?list=PL1234567890abcdef",
            link?.normalizedUrl,
        )
    }

    @Test
    fun `accepts watch URLs that carry a list parameter`() {
        val link = PlaylistLinkParser.parse(
            "https://www.youtube.com/watch?v=abc123&list=PL1234567890abcdef"
        )
        assertEquals("PL1234567890abcdef", link?.playlistId)
    }

    @Test
    fun `rejects the download link when no playlist is present`() {
        assertNull(
            PlaylistLinkParser.find(
                "Baixe em https://github.com/paraecologia-arch/XENOWAVE/releases"
            )
        )
    }

    @Test
    fun `rejects lookalike hosts and insecure schemes`() {
        assertNull(PlaylistLinkParser.parse("https://youtube.com.evil.test/playlist?list=PL123456"))
        assertNull(PlaylistLinkParser.parse("http://music.youtube.com/playlist?list=PL123456"))
        assertNull(PlaylistLinkParser.parse("https://music.youtube.com/playlist?list=../../etc"))
    }

    @Test
    fun `rejects malformed and oversized input`() {
        assertNull(PlaylistLinkParser.parse("not a url"))
        assertNull(PlaylistLinkParser.find("x".repeat(100_001)))
    }
}
