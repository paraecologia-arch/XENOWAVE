package com.dd3boh.xenowave.insights.musicalage.metadata

import com.dd3boh.xenowave.insights.musicalage.YearEvidence

enum class CandidateDateSource {
    RECORDING_FIRST_RELEASE,
    RELEASE_GROUP,
    RELEASE,
    NONE,
}

data class RecordingCandidate(
    val providerId: String,
    val title: String,
    val artists: List<String>,
    val durationMillis: Long?,
    /**
     * MusicBrainz `disambiguation` is part of the recording identity. A
     * different value means the catalog itself distinguishes the versions.
     */
    val disambiguation: String? = null,
    val firstReleaseDate: String? = null,
    val dateSource: CandidateDateSource = CandidateDateSource.NONE,
    val releaseId: String? = null,
    val releaseGroupId: String? = null,
    val releaseTitle: String? = null,
    val releasePrimaryType: String? = null,
    val releaseSecondaryTypes: List<String> = emptyList(),
    /**
     * Release whose date is equal to the chosen recording first-release date
     * AND whose release-group type is explicitly known to be non-compilation.
     * Null means the date is not corroborated by a traceable release.
     */
    val corroboratingRelease: CorroboratingRelease? = null,
    /**
     * Kept for diagnostics/backwards compatibility. It does not prove the
     * date; only [corroboratingRelease] does.
     */
    val hasNonCompilationRelease: Boolean? = null,
    val isrcs: List<String> = emptyList(),
    val score: Int = 0,
)

data class CorroboratingRelease(
    val releaseId: String?,
    val releaseGroupId: String?,
    val title: String?,
    val date: String,
    val primaryType: String,
    val secondaryTypes: List<String>,
)

/**
 * Pagination information of the catalog search. A compatible candidate in a
 * partial page is not proof that no other recording exists.
 */
data class SearchCompleteness(
    val totalCount: Int?,
    val offset: Int,
    val returnedCount: Int,
    val limit: Int,
) {
    val isComplete: Boolean
        get() = totalCount != null &&
            offset == 0 &&
            totalCount == returnedCount &&
            returnedCount <= limit

    val label: String
        get() = if (totalCount == null) {
            "completude desconhecida (sem count)"
        } else {
            "offset=$offset, retornados=$returnedCount, total=$totalCount, limite=$limit"
        }

    companion object {
        fun unknown(returnedCount: Int, limit: Int): SearchCompleteness =
            SearchCompleteness(
                totalCount = null,
                offset = 0,
                returnedCount = returnedCount,
                limit = limit,
            )
    }
}

enum class RecordingMatchState {
    ACCEPTED,
    CANDIDATE,
    CONFLICT,
    MISSING,
}

data class RecordingMatchResult(
    val state: RecordingMatchState,
    val evidence: YearEvidence,
    val candidate: RecordingCandidate?,
    val reasons: List<String>,
    val matchingCandidates: Int,
)
