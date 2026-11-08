package com.dd3boh.xenowave.sharing

import com.dd3boh.xenowave.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PlaylistTransferExporterTest {
    @Test
    fun `provider paths expose only the two dedicated narrow directories`() {
        val context = RuntimeEnvironment.getApplication()
        val parser = context.resources.getXml(R.xml.provider_paths)
        val tags = mutableListOf<Pair<String, Map<String, String>>>()
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                val attributes = buildMap {
                    for (index in 0 until parser.attributeCount) {
                        put(parser.getAttributeName(index), parser.getAttributeValue(index))
                    }
                }
                tags += parser.name to attributes
            }
            event = parser.next()
        }

        val cachePaths = tags.filter { it.first == "cache-path" }
            .associateBy { it.second["name"] }
        assertEquals(
            setOf("playlist_exports", "musical_age_cards"),
            cachePaths.keys,
        )
        assertEquals(
            "playlist-exports",
            cachePaths.getValue("playlist_exports").second["path"],
        )
        assertEquals(
            "musical-age-cards",
            cachePaths.getValue("musical_age_cards").second["path"],
        )
        assertTrue(
            tags.none {
                it.first in setOf(
                    "external-path",
                    "external-files-path",
                    "external-cache-path",
                    "files-path",
                    "root-path",
                )
            },
        )
    }

    @Test
    fun `export writes only a returnable playlist reference file`() {
        val context = RuntimeEnvironment.getApplication()
        val result = PlaylistTransferFormat.export(
            playlistName = "Fixture playlist",
            songs = listOf(
                ExportSong(
                    videoId = "dQw4w9WgXcQ",
                    title = "Fixture song",
                    artists = listOf("Fixture artist"),
                    isLocal = false,
                )
            ),
        ) as PlaylistExportResult.Success

        runCatching { PlaylistTransferExporter.write(context, result) }
        val directory = File(context.cacheDir, "playlist-exports")
        val files = directory.listFiles().orEmpty()
        assertTrue(files.isNotEmpty())
        val text = files.first().readText()
        assertTrue(text.contains("dQw4w9WgXcQ"))
        assertTrue(text.contains("xenowave-playlist-references"))
    }
}
