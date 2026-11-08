package com.dd3boh.xenowave.insights.musicalage.ui.visual

import com.dd3boh.xenowave.insights.musicalage.MusicalGeneration
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicalAgeVisualIdentityTest {
    @Test
    fun `every musical generation maps to the approved fictional character`() {
        val expected = mapOf(
            MusicalGeneration.ALPHA to MusicalAgeEtVariant.JOVIAL,
            MusicalGeneration.GENERATION_Z to MusicalAgeEtVariant.JOVIAL,
            MusicalGeneration.MILLENNIAL to MusicalAgeEtVariant.MATURE,
            MusicalGeneration.GENERATION_X to MusicalAgeEtVariant.MATURE,
            MusicalGeneration.BOOMER to MusicalAgeEtVariant.VETERAN,
            MusicalGeneration.SILENT to MusicalAgeEtVariant.VETERAN,
            MusicalGeneration.CLASSICS to MusicalAgeEtVariant.VETERAN,
        )

        assertEquals(MusicalGeneration.entries.toSet(), expected.keys)
        expected.forEach { (generation, variant) ->
            assertEquals(generation.name, variant, generation.etVariant())
        }
    }

    @Test
    fun `export formats keep the approved pixel dimensions`() {
        assertEquals(1080, MusicalAgeCardFormat.VERTICAL.width)
        assertEquals(1920, MusicalAgeCardFormat.VERTICAL.height)
        assertEquals(1080, MusicalAgeCardFormat.SQUARE.height)
    }
}
