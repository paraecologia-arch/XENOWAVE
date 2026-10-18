package com.dd3boh.xenowave.viewmodels

import com.zionhuang.innertube.pages.HomePage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestRequestGuardTest {
    @Test
    fun filteredResponseReplacesOldSectionsAndPreservesBaseChips() {
        val chip = HomePage.Chip("A", endpoint = null, deselectEndPoint = null)
        val oldSection = HomePage.Section("old", null, null, null, emptyList())
        val newSection = HomePage.Section("new", null, null, null, emptyList())
        val base = HomePage(chips = listOf(chip), sections = listOf(oldSection))
        val clearedCurrent = base.copy(sections = emptyList(), continuation = null)
        val response = HomePage(chips = null, sections = listOf(newSection), continuation = "next")

        val result = filteredHomePage(clearedCurrent, base, response)

        assertEquals(listOf(newSection), result.sections)
        assertEquals(listOf(chip), result.chips)
        assertEquals("next", result.continuation)
    }

    @Test
    fun continuationAppendsOnlyToThePageThatRequestedIt() {
        val firstSection = HomePage.Section("first", null, null, null, emptyList())
        val nextSection = HomePage.Section("next", null, null, null, emptyList())
        val selectedPage = HomePage(null, listOf(firstSection), continuation = "page-2")
        val continuation = HomePage(null, listOf(nextSection), continuation = null)

        val result = appendHomeContinuation(selectedPage, selectedPage, continuation)

        assertEquals(listOf(firstSection, nextSection), result?.sections)
        assertEquals(null, result?.continuation)
    }

    @Test
    fun continuationFromPreviousSelectionCannotModifyCurrentPage() {
        val oldPage = HomePage(null, emptyList(), continuation = "old-page-2")
        val currentPage = HomePage(null, emptyList(), continuation = "current-page-2")
        val oldContinuation = HomePage(null, emptyList(), continuation = null)

        val result = appendHomeContinuation(currentPage, oldPage, oldContinuation)

        assertEquals(null, result)
    }

    @Test
    fun latestSelectionOwnsLoadingAndOlderResultCannotPublish() {
        val tracker = ChipRequestTracker<String>()
        val first = tracker.select("A")
        val second = tracker.select("B")
        var published = ""

        assertFalse(tracker.succeeded(first, isEmpty = false) { published = "A" })
        assertEquals("", published)
        assertTrue(tracker.state.isLoading)
        assertTrue(tracker.succeeded(second, isEmpty = false) { published = "B" })
        assertEquals("B", published)
        assertFalse(tracker.state.isLoading)
        assertTrue(tracker.state.selected == "B")
    }

    @Test
    fun emptyResultClearsLoadingWithoutLosingSelection() {
        val tracker = ChipRequestTracker<String>()
        val request = tracker.select("Podcasts")

        assertTrue(tracker.succeeded(request, isEmpty = true))

        assertTrue(tracker.state.isEmpty)
        assertFalse(tracker.state.isLoading)
        assertTrue(tracker.state.selected == "Podcasts")
    }

    @Test
    fun failureClearsLoadingAndKeepsSelectionRecoverable() {
        val tracker = ChipRequestTracker<String>()
        val request = tracker.select("Foco")

        assertTrue(tracker.failed(request))

        assertTrue(tracker.state.hasError)
        assertFalse(tracker.state.isLoading)
        assertTrue(tracker.state.selected == "Foco")
    }

    @Test
    fun deselectionRestoresNeutralStateAndInvalidatesRequestInFlight() {
        val tracker = ChipRequestTracker<String>()
        val request = tracker.select("A")

        tracker.deselect()

        assertFalse(tracker.succeeded(request, isEmpty = false))
        assertTrue(tracker.state == ChipRequestState<String>())
    }
}
