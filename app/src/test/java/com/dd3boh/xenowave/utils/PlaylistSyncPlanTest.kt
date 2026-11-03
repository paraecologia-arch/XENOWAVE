package com.dd3boh.xenowave.utils

import org.junit.Assert.*
import org.junit.Test

class PlaylistSyncPlanTest {
    @Test
    fun removingOneDuplicateKeepsTheOtherOccurrence() {
        val first = PlaylistSyncEntry(1, "same-song", "first")
        val second = PlaylistSyncEntry(2, "same-song", "second")
        assertEquals(setOf("first"), PlaylistSyncPlan.removals(PlaylistSyncSnapshot("name", listOf(first, second)), listOf(second)))
    }

    @Test
    fun remoteAdditionsKeepTheirSlotsAndAreNeverRemovedByTheLocalDiff() {
        assertEquals(listOf("b", "external", "a"), PlaylistSyncPlan.order(listOf("a", "external", "b"), listOf("b", "a"), true))
        assertEquals(listOf("b", "a"), PlaylistSyncPlan.order(listOf("b", "a"), listOf("a", "b"), false))
    }

    @Test
    fun everyPermutationIncludingMovesToTheLastPositionCanBeApplied() {
        val items = listOf("a", "b", "c", "d", "e")
        fun permutations(values: List<String>): List<List<String>> = if (values.isEmpty()) listOf(emptyList()) else
            values.flatMap { first -> permutations(values - first).map { listOf(first) + it } }
        for (source in permutations(items)) for (target in permutations(items)) {
            val actual = source.toMutableList()
            PlaylistSyncPlan.moves(source, target).forEach { (id, successor) ->
                actual.remove(id)
                actual.add(actual.indexOf(successor), id)
            }
            assertEquals(target, actual)
        }
        assertTrue(PlaylistSyncPlan.moves(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun lostResponseIsRecoveredByOccurrenceAndAmbiguityIsNotGuessed() {
        val old = PlaylistSyncEntry(1, "song", "old")
        val added = PlaylistSyncEntry(2, "song", "new")
        assertEquals("new", PlaylistSyncPlan.recoveredAddition(setOf("old"), listOf(old, added), "song"))
        assertNull(PlaylistSyncPlan.recoveredAddition(setOf("old"), listOf(old), "song"))
        assertNull(PlaylistSyncPlan.recoveredAddition(setOf("old"), listOf(old, added, added.copy(setVideoId = "another")), "song"))
    }

    @Test
    fun persistedSnapshotsAndInFlightRequestsRoundTripIncludingDuplicates() {
        val snapshot = PlaylistSyncSnapshot("Playlist \"☀\"", listOf(PlaylistSyncEntry(1, "song", null), PlaylistSyncEntry(2, "song", "set")))
        assertEquals(snapshot, PlaylistSyncSnapshot.decode(snapshot.encode()))
        val attempt = PlaylistAddAttempt(7, "song", setOf("a", "b"))
        assertEquals(attempt, PlaylistAddAttempt.decode(attempt.encode()))
    }
}
