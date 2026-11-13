package com.dd3boh.xenowave.viewmodels

import android.os.Looper
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import com.dd3boh.xenowave.utils.search.OnlineSearchSource
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.pages.SearchResult
import com.zionhuang.innertube.pages.SearchSummary
import com.zionhuang.innertube.pages.SearchSummaryPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class OnlineSearchViewModelTest {
    @Test
    fun `saved state query is sent to the provider without extra decoding`() {
        listOf(
            "C++",
            "A+B",
            "rock %2B pop",
            "música anos 70",
            "rock dos anos 70",
            "rock/ao vivo",
        ).forEach { value ->
            val source = FakeSearchSource()
            OnlineSearchViewModel(
                savedStateHandle = SavedStateHandle(mapOf("query" to value)),
                searchSource = source,
            )
            drainMainLooper()

            assertEquals(value, source.summaryQueries.firstOrNull())
        }
    }

    @Test
    fun `navigation route decoding reaches the request unchanged`() {
        val context = RuntimeEnvironment.getApplication()
        val navController = NavController(context)
        navController.navigatorProvider.addNavigator(ComposeNavigator())
        navController.graph =
            navController.createGraph(startDestination = "search/{query}") {
                composable("search/{query}") { }
            }
        val value = "rock %2B pop / C++"
        navController.navigate("search/${Uri.encode(value)}")
        val routeQuery = navController.currentBackStackEntry
            ?.arguments
            ?.getString("query")
        assertEquals(value, routeQuery)

        val source = FakeSearchSource()
        OnlineSearchViewModel(
            savedStateHandle = SavedStateHandle(mapOf("query" to routeQuery)),
            searchSource = source,
        )
        drainMainLooper()
        assertEquals(value, source.summaryQueries.firstOrNull())
    }

    @Test
    fun `next page failure keeps items and cursor and retry uses the same cursor`() {
        val source = FakeSearchSource().apply {
            filterResult = Result.success(SearchResult(listOf(song("one")), "cursor-1"))
            continuationResults.add(Result.failure(IOException("transient")))
            continuationResults.add(
                Result.success(SearchResult(listOf(song("one"), song("two")), "cursor-2"))
            )
        }
        val viewModel = OnlineSearchViewModel(
            savedStateHandle = SavedStateHandle(mapOf("query" to "rock")),
            searchSource = source,
        )
        drainMainLooper()
        viewModel.filter.value = YouTube.SearchFilter.FILTER_SONG
        drainMainLooper()

        val initial = viewModel.filterStates.getValue(YouTube.SearchFilter.FILTER_SONG.value)
        assertEquals(listOf("one"), initial.items.map { it.id })
        assertEquals("cursor-1", initial.continuation)

        viewModel.loadMore()
        drainMainLooper()

        val failed = viewModel.filterStates.getValue(YouTube.SearchFilter.FILTER_SONG.value)
        assertEquals(listOf("one"), failed.items.map { it.id })
        assertEquals("cursor-1", failed.continuation)
        assertTrue(failed.loadMoreError is IOException)
        assertTrue(!failed.isLoadingMore)
        assertEquals(listOf("cursor-1"), source.continuationRequests)

        viewModel.retryNextPage()
        drainMainLooper()

        val recovered = viewModel.filterStates.getValue(YouTube.SearchFilter.FILTER_SONG.value)
        assertEquals(listOf("one", "two"), recovered.items.map { it.id })
        assertEquals("cursor-2", recovered.continuation)
        assertNull(recovered.loadMoreError)
        assertEquals(listOf("cursor-1", "cursor-1"), source.continuationRequests)
    }

    @Test
    fun `late response from an older generation cannot overwrite the new filter`() {
        val late = CompletableDeferred<Result<SearchResult>>()
        val source = FakeSearchSource().apply {
            filterResult = Result.success(SearchResult(listOf(song("song")), "song-cursor"))
            continuationHandler = {
                withContext(NonCancellable) { late.await() }
            }
        }
        val viewModel = OnlineSearchViewModel(
            savedStateHandle = SavedStateHandle(mapOf("query" to "rock")),
            searchSource = source,
        )
        drainMainLooper()
        viewModel.filter.value = YouTube.SearchFilter.FILTER_SONG
        drainMainLooper()
        viewModel.loadMore()
        drainMainLooper()

        source.filterResult = Result.success(SearchResult(listOf(song("video")), "video-cursor"))
        viewModel.filter.value = YouTube.SearchFilter.FILTER_VIDEO
        drainMainLooper()

        late.complete(Result.success(SearchResult(listOf(song("stale")), "stale-cursor")))
        drainMainLooper()

        val video = viewModel.filterStates.getValue(YouTube.SearchFilter.FILTER_VIDEO.value)
        assertEquals(listOf("video"), video.items.map { it.id })
        assertEquals("video-cursor", video.continuation)
        assertTrue(video.items.none { it.id == "stale" })
    }

    @Test
    fun `switching back to a filter with a failed next page retries that cursor`() {
        val source = FakeSearchSource().apply {
            filterResult = Result.success(SearchResult(listOf(song("song")), "cursor"))
            continuationResults.add(Result.failure(IOException("transient")))
            continuationResults.add(Result.success(SearchResult(listOf(song("next")), "next-cursor")))
        }
        val viewModel = OnlineSearchViewModel(
            savedStateHandle = SavedStateHandle(mapOf("query" to "rock")),
            searchSource = source,
        )
        drainMainLooper()
        viewModel.filter.value = YouTube.SearchFilter.FILTER_SONG
        drainMainLooper()
        viewModel.loadMore()
        drainMainLooper()
        assertTrue(
            viewModel.filterStates.getValue(YouTube.SearchFilter.FILTER_SONG.value)
                .loadMoreError is IOException
        )

        source.filterResult = Result.success(SearchResult(listOf(song("video")), null))
        viewModel.filter.value = YouTube.SearchFilter.FILTER_VIDEO
        drainMainLooper()
        viewModel.filter.value = YouTube.SearchFilter.FILTER_SONG
        drainMainLooper()

        val recovered = viewModel.filterStates.getValue(YouTube.SearchFilter.FILTER_SONG.value)
        assertEquals(listOf("song", "next"), recovered.items.map { it.id })
        assertNull(recovered.loadMoreError)
    }

    private fun drainMainLooper() {
        repeat(5) { shadowOf(Looper.getMainLooper()).idle() }
    }

    private fun song(id: String) = SongItem(
        id = id,
        title = id,
        artists = emptyList(),
        thumbnail = "",
    )
}

private class FakeSearchSource : OnlineSearchSource {
    val summaryQueries = mutableListOf<String>()
    val filterRequests = mutableListOf<Pair<String, String>>()
    val continuationRequests = mutableListOf<String>()

    var summaryResult: Result<SearchSummaryPage> = Result.success(
        SearchSummaryPage(listOf(SearchSummary("", listOf())))
    )
    var filterResult: Result<SearchResult> = Result.success(SearchResult(emptyList()))
    val continuationResults = ArrayDeque<Result<SearchResult>>()
    var continuationHandler: (suspend () -> Result<SearchResult>)? = null

    override suspend fun searchSummary(query: String): Result<SearchSummaryPage> {
        summaryQueries += query
        return summaryResult
    }

    override suspend fun search(
        query: String,
        filter: YouTube.SearchFilter,
    ): Result<SearchResult> {
        filterRequests += query to filter.value
        return filterResult
    }

    override suspend fun searchContinuation(continuation: String): Result<SearchResult> {
        continuationRequests += continuation
        continuationHandler?.let { return it() }
        return continuationResults.removeFirstOrNull()
            ?: Result.failure(IllegalStateException("no continuation result"))
    }
}
