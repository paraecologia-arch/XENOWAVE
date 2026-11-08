package com.dd3boh.xenowave.insights.musicalage.content

import com.dd3boh.xenowave.insights.musicalage.MusicalAgeV2
import com.dd3boh.xenowave.insights.musicalage.RecordingVersion
import com.dd3boh.xenowave.insights.musicalage.YearEvidencePrecision

/**
 * Explicit result of interpreting manual rows for one song.
 *
 * Different versions/recordings with years are a real ambiguity, not a reason
 * to silently pick the first row. A row with incomplete precision/version is
 * a candidate, never accepted.
 */
data class ManualYearResolution(
    val conflictingYears: Set<Int> = emptySet(),
    val accepted: ManualEvidence? = null,
    val incomplete: ManualEvidence? = null,
    val reason: String? = null,
) {
    val isConflict: Boolean
        get() = conflictingYears.size > 1
}

fun resolveManualYear(entries: List<ManualEvidence>): ManualYearResolution {
    val withYear = entries.filter { it.year != null }
    if (withYear.isEmpty()) return ManualYearResolution()

    val groups = withYear.groupBy { entry ->
        buildString {
            append(entry.canonicalRecordingId ?: entry.recordingKey ?: entry.songId)
            append('|').append(entry.resolvedVersion.label)
        }
    }
    val years = withYear.mapNotNull { it.year }.distinct()
    if (groups.size > 1) {
        return ManualYearResolution(
            conflictingYears = years.toSet(),
            reason = "confirmações manuais apontam para gravações/versões diferentes",
        )
    }
    if (years.size > 1) {
        return ManualYearResolution(
            conflictingYears = years.toSet(),
            reason = "confirmações manuais divergem no ano da mesma versão",
        )
    }

    val complete = withYear.filter {
        it.precision != YearEvidencePrecision.UNKNOWN &&
            it.resolvedVersion != RecordingVersion.UNKNOWN &&
            it.policyVersion == MusicalAgeV2.MANUAL_EVIDENCE_POLICY_VERSION
    }
    if (complete.size == 1) {
        return ManualYearResolution(accepted = complete.single())
    }
    if (complete.size > 1) {
        return ManualYearResolution(
            conflictingYears = years.toSet(),
            reason = "mais de uma confirmação completa para a mesma identidade",
        )
    }
    return ManualYearResolution(
        incomplete = withYear.first(),
        reason = "confirmação sem versão/precisão suficiente",
    )
}
