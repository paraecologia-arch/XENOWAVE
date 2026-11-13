package com.dd3boh.xenowave.utils.search

import java.text.Normalizer
import java.util.Locale

/**
 * Conservative alternative queries for clear decade expressions.
 *
 * The original query is never rewritten: callers keep it as the first request and only
 * use these variants when the first response is empty or very short.
 */
object SearchQueryVariants {
    private val explicitDecade = Regex(
        """(?iu)\b(?:anos?|años|d[eé]cada\s+de)\s*(\d{2}|19\d{2}|20\d{2})\b"""
    )
    private val trailingConnector = Regex(
        """(?iu)(?:\s+(?:d[oa]s?|n[oa]s?|de|do|da|em|no|na))+\s*$"""
    )
    private val asciiSubject = Regex("""^[A-Za-z0-9'’\- ]+$""")
    private val extraSpaces = Regex("""\s+""")
    private val diacritics = Regex("""\p{M}+""")

    fun forQuery(query: String, limit: Int = 2): List<String> {
        val match = explicitDecade.find(query) ?: return emptyList()
        val raw = match.groupValues[1]
        val number = raw.toIntOrNull() ?: return emptyList()
        val fullYear = when {
            // "70" is a decade; "75" is an ambiguous/specific year and is not
            // rewritten into a fictional "75s".
            raw.length == 2 && number in 50..99 && number % 10 == 0 -> 1900 + number
            // "1970" is a decade; "1975" is a specific year.
            raw.length == 4 && number in 1900..2099 && number % 10 == 0 -> number
            else -> return emptyList()
        }

        val prefix = query.substring(0, match.range.first)
        val suffix = query.substring(match.range.last + 1)
        val variants = linkedSetOf<String>()
        variants += query.replaceRange(match.range, "anos $fullYear")

        // "rock dos anos 70" -> "70s rock". Reordering is only safe when the
        // decade is at the end and the preceding subject is short/unaccented.
        // A suffix such as "brasileiro" is preserved by the canonical variant
        // above and is never dropped by the English reordering.
        val subject = prefix
            .replace(trailingConnector, "")
            .trim()
        if (suffix.isBlank() && fullYear in 1950..1999 && asciiSubject.matches(subject)) {
            val shortDecade = fullYear % 100
            if (subject.isNotEmpty()) {
                variants += "${shortDecade}s $subject"
            }
        }

        return variants
            .asSequence()
            .map { it.replace(extraSpaces, " ").trim() }
            .filter { it.isNotEmpty() && normalize(it) != normalize(query) }
            .distinctBy { normalize(it) }
            .take(limit)
            .toList()
    }

    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(diacritics, "")
            .lowercase(Locale.ROOT)
            .replace(extraSpaces, " ")
            .trim()
}
