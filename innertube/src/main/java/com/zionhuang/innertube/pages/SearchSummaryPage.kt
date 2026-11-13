package com.zionhuang.innertube.pages

import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.MusicCardShelfRenderer
import com.zionhuang.innertube.models.MusicResponsiveListItemRenderer
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.Run
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.models.oddElements
import com.zionhuang.innertube.models.splitBySeparator
import com.zionhuang.innertube.models.stableKey
import com.zionhuang.innertube.models.response.SearchResponse
import com.zionhuang.innertube.utils.parseTime

class SearchResponseFormatException(message: String) : IllegalStateException(message)

data class SearchSummary(
    val title: String,
    val items: List<YTItem>,
    val related: Boolean = false,
)

data class SearchSummaryPage(
    val summaries: List<SearchSummary>,
) {
    val itemCount: Int
        get() = summaries.sumOf { it.items.size }

    companion object {
        /**
         * Parses both the legacy grouped response (musicShelfRenderer) and the flat
         * response currently returned by YouTube Music (itemSectionRenderer).
         *
         * A missing/unknown section format is reported as a failure instead of looking
         * like a legitimate empty result.
         */
        fun fromResponse(response: SearchResponse): SearchSummaryPage {
            val sections = response.contents?.tabbedSearchResultsRenderer?.tabs?.firstOrNull()
                ?.tabRenderer?.content?.sectionListRenderer?.contents
                ?: throw SearchResponseFormatException("search response has no section list")

            var recognizedSections = 0
            var itemCandidates = 0
            var parsedItems = 0
            val summaries = mutableListOf<SearchSummary>()
            val flatItems = mutableListOf<YTItem>()

            sections.forEach { section ->
                when {
                    section.musicCardShelfRenderer != null -> {
                        recognizedSections++
                        val card = section.musicCardShelfRenderer
                        itemCandidates++
                        val cardItem = fromMusicCardShelfRenderer(card)
                        if (cardItem != null) parsedItems++

                        val relatedItems = card.contents.orEmpty().mapNotNull { content ->
                            content.musicResponsiveListItemRenderer?.let { renderer ->
                                itemCandidates++
                                fromMusicResponsiveListItemRenderer(renderer)?.also { parsedItems++ }
                            }
                        }
                        val items = (listOfNotNull(cardItem) + relatedItems)
                            .distinctBy { it.stableKey }
                        if (items.isNotEmpty()) {
                            summaries += SearchSummary(
                                title = card.header?.musicCardShelfHeaderBasicRenderer?.title?.runs
                                    ?.joinToString("") { it.text }
                                    .orEmpty(),
                                items = items,
                            )
                        }
                    }

                    section.musicShelfRenderer != null -> {
                        recognizedSections++
                        val shelf = section.musicShelfRenderer
                        val items = shelf.contents.orEmpty().mapNotNull { content ->
                            content.musicResponsiveListItemRenderer?.let { renderer ->
                                itemCandidates++
                                fromMusicResponsiveListItemRenderer(renderer)?.also { parsedItems++ }
                            }
                        }.distinctBy { it.stableKey }
                        if (items.isNotEmpty()) {
                            summaries += SearchSummary(
                                title = shelf.title?.runs?.joinToString("") { it.text }.orEmpty(),
                                items = items,
                            )
                        }
                    }

                    section.itemSectionRenderer != null -> {
                        recognizedSections++
                        section.itemSectionRenderer.contents.orEmpty().forEach { content ->
                            content.musicResponsiveListItemRenderer?.let { renderer ->
                                itemCandidates++
                                fromMusicResponsiveListItemRenderer(renderer)?.let {
                                    flatItems += it
                                    parsedItems++
                                }
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

            if (flatItems.isNotEmpty()) {
                summaries += SearchSummary(
                    title = "",
                    items = flatItems.distinctBy { it.stableKey },
                )
            }
            return SearchSummaryPage(summaries)
        }

        internal fun fromMusicCardShelfRenderer(renderer: MusicCardShelfRenderer): YTItem? {
            val title = renderer.title.runs?.firstOrNull()?.text
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val subtitle = renderer.subtitle.runs?.splitBySeparator().orEmpty()
            val subtitleText = renderer.subtitle.runs?.joinToString("") { it.text }.orEmpty()
            val thumbnail = renderer.thumbnail.musicThumbnailRenderer?.getThumbnailUrl()
            val explicit = renderer.subtitleBadges?.find {
                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
            } != null
            val watchEndpoint = renderer.onTap.watchEndpoint
            val browseEndpoint = renderer.onTap.browseEndpoint

            return when {
                watchEndpoint?.videoId != null -> {
                    SongItem(
                        id = watchEndpoint.videoId,
                        title = title,
                        artists = subtitle.artistRuns().toArtists(),
                        album = subtitle.albumRuns()?.firstOrNull()
                            ?.takeIf { it.navigationEndpoint?.browseEndpoint != null }
                            ?.let {
                                Album(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId!!,
                                )
                            },
                        duration = subtitle.lastOrNull()?.joinToString("") { it.text }?.parseTime(),
                        thumbnail = thumbnail.orEmpty(),
                        explicit = explicit,
                        endpoint = watchEndpoint,
                    )
                }

                browseEndpoint?.isArtistEndpoint == true -> {
                    ArtistItem(
                        id = browseEndpoint.browseId,
                        title = title,
                        thumbnail = thumbnail,
                        shuffleEndpoint = renderer.buttons
                            .find { it.buttonRenderer.icon?.iconType == "MUSIC_SHUFFLE" }
                            ?.buttonRenderer?.command?.watchPlaylistEndpoint,
                        radioEndpoint = renderer.buttons
                            .find { it.buttonRenderer.icon?.iconType == "MIX" }
                            ?.buttonRenderer?.command?.watchPlaylistEndpoint,
                    )
                }

                browseEndpoint?.isAlbumEndpoint == true -> {
                    val playlistId = renderer.buttons.firstNotNullOfOrNull {
                        it.buttonRenderer.command?.watchPlaylistEndpoint?.playlistId
                            ?: it.buttonRenderer.command?.anyWatchEndpoint?.playlistId
                    } ?: return null
                    AlbumItem(
                        browseId = browseEndpoint.browseId,
                        playlistId = playlistId,
                        title = title,
                        artists = subtitle.artistRuns().toArtists(),
                        year = null,
                        thumbnail = thumbnail.orEmpty(),
                        explicit = explicit,
                    )
                }

                browseEndpoint?.isPlaylistEndpoint == true -> {
                    PlaylistItem(
                        id = browseEndpoint.browseId.removePrefix("VL"),
                        title = title,
                        author = subtitle.getOrNull(1)?.joinToString("") { it.text }
                            ?.takeIf { it.isNotBlank() }
                            ?.let { Artist(name = it, id = null) }
                            ?: subtitleText.takeIf { it.isNotBlank() }
                                ?.let { Artist(name = it, id = null) },
                        songCountText = null,
                        thumbnail = thumbnail,
                        playEndpoint = renderer.buttons
                            .find { it.buttonRenderer.icon?.iconType == "PLAY_ARROW" }
                            ?.buttonRenderer?.command?.watchPlaylistEndpoint,
                        shuffleEndpoint = renderer.buttons
                            .find { it.buttonRenderer.icon?.iconType == "MUSIC_SHUFFLE" }
                            ?.buttonRenderer?.command?.watchPlaylistEndpoint,
                        radioEndpoint = null,
                    )
                }

                else -> null
            }
        }

        internal fun fromMusicResponsiveListItemRenderer(
            renderer: MusicResponsiveListItemRenderer,
        ): YTItem? {
            val columns = renderer.flexColumns
            val title = columns.firstOrNull()
                ?.musicResponsiveListItemFlexColumnRenderer?.text?.runs
                ?.firstOrNull()?.text
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val secondaryLine = columns.getOrNull(1)
                ?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.splitBySeparator()
                .orEmpty()
            val thirdLine = columns.getOrNull(2)
                ?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.splitBySeparator()
                .orEmpty()
            val metadata = secondaryLine + thirdLine
            val thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
            val explicit = renderer.badges?.find {
                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
            } != null

            return when {
                renderer.isSong -> {
                    val videoId = renderer.playlistItemData?.videoId ?: return null
                    SongItem(
                        id = videoId,
                        title = title,
                        artists = metadata.artistRuns().toArtists(),
                        album = metadata.albumRuns()?.firstOrNull()
                            ?.takeIf { it.navigationEndpoint?.browseEndpoint != null }
                            ?.let {
                                Album(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId!!,
                                )
                            },
                        duration = metadata.lastOrNull()
                            ?.joinToString("") { it.text }
                            ?.parseTime(),
                        thumbnail = thumbnail.orEmpty(),
                        explicit = explicit,
                        endpoint = renderer.navigationEndpoint?.watchEndpoint,
                        setVideoId = renderer.playlistItemData.playlistSetVideoId,
                    )
                }

                renderer.isArtist -> {
                    ArtistItem(
                        id = renderer.navigationEndpoint?.browseEndpoint?.browseId ?: return null,
                        title = title,
                        thumbnail = thumbnail,
                        shuffleEndpoint = renderer.menu?.menuRenderer?.items
                            ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE" }
                            ?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                        radioEndpoint = renderer.menu?.menuRenderer?.items
                            ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MIX" }
                            ?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                    )
                }

                renderer.isAlbum -> {
                    val playEndpoint = renderer.overlay?.musicItemThumbnailOverlayRenderer
                        ?.content?.musicPlayButtonRenderer?.playNavigationEndpoint
                    AlbumItem(
                        browseId = renderer.navigationEndpoint?.browseEndpoint?.browseId
                            ?: return null,
                        playlistId = playEndpoint?.watchPlaylistEndpoint?.playlistId
                            ?: playEndpoint?.anyWatchEndpoint?.playlistId
                            ?: return null,
                        title = title,
                        artists = metadata.artistRuns().toArtists(),
                        year = metadata.mapNotNull {
                            it.joinToString("") { run -> run.text }.toIntOrNull()
                        }.lastOrNull(),
                        thumbnail = thumbnail.orEmpty(),
                        explicit = explicit,
                    )
                }

                renderer.isPlaylist -> {
                    PlaylistItem(
                        id = renderer.navigationEndpoint?.browseEndpoint?.browseId
                            ?.removePrefix("VL")
                            ?: return null,
                        title = title,
                        author = metadata.artistRuns().joinToString("") { it.text }
                            .takeIf { it.isNotBlank() }
                            ?.let { Artist(name = it, id = null) },
                        songCountText = metadata.lastOrNull()?.joinToString("") { it.text }
                            ?.takeIf { it.isNotBlank() },
                        thumbnail = thumbnail,
                        playEndpoint = renderer.overlay?.musicItemThumbnailOverlayRenderer
                            ?.content?.musicPlayButtonRenderer?.playNavigationEndpoint
                            ?.watchPlaylistEndpoint,
                        shuffleEndpoint = renderer.menu?.menuRenderer?.items
                            ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE" }
                            ?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                        radioEndpoint = renderer.menu?.menuRenderer?.items
                            ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MIX" }
                            ?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                    )
                }

                else -> null
            }
        }
    }
}

private fun List<List<Run>>.artistRuns(): List<Run> =
    firstOrNull { segment ->
        segment.any { it.navigationEndpoint?.browseEndpoint?.isArtistEndpoint == true }
    } ?: firstOrNull { segment ->
        segment.any { it.navigationEndpoint?.browseEndpoint != null }
    } ?: getOrNull(1) ?: getOrNull(0) ?: emptyList()

private fun List<List<Run>>.albumRuns(): List<Run>? =
    firstOrNull { segment ->
        segment.any { it.navigationEndpoint?.browseEndpoint?.isAlbumEndpoint == true }
    }

private fun List<Run>.toArtists(): List<Artist> =
    oddElements().mapNotNull { run ->
        run.text.takeIf { it.isNotBlank() }?.let {
            Artist(
                name = it,
                id = run.navigationEndpoint?.browseEndpoint?.browseId,
            )
        }
    }
