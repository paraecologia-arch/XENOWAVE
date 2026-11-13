package com.dd3boh.xenowave.insights.musicalage.ui.visual

import com.dd3boh.xenowave.insights.musicalage.MusicalAgeDataOrigin
import com.dd3boh.xenowave.insights.musicalage.MusicalAgeDiagnostic
import com.dd3boh.xenowave.insights.musicalage.MusicalGeneration
import com.dd3boh.xenowave.insights.musicalage.ReadyResult
import com.dd3boh.xenowave.insights.musicalage.YearWindowSummary
import java.time.LocalDateTime

object MusicalAgeTestFixtures {
    private const val CURRENT_YEAR = 2026

    fun readyResult(
        generation: MusicalGeneration = MusicalGeneration.MILLENNIAL,
        musicalAge: Int = 44,
    ): ReadyResult {
        // Keep synthetic values coherent with the approved formula:
        // age = CURRENT_YEAR - referenceYear + 18.
        val referenceYear = CURRENT_YEAR + 18 - musicalAge
        val fromYear = referenceYear - 2
        val toYear = referenceYear + 2
        val periodStart = LocalDateTime.of(2026, 8, 9, 0, 0)
        val periodEnd = LocalDateTime.of(2026, 11, 7, 12, 0)
        val diagnostic = MusicalAgeDiagnostic(
            algorithmVersion = "fixture",
            dataOrigin = MusicalAgeDataOrigin.SYNTHETIC_FIXTURE,
            asOf = periodEnd,
            periodStart = periodStart,
            periodEnd = periodEnd,
            totalEventsRead = 0,
            eligibleEvents = 0,
            duplicateEventsIgnored = 0,
            futureEventsIgnored = 0,
            beforePeriodEventsIgnored = 0,
            explicitlyExcludedEvents = 0,
            nonMusicEvents = 0,
            invalidPlayTimeEvents = 0,
            invalidDurationMetadataEvents = 0,
            belowMinimumListenEvents = 0,
            distinctRecordings = 0,
            activeDays = 0,
            weightedMillis = 0,
            maxDailyWeightMillis = 0,
            recordingsWithLocalYearHint = 0,
            recordingsWithYearHintButUnverified = 0,
            acceptedYearRecordings = 0,
            incompleteEvidenceRecordings = 0,
            yearCandidateRecordings = 0,
            yearTransientErrorRecordings = 0,
            conflictingYearRecordings = 0,
            missingYearRecordings = 0,
            invalidYearRecordings = 0,
            unresolvedVersionRecordings = 0,
            unverifiedYearRecordings = 0,
            unconfirmedContentRecordings = 0,
            musicContentRecordings = 0,
            nonMusicContentRecordings = 0,
            unknownContentRecordings = 0,
            conflictingContentRecordings = 0,
            manualContentCorrections = 0,
            manualYearCorrections = 0,
            acceptedYears = (fromYear..toYear).toList(),
            coverage = 0.8,
            unmetRequirements = emptyList(),
        )
        return ReadyResult(
            diagnostic = diagnostic,
            musicalAge = musicalAge,
            generation = generation,
            musicalBirthYear = referenceYear - 18,
            referenceYear = referenceYear,
            dominantWindow = YearWindowSummary(
                fromYear = fromYear,
                toYear = toYear,
                weightMillis = 7_200_000,
                share = 0.6,
            ),
            runnerUpWindow = null,
            coverage = 0.8,
            concentration = 0.6,
            margin = 0.2,
            eligibleRecordings = 20,
            activeDays = 3,
            weightedMinutes = 120.0,
            periodStart = periodStart,
            periodEnd = periodEnd,
        )
    }
}
