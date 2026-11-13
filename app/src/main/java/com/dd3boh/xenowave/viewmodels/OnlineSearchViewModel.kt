package com.dd3boh.xenowave.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.xenowave.utils.reportException
import com.dd3boh.xenowave.utils.search.OnlineSearchSource
import com.dd3boh.xenowave.utils.search.SearchFallbackLoader
import com.dd3boh.xenowave.utils.search.SearchQueryVariants
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.models.stableKey
import com.zionhuang.innertube.pages.SearchResponseFormatException
import com.zionhuang.innertube.pages.SearchResult
import com.zionhuang.innertube.pages.SearchSummary
import com.zionhuang.innertube.pages.SearchSummaryPage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One filter's page. [generation] identifies the owner of [items]/[continuation];
 * late responses from an older generation are discarded.
 */
data class OnlineSearchPageState(
    val items: List<YTItem> = emptyList(),
    val continuation: String? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: Throwable? = null,
    val loadMoreError: Throwable? = null,
    val related: Boolean = false,
    val generation: Long = 0L,
)

@HiltViewModel
class OnlineSearchViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val searchSource: OnlineSearchSource,
) : ViewModel() {
    /**
     * Navigation already decodes path arguments before SavedStateHandle exposes
     * them. Keep this exact string for requests, history, voice and queue titles;
     * applying URLDecoder here would turn "C++" into "C  " and "%2B" into "+".
     */
    val query = savedStateHandle.get<String>("query")!!
    val filter = MutableStateFlow<YouTube.SearchFilter?>(null)

    var summaryPage by mutableStateOf<SearchSummaryPage?>(null)
        private set
    var isSummaryLoading by mutableStateOf(true)
        private set
    var summaryError by mutableStateOf<Throwable?>(null)
        private set
    var summaryRelated by mutableStateOf(false)
        private set

    val filterStates = mutableStateMapOf<String, OnlineSearchPageState>()

    private val fallbackLoader = SearchFallbackLoader()
    private val queryVariants = SearchQueryVariants.forQuery(query)
    private var summaryRequestId = 0
    private var generationCounter = 0L
    private var loadMoreJob: Job? = null

    init {
        viewModelScope.launch {
            filter.collectLatest { currentFilter ->
                loadMoreJob?.cancel()
                if (currentFilter == null) {
                    loadSummary()
                } else {
                    loadFilter(currentFilter)
                }
            }
        }
    }

    fun retry() {
        viewModelScope.launch {
            val currentFilter = filter.value
            if (currentFilter == null) {
                loadSummary(force = true)
            } else {
                loadFilter(currentFilter, force = true)
            }
        }
    }

    /** Retries the next page with the same cursor, keeping the items already shown. */
    fun retryNextPage() {
        val key = filter.value?.value ?: return
        val state = filterStates[key] ?: return
        val continuation = state.continuation ?: return
        if (state.isLoading || state.isLoadingMore) return
        loadMoreJob?.cancel()
        loadMoreJob = viewModelScope.launch {
            loadNextPage(
                key = key,
                continuation = continuation,
                generation = state.generation,
                fromRetry = true,
            )
        }
    }

    fun loadMore() {
        val key = filter.value?.value ?: return
        val state = filterStates[key] ?: return
        if (state.isLoading || state.isLoadingMore || state.loadMoreError != null) return
        val continuation = state.continuation ?: return
        if (loadMoreJob?.isActive == true) return
        loadMoreJob = viewModelScope.launch {
            loadNextPage(
                key = key,
                continuation = continuation,
                generation = state.generation,
                fromRetry = false,
            )
        }
    }

    private suspend fun loadSummary(force: Boolean = false) {
        if (!force && summaryPage != null) return
        val requestId = ++summaryRequestId
        isSummaryLoading = true
        summaryError = null

        val attempt = fallbackLoader.load(
            query = query,
            variants = queryVariants,
            request = { candidate -> searchSource.searchSummary(candidate) },
            isSparse = { it.itemCount < 2 },
        )
        if (requestId != summaryRequestId) return

        isSummaryLoading = false
        val original = attempt.original
        if (original == null) {
            if (summaryPage == null) {
                summaryError = attempt.error
                    ?: SearchResponseFormatException("summary search failed")
            }
            return
        }

        val variant = attempt.variant
        if (variant == null) {
            summaryPage = original
            summaryRelated = false
        } else {
            val merged = mergeSummaries(original, variant)
            summaryPage = merged
            summaryRelated = merged.summaries.any { it.related }
        }
    }

    private suspend fun loadFilter(
        currentFilter: YouTube.SearchFilter,
        force: Boolean = false,
    ) {
        loadMoreJob?.cancel()
        val key = currentFilter.value
        val existing = filterStates[key]
        if (!force && existing != null) {
            if (existing.isLoading || existing.isLoadingMore) return
            if (existing.loadMoreError != null && existing.continuation != null) {
                // Switching back to this chip must recover the failed next page,
                // not silently keep a cache entry that cannot advance.
                retryNextPage()
                return
            }
            if (existing.error == null) return
        }

        val generation = ++generationCounter
        val previousItems = existing?.items.orEmpty()
        val previousContinuation = existing?.continuation
        filterStates[key] = OnlineSearchPageState(
            items = previousItems,
            continuation = previousContinuation,
            isLoading = true,
            generation = generation,
        )

        val attempt = fallbackLoader.load(
            query = query,
            variants = queryVariants,
            request = { candidate -> searchSource.search(candidate, currentFilter) },
            isSparse = { it.items.size < 2 },
        )
        if (filterStates[key]?.generation != generation) return

        val original = attempt.original
        if (original == null) {
            filterStates[key] = OnlineSearchPageState(
                items = previousItems,
                continuation = previousContinuation,
                isLoading = false,
                error = if (previousItems.isEmpty()) {
                    attempt.error ?: SearchResponseFormatException("filtered search failed")
                } else {
                    null
                },
                generation = generation,
            )
            return
        }

        val variant = attempt.variant
        val page = if (variant == null) original else mergeItems(original, variant)
        val addedByVariant = variant != null &&
            page.items.size > original.items.distinctBy { it.stableKey }.size
        filterStates[key] = OnlineSearchPageState(
            items = page.items,
            continuation = page.continuation,
            related = addedByVariant,
            generation = generation,
        )
    }

    private suspend fun loadNextPage(
        key: String,
        continuation: String,
        generation: Long,
        fromRetry: Boolean,
    ) {
        val current = filterStates[key] ?: return
        if (current.generation != generation ||
            current.isLoading ||
            current.isLoadingMore ||
            (!fromRetry && current.loadMoreError != null)
        ) {
            return
        }

        filterStates[key] = current.copy(
            isLoadingMore = true,
            loadMoreError = null,
        )
        try {
            val result = searchSource.searchContinuation(continuation)
            if (filterStates[key]?.generation != generation) return
            result.onSuccess { page ->
                val state = filterStates[key] ?: return@onSuccess
                if (state.generation != generation) return@onSuccess
                filterStates[key] = state.copy(
                    items = (state.items + page.items).distinctBy { it.stableKey },
                    continuation = page.continuation,
                    isLoadingMore = false,
                    loadMoreError = null,
                )
            }.onFailure { error ->
                reportException(error)
                val state = filterStates[key] ?: return@onFailure
                if (state.generation != generation) return@onFailure
                // Keep items and the cursor: a transient failure must be recoverable.
                filterStates[key] = state.copy(
                    isLoadingMore = false,
                    loadMoreError = error,
                )
            }
        } finally {
            val state = filterStates[key]
            if (state != null && state.generation == generation && state.isLoadingMore) {
                filterStates[key] = state.copy(isLoadingMore = false)
            }
        }
    }

    private fun mergeSummaries(
        original: SearchSummaryPage,
        variant: SearchSummaryPage,
    ): SearchSummaryPage {
        val existing = original.summaries.flatMap { it.items }
            .mapTo(mutableSetOf()) { it.stableKey }
        val additions = variant.summaries.flatMap { it.items }
            .filter { it.stableKey !in existing }
            .distinctBy { it.stableKey }
        if (additions.isEmpty()) return original
        return SearchSummaryPage(
            summaries = original.summaries + SearchSummary(
                title = "",
                items = additions,
                related = true,
            ),
        )
    }

    private fun mergeItems(original: SearchResult, variant: SearchResult): SearchResult {
        val existing = original.items.mapTo(mutableSetOf()) { it.stableKey }
        val additions = variant.items.filter { it.stableKey !in existing }
        return SearchResult(
            items = (original.items + additions).distinctBy { it.stableKey },
            continuation = original.continuation ?: variant.continuation,
        )
    }
}
