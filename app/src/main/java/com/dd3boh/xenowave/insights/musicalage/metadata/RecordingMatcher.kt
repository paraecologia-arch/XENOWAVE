package com.dd3boh.xenowave.insights.musicalage.metadata

import com.dd3boh.xenowave.insights.musicalage.MusicalAgeV2
import com.dd3boh.xenowave.insights.musicalage.RecordingVersion
import com.dd3boh.xenowave.insights.musicalage.YearEvidence
import com.dd3boh.xenowave.insights.musicalage.YearEvidencePrecision
import com.dd3boh.xenowave.insights.musicalage.YearEvidenceSource
import com.dd3boh.xenowave.insights.musicalage.YearEvidenceState
import com.dd3boh.xenowave.insights.musicalage.content.SongMetadata
import java.text.Normalizer
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Conservative matcher. A search score, title similarity or the first result
 * never prove identity.
 *
 * Version identity is the full set of title qualifiers, not only the words
 * this project happens to know. Unknown qualifiers are preserved and must be
 * equal on both sides. A title without qualifiers is labelled `unqualified`,
 * never `studio`.
 */
class RecordingMatcher(
    private val durationToleranceMillis: Long = 5_000L,
) {
    fun match(
        metadata: SongMetadata,
        candidates: List<RecordingCandidate>,
        today: LocalDate,
        obtainedAt: Instant,
        search: SearchCompleteness = SearchCompleteness.unknown(
            returnedCount = candidates.size,
            limit = maxOf(1, candidates.size),
        ),
    ): RecordingMatchResult {
        val local = TitleParts.parse(metadata.title)
        if (local.base.isBlank()) {
            return missing(metadata, "título local vazio", obtainedAt)
        }
        val localArtist = metadata.artists.firstOrNull()?.let(::normalizeText).orEmpty()
        if (localArtist.isBlank()) {
            return missing(metadata, "artista local ausente; identidade não confirmada", obtainedAt)
        }
        val localDisambiguation = normalizeText(metadata.disambiguation)

        val matching = candidates.filter { candidate ->
            val candidateParts = TitleParts.parse(candidate.title)
            val titleMatches = candidateParts.base == local.base
            val versionMatches = candidateParts.versionKey == local.versionKey
            val artistMatches = normalizeText(candidate.artists.firstOrNull()) == localArtist
            val disambiguationMatches =
                normalizeText(candidate.disambiguation) == localDisambiguation
            val durationMatches = metadata.durationMillis != null &&
                candidate.durationMillis != null &&
                abs(metadata.durationMillis - candidate.durationMillis) <=
                tolerance(metadata.durationMillis)
            titleMatches && versionMatches && artistMatches &&
                disambiguationMatches && durationMatches
        }

        if (matching.isEmpty()) {
            val reason = when {
                candidates.isEmpty() -> "nenhum candidato retornado"
                candidates.any { it.title.equals(metadata.title, ignoreCase = true) } ->
                    "candidato com título parecido, mas artista/versão/desambiguação/duração não comprovam identidade"
                else -> "nenhum candidato compatível"
            }
            return missing(metadata, reason, obtainedAt)
        }

        if (matching.size > 1) {
            val years = matching.mapNotNull { parseDate(it.firstReleaseDate, today)?.year }.toSet()
            return RecordingMatchResult(
                state = RecordingMatchState.CONFLICT,
                evidence = baseEvidence(
                    metadata = metadata,
                    obtainedAt = obtainedAt,
                    state = YearEvidenceState.CONFLICT,
                    year = null,
                    precision = YearEvidencePrecision.UNKNOWN,
                    conflictingYears = years,
                ).copy(
                    candidateTitle = matching.first().title,
                    candidateArtist = matching.first().artists.firstOrNull(),
                    sourceReference = sourceReference(matching.first()),
                    searchCompleteness = search.label,
                    reason = "mais de um candidato compatível; identidade permanece ambígua",
                ),
                candidate = matching.first(),
                reasons = listOf("mais de um candidato compatível; identidade permanece ambígua"),
                matchingCandidates = matching.size,
            )
        }

        val candidate = matching.single()
        val parsed = parseDate(candidate.firstReleaseDate, today)
        val corroboration = candidate.corroboratingRelease
        val dateReason = when {
            parsed == null -> "data de lançamento ausente ou inválida"
            candidate.dateSource != CandidateDateSource.RECORDING_FIRST_RELEASE ->
                "data disponível não é explicitamente o primeiro lançamento da gravação"
            parsed.isFuture -> "data de lançamento futura em relação ao relógio injetado"
            corroboration == null ->
                "sem release rastreável, não-coletânea e com a mesma data para corroborar a gravação"
            !datesCorroborate(parsed, corroboration.date) ->
                "release de corroboração tem data diferente da data aceita da gravação"
            !search.isComplete ->
                "busca parcial (${search.label}); ausência de concorrentes não comprovada"
            else -> null
        }
        if (dateReason != null) {
            return RecordingMatchResult(
                state = RecordingMatchState.CANDIDATE,
                evidence = baseEvidence(
                    metadata = metadata,
                    obtainedAt = obtainedAt,
                    state = YearEvidenceState.CANDIDATE,
                    year = parsed?.year,
                    precision = parsed?.precision ?: YearEvidencePrecision.UNKNOWN,
                ).copy(
                    candidateTitle = candidate.title,
                    candidateArtist = candidate.artists.firstOrNull(),
                    candidateDurationMillis = candidate.durationMillis,
                    sourceReference = sourceReference(candidate),
                    dateSource = candidate.dateSource.name,
                    recordingDisambiguation = candidate.disambiguation,
                    searchCompleteness = search.label,
                    transientError = null,
                    reason = dateReason,
                ),
                candidate = candidate,
                reasons = listOf(dateReason),
                matchingCandidates = 1,
            )
        }

        return RecordingMatchResult(
            state = RecordingMatchState.ACCEPTED,
            evidence = baseEvidence(
                metadata = metadata,
                obtainedAt = obtainedAt,
                state = YearEvidenceState.ACCEPTED,
                year = parsed!!.year,
                precision = parsed.precision,
            ).copy(
                resolvedVersion = versionLabel(candidate),
                canonicalRecordingId = candidate.providerId,
                linkedRecordingId = candidate.providerId,
                releaseId = corroboration?.releaseId ?: candidate.releaseId,
                isrc = candidate.isrcs.firstOrNull(),
                candidateTitle = candidate.title,
                candidateArtist = candidate.artists.firstOrNull(),
                candidateDurationMillis = candidate.durationMillis,
                sourceReference = sourceReference(candidate),
                acceptanceRule = "mb-title-artist-disambiguation-duration-version-v2",
                dateSource = candidate.dateSource.name,
                recordingDisambiguation = candidate.disambiguation,
                searchCompleteness = search.label,
                associationProof = candidate.providerId,
            ),
            candidate = candidate,
            reasons = listOf(
                "título, artista, versão, desambiguação, duração, data de gravação e release corroborante compatíveis"
            ),
            matchingCandidates = 1,
        )
    }

    private fun missing(
        metadata: SongMetadata,
        reason: String,
        obtainedAt: Instant,
    ): RecordingMatchResult = RecordingMatchResult(
        state = RecordingMatchState.MISSING,
        evidence = baseEvidence(
            metadata = metadata,
            obtainedAt = obtainedAt,
            state = YearEvidenceState.MISSING,
            year = null,
            precision = YearEvidencePrecision.UNKNOWN,
        ).copy(reason = reason),
        candidate = null,
        reasons = listOf(reason),
        matchingCandidates = 0,
    )

    private fun baseEvidence(
        metadata: SongMetadata,
        obtainedAt: Instant,
        state: YearEvidenceState,
        year: Int?,
        precision: YearEvidencePrecision,
        conflictingYears: Set<Int> = emptySet(),
    ): YearEvidence = YearEvidence(
        recordingKey = "local:${metadata.songId}",
        year = year,
        state = state,
        origin = "MusicBrainz",
        precision = precision,
        obtainedAt = obtainedAt,
        conflictingYears = conflictingYears,
        source = YearEvidenceSource.CATALOG,
        policyVersion = MusicalAgeV2.YEAR_EVIDENCE_POLICY_VERSION,
    )

    private fun sourceReference(candidate: RecordingCandidate): String =
        "https://musicbrainz.org/recording/${candidate.providerId}"

    private fun versionLabel(candidate: RecordingCandidate): String =
        TitleParts.parse(candidate.title).versionLabel

    private fun tolerance(durationMillis: Long): Long =
        max(durationToleranceMillis, durationMillis / 50L)

    fun parseDate(value: String?, today: LocalDate): ParsedDate? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val match = PARTIAL_DATE.matchEntire(raw) ?: return null
        val year = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].takeIf { it.isNotBlank() }?.toIntOrNull()
        val day = match.groupValues[3].takeIf { it.isNotBlank() }?.toIntOrNull()
        if (month != null && month !in 1..12) return null
        if (day != null && month == null) return null
        if (year < 1850) return null
        val validMonth = month
        val validDay = day
        val calendarDay = if (validDay != null) {
            val m = validMonth ?: return null
            try {
                LocalDate.of(year, m, validDay)
            } catch (_: DateTimeException) {
                return null
            }
        } else {
            null
        }
        val precision = when {
            day != null -> YearEvidencePrecision.RECORDING_RELEASE_DAY
            month != null -> YearEvidencePrecision.RECORDING_RELEASE_MONTH
            else -> YearEvidencePrecision.YEAR_ONLY
        }
        val future = when {
            year > today.year -> true
            year < today.year -> false
            month == null -> false
            day == null -> month > today.monthValue
            else -> calendarDay!!.isAfter(today)
        }
        return ParsedDate(year, month, day, precision, future)
    }

    private fun datesCorroborate(parsed: ParsedDate, releaseDate: String): Boolean {
        val other = parseDate(releaseDate, LocalDate.of(parsed.year, 12, 31)) ?: return false
        if (parsed.year != other.year) return false
        if (parsed.month != null && other.month != null && parsed.month != other.month) return false
        if (parsed.day != null && other.day != null && parsed.day != other.day) return false
        return true
    }

    data class ParsedDate(
        val year: Int,
        val month: Int?,
        val day: Int?,
        val precision: YearEvidencePrecision,
        val isFuture: Boolean,
    )

    data class TitleParts(
        val base: String,
        val versions: Set<RecordingVersion>,
        val unknownQualifiers: Set<String>,
    ) {
        val versionKey: String
            get() = buildString {
                append(versions.map { it.label }.sorted().joinToString("+"))
                if (unknownQualifiers.isNotEmpty()) {
                    append('|')
                    append(unknownQualifiers.sorted().joinToString("+"))
                }
            }

        val versionLabel: String
            get() = when {
                versions.isEmpty() && unknownQualifiers.isEmpty() ->
                    RecordingVersion.UNQUALIFIED.label
                else -> buildList {
                    addAll(versions.map { it.label }.sorted())
                    addAll(unknownQualifiers.sorted())
                }.joinToString("+")
            }

        companion object {
            fun parse(raw: String): TitleParts {
                val qualifierParts = ArrayList<String>()
                var baseRaw = raw
                BRACKET_QUALIFIER.findAll(raw).forEach { match ->
                    qualifierParts += match.groupValues[1]
                    baseRaw = baseRaw.replace(match.value, " ")
                }
                DASH_QUALIFIER.find(baseRaw)?.let { match ->
                    qualifierParts += match.groupValues[1]
                    baseRaw = baseRaw.substring(0, match.range.first)
                }
                val versions = LinkedHashSet<RecordingVersion>()
                val unknown = LinkedHashSet<String>()
                for (part in qualifierParts) {
                    val normalizedPart = normalizeText(part)
                    if (normalizedPart.isBlank()) continue
                    // A remastered edition is the same recording; the edition
                    // year must not become a different recording identity.
                    if (REMASTER_PART.containsMatchIn(normalizedPart)) continue
                    val words = normalizedPart.split(' ').filter { it.isNotBlank() }
                    var index = 0
                    while (index < words.size) {
                        val word = words[index]
                        when {
                            word == "radio" && words.getOrNull(index + 1) == "edit" -> {
                                versions += RecordingVersion.RADIO_EDIT
                                index++
                            }
                            word == "extended" -> versions += RecordingVersion.EXTENDED
                            word == "studio" -> versions += RecordingVersion.STUDIO
                            word == "live" || word == "vivo" || word == "ao" -> {
                                versions += RecordingVersion.LIVE
                            }
                            word == "remix" || word == "remixed" -> {
                                versions += RecordingVersion.REMIX
                            }
                            word == "cover" -> versions += RecordingVersion.COVER
                            word == "acoustic" || word == "acustico" -> {
                                versions += RecordingVersion.ACOUSTIC
                            }
                            word == "instrumental" -> versions += RecordingVersion.INSTRUMENTAL
                            word == "demo" -> versions += RecordingVersion.DEMO
                            word == "karaoke" -> versions += RecordingVersion.KARAOKE
                            word == "re" && words.getOrNull(index + 1) == "recording" -> {
                                versions += RecordingVersion.RERECORDING
                                index++
                            }
                            word == "rerecording" -> versions += RecordingVersion.RERECORDING
                            else -> unknown += word
                        }
                        index++
                    }
                }
                return TitleParts(
                    base = normalizeText(baseRaw),
                    versions = versions,
                    unknownQualifiers = unknown,
                )
            }

            private val REMASTER_PART = Regex("""\bremaster(ed|izado|izada)?\b""")
            private val BRACKET_QUALIFIER = Regex("""[\(\[]([^\)\]]*)[\)\]]""")
            private val DASH_QUALIFIER = Regex("""\s[-–—]\s(.*)$""")
        }
    }

    private companion object {
        private val PARTIAL_DATE = Regex("""^(\d{4})(?:-(\d{1,2}))?(?:-(\d{1,2}))?$""")

        fun normalizeText(value: String?): String {
            if (value.isNullOrBlank()) return ""
            val decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD)
            return decomposed
                .lowercase(Locale.ROOT)
                .replace(Regex("""\p{M}+"""), "")
                .replace(Regex("""[^a-z0-9]+"""), " ")
                .trim()
                .replace(Regex("""\s+"""), " ")
        }
    }
}
