package com.zionhuang.innertube.pages

import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.response.SearchResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchResponseParserTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Test
    fun `flat item sections are parsed instead of shown as no results`() {
        val page = SearchSummaryPage.fromResponse(
            json.decodeFromString<SearchResponse>(summaryWithFlatSections())
        )

        assertEquals(2, page.summaries.size)
        assertEquals(6, page.itemCount)

        val card = page.summaries.first().items.single() as PlaylistItem
        assertEquals("Sucessos suaves dos 70", card.title)
        assertEquals("RDCLAK5uy_card", card.id)

        val flat = page.summaries.last().items
        assertEquals(5, flat.size)
        assertTrue(flat.any { it is SongItem && it.id == "song1" })
        assertTrue(flat.any { it is SongItem && it.id == "song2" && it.thumbnail.isEmpty() })
        assertTrue(flat.any { it is AlbumItem && it.id == "MPREb_album" })
        assertTrue(flat.any { it is ArtistItem && it.id == "UCartist" })
        assertTrue(flat.any { it is PlaylistItem && it.id == "PLflat" })
    }

    @Test
    fun `legacy music shelf keeps its title and items`() {
        val page = SearchSummaryPage.fromResponse(
            json.decodeFromString<SearchResponse>(legacyShelf())
        )

        assertEquals("Músicas", page.summaries.single().title)
        assertEquals("song1", (page.summaries.single().items.single() as SongItem).id)
    }

    @Test
    fun `empty section list is a valid empty result`() {
        val page = SearchSummaryPage.fromResponse(
            json.decodeFromString<SearchResponse>(emptySections())
        )

        assertTrue(page.summaries.isEmpty())
    }

    @Test
    fun `unknown section format is an observed compatibility error`() {
        assertThrows(SearchResponseFormatException::class.java) {
            SearchSummaryPage.fromResponse(
                json.decodeFromString<SearchResponse>(unknownSection())
            )
        }
    }

    @Test
    fun `filtered search reads every shelf and keeps continuation`() {
        val result = SearchPage.fromResponse(
            json.decodeFromString<SearchResponse>(twoFilteredShelves())
        )

        assertEquals(listOf("song1", "song2"), result.items.map { it.id })
        assertEquals("continuation-token", result.continuation)
    }

    @Test
    fun `filtered search reports unknown format instead of an empty list`() {
        val error = assertThrows(SearchResponseFormatException::class.java) {
            SearchPage.fromResponse(json.decodeFromString<SearchResponse>(unknownSection()))
        }
        assertNotNull(error.message)
    }

    @Test
    fun `same raw id in different item types is not collapsed`() {
        val page = SearchSummaryPage.fromResponse(
            json.decodeFromString<SearchResponse>(duplicateRawId())
        )

        val items = page.summaries.single().items
        assertEquals(2, items.size)
        assertTrue(items.any { it is SongItem && it.id == "same" })
        assertTrue(items.any { it is PlaylistItem && it.id == "same" })
    }

    @Test
    fun `continuation end is distinct from a missing continuation payload`() {
        val end = SearchPage.fromContinuationResponse(
            json.decodeFromString<SearchResponse>(continuationEnd())
        )
        assertTrue(end.items.isEmpty())
        assertNull(end.continuation)

        assertThrows(SearchResponseFormatException::class.java) {
            SearchPage.fromContinuationResponse(
                json.decodeFromString<SearchResponse>(missingContinuation())
            )
        }
    }

    @Test
    fun `continuation page keeps items and the next cursor`() {
        val result = SearchPage.fromContinuationResponse(
            json.decodeFromString<SearchResponse>(continuationPage())
        )

        assertEquals(listOf("song1"), result.items.map { it.id })
        assertEquals("next-cursor", result.continuation)
    }

    private fun summaryWithFlatSections() = """
        {
          "contents": {
            "tabbedSearchResultsRenderer": {
              "tabs": [{
                "tabRenderer": {
                  "content": {
                    "sectionListRenderer": {
                      "contents": [
                        {
                          "musicCardShelfRenderer": {
                            "title": {"runs": [{"text": "Sucessos suaves dos 70"}]},
                            "subtitle": {"runs": [
                              {"text": "Playlist"}, {"text": " • "}, {"text": "YouTube Music"}
                            ]},
                            "thumbnail": ${thumbnail("card")},
                            "buttons": [
                              {"buttonRenderer": {
                                "text": {"runs": [{"text": ""}]},
                                "icon": {"iconType": "MUSIC_SHUFFLE"},
                                "command": {"watchPlaylistEndpoint": {"playlistId": "RDCLAK5uy_card"}}
                              }},
                              {"buttonRenderer": {
                                "text": {"runs": [{"text": ""}]},
                                "icon": {"iconType": "PLAY_ARROW"},
                                "command": {"watchPlaylistEndpoint": {"playlistId": "RDCLAK5uy_card"}}
                              }}
                            ],
                            "onTap": {
                              "browseEndpoint": {
                                "browseId": "VLRDCLAK5uy_card",
                                "browseEndpointContextSupportedConfigs": {
                                  "browseEndpointContextMusicConfig": {
                                    "pageType": "MUSIC_PAGE_TYPE_PLAYLIST"
                                  }
                                }
                              }
                            },
                            "contents": []
                          }
                        },
                        {
                          "itemSectionRenderer": {
                            "contents": [
                              {"musicResponsiveListItemRenderer": ${songItem("song1", "Música • Queen", withThumbnail = true)}},
                              {"musicResponsiveListItemRenderer": ${songItem("song2", "Música • Artist", withThumbnail = false)}},
                              {"musicResponsiveListItemRenderer": ${albumItem()}},
                              {"musicResponsiveListItemRenderer": ${artistItem()}},
                              {"musicResponsiveListItemRenderer": ${playlistItem()}}
                            ]
                          }
                        }
                      ]
                    }
                  }
                }
              }]
            }
          }
        }
    """.trimIndent()

    private fun legacyShelf() = """
        {
          "contents": {
            "tabbedSearchResultsRenderer": {
              "tabs": [{
                "tabRenderer": {
                  "content": {
                    "sectionListRenderer": {
                      "contents": [{
                        "musicShelfRenderer": {
                          "title": {"runs": [{"text": "Músicas"}]},
                          "contents": [
                            {"musicResponsiveListItemRenderer": ${songItem("song1", "Música • Queen", withThumbnail = true)}}
                          ]
                        }
                      }]
                    }
                  }
                }
              }]
            }
          }
        }
    """.trimIndent()

    private fun emptySections() = """
        {"contents": {"tabbedSearchResultsRenderer": {"tabs": [{
          "tabRenderer": {"content": {"sectionListRenderer": {"contents": []}}}
        }]}}}
    """.trimIndent()

    private fun unknownSection() = """
        {"contents": {"tabbedSearchResultsRenderer": {"tabs": [{
          "tabRenderer": {"content": {"sectionListRenderer": {"contents": [
            {"shelfRenderer": {"title": {"runs": [{"text": "old"}]}}}
          ]}}}
        }]}}}
    """.trimIndent()

    private fun twoFilteredShelves() = """
        {"contents": {"tabbedSearchResultsRenderer": {"tabs": [{
          "tabRenderer": {"content": {"sectionListRenderer": {"contents": [
            {"musicShelfRenderer": {
              "title": {"runs": [{"text": "Músicas"}]},
              "contents": [
                {"musicResponsiveListItemRenderer": ${songItem("song1", "Música • Queen", withThumbnail = true)}}
              ],
              "continuations": [{"nextContinuationData": {"continuation": "continuation-token"}}]
            }},
            {"musicShelfRenderer": {
              "title": {"runs": [{"text": "Vídeos"}]},
              "contents": [
                {"musicResponsiveListItemRenderer": ${songItem("song2", "Vídeo • Artist", withThumbnail = true)}}
              ]
            }}
          ]}}}
        }]}}}
    """.trimIndent()

    private fun duplicateRawId() = """
        {"contents": {"tabbedSearchResultsRenderer": {"tabs": [{
          "tabRenderer": {"content": {"sectionListRenderer": {"contents": [
            {"itemSectionRenderer": {"contents": [
              {"musicResponsiveListItemRenderer": ${songItem("same", "Música • A", withThumbnail = true)}},
              {"musicResponsiveListItemRenderer": ${playlistItem("same")}}
            ]}}
          ]}}}
        }]}}}
    """.trimIndent()

    private fun continuationEnd() = """
        {"continuationContents": {"musicShelfContinuation": {
          "contents": [], "continuations": []
        }}}
    """.trimIndent()

    private fun continuationPage() = """
        {"continuationContents": {"musicShelfContinuation": {
          "contents": [
            {"musicResponsiveListItemRenderer": ${songItem("song1", "Música • Queen", withThumbnail = true)}}
          ],
          "continuations": [{"nextContinuationData": {"continuation": "next-cursor"}}]
        }}}
    """.trimIndent()

    private fun missingContinuation() = """
        {"contents": null}
    """.trimIndent()

    private fun songItem(id: String, line: String, withThumbnail: Boolean): String = """
        {
          "flexColumns": [
            {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Song $id"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": ${lineRuns(line)}}}}
          ],
          ${if (withThumbnail) "\"thumbnail\": ${thumbnail(id)}," else ""}
          "playlistItemData": {"videoId": "$id"}
        }
    """.trimIndent()

    private fun lineRuns(line: String): String {
        val parts = line.split(" • ")
        return parts.flatMapIndexed { index, part ->
            buildList {
                if (index > 0) add("{\"text\": \" • \"}")
                add("{\"text\": \"$part\"}")
            }
        }.joinToString(prefix = "[", postfix = "]", separator = ", ")
    }

    private fun albumItem() = """
        {
          "flexColumns": [
            {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Album"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [
              {"text": "Álbum"}, {"text": " • "}, {"text": "Queen"}, {"text": " • "}, {"text": "1981"}
            ]}}}
          ],
          "thumbnail": ${thumbnail("album")},
          "navigationEndpoint": {
            "browseEndpoint": {
              "browseId": "MPREb_album",
              "browseEndpointContextSupportedConfigs": {
                "browseEndpointContextMusicConfig": {"pageType": "MUSIC_PAGE_TYPE_ALBUM"}
              }
            }
          },
          "overlay": {
            "musicItemThumbnailOverlayRenderer": {
              "content": {
                "musicPlayButtonRenderer": {
                  "playNavigationEndpoint": {"watchPlaylistEndpoint": {"playlistId": "OLAK5uy_album"}}
                }
              }
            }
          }
        }
    """.trimIndent()

    private fun artistItem() = """
        {
          "flexColumns": [
            {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Queen"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [
              {"text": "Artista"}, {"text": " • "}, {"text": "119 mi ouvintes"}
            ]}}}
          ],
          "thumbnail": ${thumbnail("artist")},
          "navigationEndpoint": {
            "browseEndpoint": {
              "browseId": "UCartist",
              "browseEndpointContextSupportedConfigs": {
                "browseEndpointContextMusicConfig": {"pageType": "MUSIC_PAGE_TYPE_ARTIST"}
              }
            }
          }
        }
    """.trimIndent()

    private fun playlistItem(id: String = "PLflat") = """
        {
          "flexColumns": [
            {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Playlist"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [
              {"text": "Playlist"}, {"text": " • "}, {"text": "Erick"}, {"text": " • "}, {"text": "7 músicas"}
            ]}}}
          ],
          "thumbnail": ${thumbnail("playlist")},
          "navigationEndpoint": {
            "browseEndpoint": {
              "browseId": "VL$id",
              "browseEndpointContextSupportedConfigs": {
                "browseEndpointContextMusicConfig": {"pageType": "MUSIC_PAGE_TYPE_PLAYLIST"}
              }
            }
          },
          "overlay": {
            "musicItemThumbnailOverlayRenderer": {
              "content": {
                "musicPlayButtonRenderer": {
                  "playNavigationEndpoint": {"watchPlaylistEndpoint": {"playlistId": "$id"}}
                }
              }
            }
          }
        }
    """.trimIndent()

    private fun thumbnail(name: String) = """
        {"musicThumbnailRenderer": {"thumbnail": {"thumbnails": [
          {"url": "https://example.test/$name.jpg", "width": 60, "height": 60}
        ]}}}
    """.trimIndent()
}
