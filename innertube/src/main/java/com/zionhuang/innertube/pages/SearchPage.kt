package com.zionhuang.innertube.pages

import com.zionhuang.innertube.models.MusicResponsiveListItemRenderer
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.models.getContinuation
import com.zionhuang.innertube.models.stableKey
import com.zionhuang.innertube.models.response.SearchResponse

data class SearchResult(
    val items: List<YTItem>,
    val continuation: String? = null,
)

object SearchPage {
    fun toYTItem(renderer: MusicResponsiveListItemRenderer): YTItem? =
        SearchSummaryPage.fromMusicResponsiveListItemRenderer(renderer)

    /**
     * Reads every music shelf in the response, not just the last one. The filtered
     * search response currently uses musicShelfRenderer, but older/newer layouts may
     * add more than one section.
     */
    fun fromResponse(response: SearchResponse): SearchResult {
        val sections = response.contents?.tabbedSearchResultsRenderer?.tabs?.firstOrNull()
            ?.tabRenderer?.content?.sectionListRenderer?.contents
            ?: throw SearchResponseFormatException("search response has no section list")

        var recognizedSections = 0
        var itemCandidates = 0
        var parsedItems = 0
        var continuation: String? = null
        val items = mutableListOf<YTItem>()

        sections.forEach { section ->
            section.musicShelfRenderer?.let { shelf ->
                recognizedSections++
                shelf.contents.orEmpty().forEach { content ->
                    content.musicResponsiveListItemRenderer?.let { renderer ->
                        itemCandidates++
                        toYTItem(renderer)?.let {
                            items += it
                            parsedItems++
                        }
                    }
                }
                continuation = continuation ?: shelf.continuations?.getContinuation()
            }

            section.itemSectionRenderer?.let { itemSection ->
                recognizedSections++
                itemSection.contents.orEmpty().forEach { content ->
                    content.musicResponsiveListItemRenderer?.let { renderer ->
                        itemCandidates++
                        toYTItem(renderer)?.let {
                            items += it
                            parsedItems++
                        }
                    }
                }
            }
        }

        if (recognizedSections == 0 && sections.isNotEmpty()) {
            throw SearchResponseFormatException(
                "search response has ${sections.size} sections but none is recognized"
            )
        }
        if (itemCandidates > 0 && parsedItems == 0) {
            throw SearchResponseFormatException(
                "search response has $itemCandidates items but none could be parsed"
            )
        }

        return SearchResult(
            items = items.distinctBy { it.stableKey },
            continuation = continuation,
        )
    }

    /**
     * A continuation response without continuationContents is a format change, not a
     * legitimate end of list. An empty musicShelfContinuation with no next cursor is
     * the real end.
     */
    fun fromContinuationResponse(response: SearchResponse): SearchResult {
        val continuationContents = response.continuationContents
            ?: throw SearchResponseFormatException("continuation response has no continuationContents")
        val shelf = continuationContents.musicShelfContinuation
        val items = shelf.contents.mapNotNull { content ->
            toYTItem(content.musicResponsiveListItemRenderer)
        }
        if (shelf.contents.isNotEmpty() && items.isEmpty()) {
            throw SearchResponseFormatException("continuation items could not be parsed")
        }
        return SearchResult(
            items = items.distinctBy { it.stableKey },
            continuation = shelf.continuations?.getContinuation(),
        )
    }
}
