package com.dd3boh.xenowave.utils.search

import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.pages.SearchResult
import com.zionhuang.innertube.pages.SearchSummaryPage

/**
 * Small seam around the YouTube Music calls used by [com.dd3boh.xenowave.viewmodels.OnlineSearchViewModel].
 * Production uses [YouTubeOnlineSearchSource]; tests use a controlled fake.
 */
interface OnlineSearchSource {
    suspend fun searchSummary(query: String): Result<SearchSummaryPage>

    suspend fun search(query: String, filter: YouTube.SearchFilter): Result<SearchResult>

    suspend fun searchContinuation(continuation: String): Result<SearchResult>
}

object YouTubeOnlineSearchSource : OnlineSearchSource {
    override suspend fun searchSummary(query: String): Result<SearchSummaryPage> =
        YouTube.searchSummary(query)

    override suspend fun search(
        query: String,
        filter: YouTube.SearchFilter,
    ): Result<SearchResult> = YouTube.search(query, filter)

    override suspend fun searchContinuation(continuation: String): Result<SearchResult> =
        YouTube.searchContinuation(continuation)
}
