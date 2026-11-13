package com.dd3boh.xenowave.insights.musicalage

import com.dd3boh.xenowave.insights.musicalage.content.ContentEvidence
import com.dd3boh.xenowave.insights.musicalage.content.EmptyManualEvidenceStore
import com.dd3boh.xenowave.insights.musicalage.content.ManualEvidenceStore
import com.dd3boh.xenowave.insights.musicalage.content.resolveManualYear
import java.time.Instant
import java.time.LocalDateTime

/**
 * One listening event as seen by the engine. It is deliberately independent
 * from Room and from presentation code.
 *
 * [recordingKey] must identify the recording, not merely a similar title.
 * Phase 1 uses the local song id because no canonical recording id is stored
 * in the database. A canonical id can be supplied later only when proven.
 *
 * [timestamp] is the `Event.timestamp` wall-clock value: a LocalDateTime
 * without the original zone, written when playback statistics are finalized.
 * The engine compares these wall-clock values inside the injected zone; it
 * does not reconstruct the real instant, the start of a long session or
 * timezone changes.
 */
data class MusicalAgeEvent(
    val eventId: Long,
    val songId: String,
    val recordingKey: String = "local:$songId",
    val canonicalRecordingId: String? = null,
    val localYearHint: Int? = null,
    val timestamp: LocalDateTime,
    val playedMillis: Long,
    val durationMillis: Long? = null,
    val kind: ListeningKind = ListeningKind.UNKNOWN,
    val explicitlyExcluded: Boolean = false,
    val contentEvidence: ContentEvidence? = null,
)

data class RecordingIdentity(
    val recordingKey: String,
    val canonicalRecordingId: String? = null,
    val localSongId: String? = null,
    val localSongIds: List<String> = localSongId?.let { listOf(it) } ?: emptyList(),
    val localYearHint: Int? = null,
    val contentEvidence: ContentEvidence? = null,
)

/**
 * Evidence about the release year of a recording.
 *
 * Only ACCEPTED evidence with a resolved recording version and a plausible
 * year can enter the numerator. A video upload date, a compilation date or a
 * composition date is not sufficient.
 */
data class YearEvidence(
    val recordingKey: String,
    val year: Int? = null,
    val state: YearEvidenceState,
    val origin: String,
    val precision: YearEvidencePrecision = YearEvidencePrecision.UNKNOWN,
    val resolvedVersion: String? = null,
    val canonicalRecordingId: String? = null,
    val obtainedAt: Instant? = null,
    val conflictingYears: Set<Int> = emptySet(),
    val source: YearEvidenceSource = YearEvidenceSource.NONE,
    val sourceReference: String? = null,
    val acceptanceRule: String? = null,
    val linkedRecordingId: String? = null,
    val releaseId: String? = null,
    val isrc: String? = null,
    val transientError: String? = null,
    val reason: String? = null,
    val candidateTitle: String? = null,
    val candidateArtist: String? = null,
    val candidateDurationMillis: Long? = null,
    val dateSource: String? = null,
    val recordingDisambiguation: String? = null,
    val searchCompleteness: String? = null,
    val associationProof: String? = null,
    /**
     * True when this is a previously valid entry kept after a failed refresh.
     * It may still be used, but it must never be presented as freshly
     * confirmed.
     */
    val stale: Boolean = false,
    val policyVersion: String = MusicalAgeV2.YEAR_EVIDENCE_POLICY_VERSION,
)

/**
 * Injectable evidence resolver. Implementations receive the distinct
 * recordings in one batch, so a future cache/catalog resolver does not need a
 * query per event.
 */
fun interface RecordingYearResolver {
    fun resolve(recordings: List<RecordingIdentity>): Map<String, List<YearEvidence>>
}

/**
 * Phase-1 resolver with no network access.
 *
 * It accepts only evidence explicitly supplied as confirmed. A local
 * `SongEntity.year` is reported as UNVERIFIED because its semantics and
 * provenance have not been proven for the specific recording version.
 */
class LocalOnlyRecordingYearResolver(
    private val confirmedEvidence: Map<String, List<YearEvidence>> = emptyMap(),
    private val manualEvidenceStore: ManualEvidenceStore = EmptyManualEvidenceStore,
) : RecordingYearResolver {
    override fun resolve(recordings: List<RecordingIdentity>): Map<String, List<YearEvidence>> =
        recordings.associate { recording ->
            val songIds = recording.localSongIds.ifEmpty {
                listOfNotNull(recording.localSongId)
            }
            val manual = manualEvidenceStore.forSongs(songIds)
                .values
                .flatten()
            val manualResolution = resolveManualYear(manual)
            val confirmed = confirmedEvidence[recording.recordingKey]
                ?: recording.canonicalRecordingId?.let(confirmedEvidence::get)
                ?: songIds.firstNotNullOfOrNull { confirmedEvidence["local:$it"] }
            when {
                manualResolution.isConflict -> recording.recordingKey to listOf(
                    YearEvidence(
                        recordingKey = recording.recordingKey,
                        state = YearEvidenceState.CONFLICT,
                        origin = "user-confirmed",
                        source = YearEvidenceSource.USER_CONFIRMED,
                        conflictingYears = manualResolution.conflictingYears,
                        canonicalRecordingId = recording.canonicalRecordingId,
                        reason = manualResolution.reason,
                        policyVersion = MusicalAgeV2.MANUAL_EVIDENCE_POLICY_VERSION,
                    )
                )

                manualResolution.accepted != null -> recording.recordingKey to listOf(
                    YearEvidence(
                        recordingKey = recording.recordingKey,
                        year = manualResolution.accepted.year,
                        state = YearEvidenceState.ACCEPTED,
                        origin = "user-confirmed",
                        precision = manualResolution.accepted.precision,
                        resolvedVersion = manualResolution.accepted.resolvedVersion.label,
                        canonicalRecordingId = manualResolution.accepted.canonicalRecordingId
                            ?: recording.canonicalRecordingId,
                        obtainedAt = manualResolution.accepted.chosenAt,
                        source = YearEvidenceSource.USER_CONFIRMED,
                        sourceReference = "manual-evidence",
                        acceptanceRule = "user-confirmed-v2",
                        linkedRecordingId = manualResolution.accepted.recordingKey
                            ?: recording.recordingKey,
                        associationProof = "user-identified-version",
                        policyVersion = MusicalAgeV2.MANUAL_EVIDENCE_POLICY_VERSION,
                    )
                )

                manualResolution.incomplete != null -> recording.recordingKey to listOf(
                    YearEvidence(
                        recordingKey = recording.recordingKey,
                        year = manualResolution.incomplete.year,
                        state = YearEvidenceState.CANDIDATE,
                        origin = "user-confirmed-incomplete",
                        precision = manualResolution.incomplete.precision,
                        resolvedVersion = manualResolution.incomplete.resolvedVersion.label,
                        source = YearEvidenceSource.USER_CONFIRMED,
                        sourceReference = "manual-evidence",
                        acceptanceRule = "user-confirmed-v2",
                        linkedRecordingId = recording.recordingKey,
                        reason = manualResolution.reason,
                        policyVersion = MusicalAgeV2.MANUAL_EVIDENCE_POLICY_VERSION,
                    )
                )

                // Preserve the evidence identity supplied by the caller. The
                // engine validates that it really belongs to this recording
                // instead of masking a wrong link by rewriting the key.
                confirmed != null -> recording.recordingKey to confirmed

                recording.localYearHint != null -> recording.recordingKey to listOf(
                    YearEvidence(
                        recordingKey = recording.recordingKey,
                        year = recording.localYearHint,
                        state = YearEvidenceState.UNVERIFIED,
                        origin = "song.year (semântica/proveniência da gravação não comprovada)",
                        precision = YearEvidencePrecision.UNKNOWN,
                        canonicalRecordingId = recording.canonicalRecordingId,
                        source = YearEvidenceSource.LOCAL_TAG,
                        policyVersion = MusicalAgeV2.YEAR_EVIDENCE_POLICY_VERSION,
                    )
                )

                else -> recording.recordingKey to listOf(
                    YearEvidence(
                        recordingKey = recording.recordingKey,
                        state = YearEvidenceState.MISSING,
                        origin = "sem evidência local confirmada",
                        canonicalRecordingId = recording.canonicalRecordingId,
                        source = YearEvidenceSource.NONE,
                        policyVersion = MusicalAgeV2.YEAR_EVIDENCE_POLICY_VERSION,
                    )
                )
            }
        }
}

data class UnmetRequirement(
    val requirement: MusicalAgeRequirement,
    val actual: Double,
    val minimum: Double,
)

data class YearWindowSummary(
    val fromYear: Int,
    val toYear: Int,
    val weightMillis: Long,
    val share: Double,
) {
    val weightMinutes: Double
        get() = weightMillis / 60_000.0
}

/**
 * Aggregate-only diagnostics. It must never contain song titles, account names
 * or individual listening timestamps.
 */
data class MusicalAgeDiagnostic(
    val algorithmVersion: String,
    val dataOrigin: MusicalAgeDataOrigin,
    val asOf: LocalDateTime,
    val periodStart: LocalDateTime,
    val periodEnd: LocalDateTime,
    val totalEventsRead: Int,
    val eligibleEvents: Int,
    val duplicateEventsIgnored: Int,
    val futureEventsIgnored: Int,
    val beforePeriodEventsIgnored: Int,
    val explicitlyExcludedEvents: Int,
    val nonMusicEvents: Int,
    val invalidPlayTimeEvents: Int,
    val invalidDurationMetadataEvents: Int,
    val belowMinimumListenEvents: Int,
    val distinctRecordings: Int,
    val activeDays: Int,
    val weightedMillis: Long,
    val maxDailyWeightMillis: Long,
    val recordingsWithLocalYearHint: Int,
    val recordingsWithYearHintButUnverified: Int,
    val acceptedYearRecordings: Int,
    val incompleteEvidenceRecordings: Int,
    val yearCandidateRecordings: Int,
    val yearTransientErrorRecordings: Int,
    val conflictingYearRecordings: Int,
    val missingYearRecordings: Int,
    val invalidYearRecordings: Int,
    val unresolvedVersionRecordings: Int,
    val unverifiedYearRecordings: Int,
    val unconfirmedContentRecordings: Int,
    val musicContentRecordings: Int,
    val nonMusicContentRecordings: Int,
    val unknownContentRecordings: Int,
    val conflictingContentRecordings: Int,
    val manualContentCorrections: Int,
    val manualYearCorrections: Int,
    val staleEvidenceRecordings: Int = 0,
    val invalidIdentityRecordings: Int = 0,
    val acceptedYears: List<Int>,
    val coverage: Double,
    val unmetRequirements: List<UnmetRequirement>,
) {
    val discardedEvents: Int
        get() = totalEventsRead - eligibleEvents

    val weightedMinutes: Double
        get() = weightedMillis / 60_000.0
}

sealed interface MusicalAgeResult {
    val status: MusicalAgeStatus
    val diagnostic: MusicalAgeDiagnostic

    val algorithmVersion: String
        get() = diagnostic.algorithmVersion
}

data class NoHistoryResult(
    override val diagnostic: MusicalAgeDiagnostic,
) : MusicalAgeResult {
    override val status: MusicalAgeStatus = MusicalAgeStatus.NO_HISTORY
}

data class InsufficientHistoryResult(
    override val diagnostic: MusicalAgeDiagnostic,
) : MusicalAgeResult {
    override val status: MusicalAgeStatus = MusicalAgeStatus.INSUFFICIENT_HISTORY
}

data class InsufficientMetadataResult(
    override val diagnostic: MusicalAgeDiagnostic,
) : MusicalAgeResult {
    override val status: MusicalAgeStatus = MusicalAgeStatus.INSUFFICIENT_METADATA
}

/**
 * Data was sufficient, but there is no temporal predominance by the v1
 * criteria. [eras] lists non-overlapping windows in weight order; no single
 * age or generation is produced.
 */
data class MixedErasResult(
    override val diagnostic: MusicalAgeDiagnostic,
    val dominantWindow: YearWindowSummary,
    val runnerUpWindow: YearWindowSummary?,
    val eras: List<YearWindowSummary>,
) : MusicalAgeResult {
    override val status: MusicalAgeStatus = MusicalAgeStatus.MIXED_ERAS
}

data class ReadyResult(
    override val diagnostic: MusicalAgeDiagnostic,
    val musicalAge: Int,
    val generation: MusicalGeneration,
    val musicalBirthYear: Int,
    val referenceYear: Int,
    val dominantWindow: YearWindowSummary,
    val runnerUpWindow: YearWindowSummary?,
    val coverage: Double,
    val concentration: Double,
    val margin: Double,
    val eligibleRecordings: Int,
    val activeDays: Int,
    val weightedMinutes: Double,
    val periodStart: LocalDateTime,
    val periodEnd: LocalDateTime,
) : MusicalAgeResult {
    override val status: MusicalAgeStatus = MusicalAgeStatus.READY
}
