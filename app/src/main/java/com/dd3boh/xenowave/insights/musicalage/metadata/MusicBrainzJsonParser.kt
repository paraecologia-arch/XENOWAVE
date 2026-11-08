package com.dd3boh.xenowave.insights.musicalage.metadata

import com.dd3boh.xenowave.insights.musicalage.content.SongMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class MetadataParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

class MetadataResponseTooLargeException(message: String) : Exception(message)

class MetadataTransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * One catalog page plus the information needed to know whether the search was
 * exhaustive. A compatible recording inside a partial page never proves that
 * no competitor exists.
 */
data class RecordingSearchPage(
    val candidates: List<RecordingCandidate>,
    val completeness: SearchCompleteness,
    val discarded: List<String> = emptyList(),
)

/**
 * Defensive MusicBrainz JSON parser.
 *
 * Root-level malformed JSON/structure becomes [MetadataParseException]. A
 * single malformed candidate is discarded with a reason and cannot take down
 * the whole batch or the future screen. No raw JSON, title or account data is
 * included in error messages.
 */
object MusicBrainzJsonParser {
    fun parseRecordingSearch(json: String, maxCandidates: Int): List<RecordingCandidate> =
        parseRecordingSearchPage(json, maxCandidates).candidates

    fun parseRecordingSearchPage(json: String, maxCandidates: Int): RecordingSearchPage {
        val root = try {
            Json.parseToJsonElement(json) as? JsonObject
                ?: throw MetadataParseException("JSON do MusicBrainz não é um objeto")
        } catch (e: MetadataParseException) {
            throw e
        } catch (e: Exception) {
            throw MetadataParseException("JSON inválido do MusicBrainz", e)
        }

        val recordingsElement = root["recordings"]
        val recordings = when {
            recordingsElement == null -> emptyList()
            recordingsElement is JsonArray -> recordingsElement
            else -> throw MetadataParseException("campo recordings não é uma lista")
        }
        val totalCount = root.int("count")
        val offset = root.int("offset") ?: 0
        val limit = maxCandidates.coerceIn(1, 100)
        val discarded = ArrayList<String>()
        val parsed = ArrayList<RecordingCandidate>()
        for ((index, element) in recordings.withIndex()) {
            val obj = element as? JsonObject
            if (obj == null) {
                discarded += "candidato[$index] não é objeto"
                continue
            }
            try {
                val candidate = parseCandidate(obj)
                if (candidate == null) {
                    discarded += "candidato[$index] sem id/título válidos"
                } else if (!MUSICBRAINZ_ID.matches(candidate.providerId)) {
                    discarded += "candidato[$index] com id inválido"
                } else {
                    parsed += candidate
                }
            } catch (e: CandidateStructureException) {
                discarded += "candidato[$index]: ${e.message}"
            } catch (e: Exception) {
                // A malformed candidate is a metadata error, never a crash.
                discarded += "candidato[$index]: estrutura inesperada"
            }
        }
        val distinct = parsed.distinctBy { it.providerId }.take(maxCandidates.coerceAtLeast(0))
        return RecordingSearchPage(
            candidates = distinct,
            completeness = SearchCompleteness(
                totalCount = totalCount,
                offset = offset,
                returnedCount = recordings.size,
                limit = limit,
            ),
            discarded = discarded,
        )
    }

    private fun parseCandidate(obj: JsonObject): RecordingCandidate? {
        val id = obj.string("id") ?: return null
        val title = obj.string("title") ?: return null
        val disambiguation = obj.string("disambiguation")
        val artists = obj.array("artist-credit")?.mapNotNull { credit ->
            val creditObj = credit as? JsonObject ?: throw CandidateStructureException(
                "artist-credit não é objeto"
            )
            creditObj.string("name")
                ?: (creditObj["artist"] as? JsonObject)?.string("name")
        }.orEmpty()
        val releases = obj.array("releases")
            ?.map { element ->
                element as? JsonObject ?: throw CandidateStructureException(
                    "releases contém item não objeto"
                )
                parseRelease(element)
            }
            .orEmpty()

        val nonCompilationKnown = releases.mapNotNull { it.isNonCompilationKnown }
        val hasNonCompilationRelease = when {
            releases.isEmpty() -> null
            nonCompilationKnown.any { it } -> true
            nonCompilationKnown.isNotEmpty() && nonCompilationKnown.none { it } -> false
            else -> null
        }
        val releaseWithDate = releases
            .mapNotNull { release ->
                val chosenDate = release.groupDate ?: release.date ?: return@mapNotNull null
                release.copy(chosenDate = chosenDate)
            }
            .minByOrNull { it.chosenDate.orEmpty() }
        val corroborating = releases.firstOrNull { release ->
            release.isNonCompilationKnown == true && !release.date.isNullOrBlank()
        }

        val recordingFirstRelease = obj.string("first-release-date")
        val (date, dateSource) = when {
            !recordingFirstRelease.isNullOrBlank() ->
                recordingFirstRelease to CandidateDateSource.RECORDING_FIRST_RELEASE
            releaseWithDate?.groupDate != null ->
                releaseWithDate.groupDate to CandidateDateSource.RELEASE_GROUP
            releaseWithDate?.date != null ->
                releaseWithDate.date to CandidateDateSource.RELEASE
            else -> null to CandidateDateSource.NONE
        }

        return RecordingCandidate(
            providerId = id,
            title = title,
            artists = artists,
            durationMillis = obj.long("length"),
            disambiguation = disambiguation,
            firstReleaseDate = date,
            dateSource = dateSource,
            releaseId = releaseWithDate?.id,
            releaseGroupId = releaseWithDate?.groupId,
            releaseTitle = releaseWithDate?.title,
            releasePrimaryType = releaseWithDate?.primaryType,
            releaseSecondaryTypes = releaseWithDate?.secondaryTypes.orEmpty(),
            corroboratingRelease = corroborating?.let {
                CorroboratingRelease(
                    releaseId = it.id,
                    releaseGroupId = it.groupId,
                    title = it.title,
                    date = it.date!!,
                    primaryType = it.primaryType!!,
                    secondaryTypes = it.secondaryTypes,
                )
            },
            hasNonCompilationRelease = hasNonCompilationRelease,
            isrcs = obj.array("isrcs")
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty(),
            score = obj.int("score") ?: 0,
        )
    }

    private fun parseRelease(obj: JsonObject): ReleaseInfo {
        val releaseGroup = when (val group = obj["release-group"]) {
            null -> null
            is JsonObject -> group
            else -> throw CandidateStructureException("release-group não é objeto")
        }
        val secondaryTypes = releaseGroup?.array("secondary-types")
            ?.map { (it as? JsonPrimitive)?.contentOrNull ?: "" }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val primaryType = releaseGroup?.string("primary-type")
        val nonCompilationKnown = when {
            primaryType == null -> null
            primaryType.equals("Compilation", ignoreCase = true) -> false
            secondaryTypes.any { it.equals("Compilation", ignoreCase = true) } -> false
            else -> true
        }
        return ReleaseInfo(
            date = obj.string("date"),
            groupDate = releaseGroup?.string("first-release-date"),
            id = obj.string("id"),
            groupId = releaseGroup?.string("id"),
            title = obj.string("title"),
            primaryType = primaryType,
            secondaryTypes = secondaryTypes,
            isNonCompilationKnown = nonCompilationKnown,
            chosenDate = null,
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    private fun JsonObject.array(key: String): JsonArray? {
        val value: JsonElement = this[key] ?: return null
        return value as? JsonArray
            ?: throw CandidateStructureException("campo $key não é uma lista")
    }

    private class CandidateStructureException(message: String) : Exception(message)

    private data class ReleaseInfo(
        val date: String?,
        val groupDate: String?,
        val id: String?,
        val groupId: String?,
        val title: String?,
        val primaryType: String?,
        val secondaryTypes: List<String>,
        val isNonCompilationKnown: Boolean?,
        val chosenDate: String?,
    )

    private val MUSICBRAINZ_ID = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    )
}

object MusicBrainzQuery {
    private val LUCENE_SPECIAL = setOf(
        '+', '-', '&', '|', '!', '(', ')', '{', '}', '[', ']',
        '^', '"', '~', '*', '?', ':', '\\', '/',
    )

    fun searchUrl(metadata: SongMetadata, maxCandidates: Int): String {
        val parts = ArrayList<String>()
        parts += "recording:\"${escapeLucene(metadata.title)}\""
        metadata.artists.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
            parts += "artist:\"${escapeLucene(it)}\""
        }
        val query = parts.joinToString(" AND ")
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        return "https://musicbrainz.org/ws/2/recording/?query=$encoded&fmt=json" +
            "&limit=${maxCandidates.coerceIn(1, 10)}"
    }

    fun escapeLucene(value: String): String = buildString {
        for (ch in value) {
            if (ch in LUCENE_SPECIAL) append('\\')
            append(ch)
        }
    }
}
