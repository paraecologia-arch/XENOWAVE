package com.dd3boh.xenowave.insights.musicalage.ui.visual

import com.dd3boh.xenowave.debug.DebugMusicalAgeFixtures
import com.dd3boh.xenowave.insights.musicalage.MusicalGeneration
import com.dd3boh.xenowave.insights.musicalage.ReadyResult
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicalAgeFixtureCoherenceTest {
    @Test
    fun `test fixtures follow the approved age formula`() {
        assertCoherent(MusicalAgeTestFixtures.readyResult(MusicalGeneration.MILLENNIAL, 44), 44)
        assertCoherent(MusicalAgeTestFixtures.readyResult(MusicalGeneration.GENERATION_Z, 20), 20)
        assertCoherent(MusicalAgeTestFixtures.readyResult(MusicalGeneration.CLASSICS, 194), 194)
    }

    @Test
    fun `debug fixtures follow the approved age formula for every visual case`() {
        assertCoherent(
            DebugMusicalAgeFixtures.ready(MusicalGeneration.GENERATION_Z, 20),
            20,
        )
        assertCoherent(
            DebugMusicalAgeFixtures.ready(MusicalGeneration.MILLENNIAL, 44),
            44,
        )
        assertCoherent(
            DebugMusicalAgeFixtures.ready(MusicalGeneration.CLASSICS, 194),
            194,
        )
        assertCoherent(
            DebugMusicalAgeFixtures.ready(MusicalGeneration.GENERATION_X, 52),
            52,
        )
    }

    private fun assertCoherent(result: ReadyResult, expectedAge: Int) {
        val expectedReference = 2026 + 18 - expectedAge
        assertEquals(expectedAge, result.musicalAge)
        assertEquals(expectedReference, result.referenceYear)
        assertEquals(expectedReference - 18, result.musicalBirthYear)
        assertEquals(expectedReference - 2, result.dominantWindow.fromYear)
        assertEquals(expectedReference + 2, result.dominantWindow.toYear)
        assertEquals(
            (expectedReference - 2..expectedReference + 2).toList(),
            result.diagnostic.acceptedYears,
        )
    }
}
