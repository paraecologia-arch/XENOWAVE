package com.dd3boh.xenowave.utils.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryVariantsTest {
    @Test
    fun `clear Brazilian decade expression gets conservative variants`() {
        assertEquals(
            listOf("rock dos anos 1970", "70s rock"),
            SearchQueryVariants.forQuery("rock dos anos 70"),
        )
    }

    @Test
    fun `accented subject is preserved without unsafe reordering`() {
        assertEquals(
            listOf("música anos 1970"),
            SearchQueryVariants.forQuery("música anos 70"),
        )
    }

    @Test
    fun `full decade expression is canonicalized`() {
        assertEquals(
            listOf("anos 1970"),
            SearchQueryVariants.forQuery("década de 1970"),
        )
    }

    @Test
    fun `already canonical query is not repeated`() {
        assertTrue("rock dos anos 1970" !in SearchQueryVariants.forQuery("rock dos anos 1970"))
    }

    @Test
    fun `ambiguous numbers and lyric fragments are not rewritten`() {
        assertTrue(SearchQueryVariants.forQuery("sala 70").isEmpty())
        assertTrue(SearchQueryVariants.forQuery("anos 20").isEmpty())
        assertTrue(SearchQueryVariants.forQuery("is this the real life").isEmpty())
        assertTrue(SearchQueryVariants.forQuery("70s rock").isEmpty())
        assertTrue(SearchQueryVariants.forQuery("rock ano 1975").isEmpty())
        assertTrue(SearchQueryVariants.forQuery("rock anos 1975").isEmpty())
    }

    @Test
    fun `suffix after the decade is preserved and never dropped by reordering`() {
        assertEquals(
            listOf("rock dos anos 1970 brasileiro"),
            SearchQueryVariants.forQuery("rock dos anos 70 brasileiro"),
        )
    }

    @Test
    fun `qualifiers before the decade remain in the short variant`() {
        assertEquals(
            listOf("rock ao vivo anos 1970", "70s rock ao vivo"),
            SearchQueryVariants.forQuery("rock ao vivo anos 70"),
        )
        assertEquals(
            listOf("rock instrumental anos 1970", "70s rock instrumental"),
            SearchQueryVariants.forQuery("rock instrumental anos 70"),
        )
    }

    @Test
    fun `comparison normalization ignores case accents and extra spaces`() {
        assertEquals(
            "musica anos 70",
            SearchQueryVariants.normalize("  Música   ANOS 70  "),
        )
    }
}
