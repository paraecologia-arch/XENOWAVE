package com.zionhuang.innertube.models

import kotlinx.serialization.Serializable

/**
 * Search results are sometimes returned as a flat list of item sections instead of
 * [MusicShelfRenderer]. The items still use [MusicResponsiveListItemRenderer].
 */
@Serializable
data class ItemSectionRenderer(
    val contents: List<Content>?,
) {
    @Serializable
    data class Content(
        val musicResponsiveListItemRenderer: MusicResponsiveListItemRenderer? = null,
    )
}
