/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.debug

import com.dd3boh.xenowave.insights.musicalage.InsufficientHistoryResult
import com.dd3boh.xenowave.insights.musicalage.MixedErasResult
import com.dd3boh.xenowave.insights.musicalage.MusicalAgeDataOrigin
import com.dd3boh.xenowave.insights.musicalage.MusicalAgeDiagnostic
import com.dd3boh.xenowave.insights.musicalage.MusicalGeneration
import com.dd3boh.xenowave.insights.musicalage.NoHistoryResult
import com.dd3boh.xenowave.insights.musicalage.ReadyResult
import com.dd3boh.xenowave.insights.musicalage.YearWindowSummary
import java.time.LocalDateTime

/**
 * Synthetic, debug-only fixtures. They never enter a release build and are
 * visibly marked by the preview host, not by a production "fake mode".
 */
object DebugMusicalAgeFixtures {
    private const val CURRENT_YEAR = 2026
    private val periodStart = LocalDateTime.of(2026, 8, 9, 0, 0)
    private val periodEnd = LocalDateTime.of(2026, 11, 7, 12, 0)

    private fun diagnostic(acceptedYears: List<Int>): MusicalAgeDiagnostic = MusicalAgeDiagnostic(
        algorithmVersion = "debug-fixture",
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
        acceptedYears = acceptedYears,
        coverage = 0.8,
        unmetRequirements = emptyList(),
    )

    fun ready(
        generation: MusicalGeneration = MusicalGeneration.MILLENNIAL,
        age: Int = 44,
    ): ReadyResult {
        // age = CURRENT_YEAR - referenceYear + 18
        val referenceYear = CURRENT_YEAR + 18 - age
        val fromYear = referenceYear - 2
        val toYear = referenceYear + 2
        return ReadyResult(
            diagnostic = diagnostic((fromYear..toYear).toList()),
            musicalAge = age,
            generation = generation,
            musicalBirthYear = referenceYear - 18,
            referenceYear = referenceYear,
            dominantWindow = YearWindowSummary(fromYear, toYear, 7_200_000, 0.6),
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

    fun mixed(): MixedErasResult = MixedErasResult(
        diagnostic = diagnostic(listOf(2000, 2010, 2020)),
        dominantWindow = YearWindowSummary(1998, 2002, 4_000_000, 0.4),
        runnerUpWindow = YearWindowSummary(2008, 2012, 3_400_000, 0.34),
        eras = listOf(
            YearWindowSummary(1998, 2002, 4_000_000, 0.4),
            YearWindowSummary(2008, 2012, 3_400_000, 0.34),
            YearWindowSummary(2018, 2022, 2_600_000, 0.26),
        ),
    )

    fun insufficient(): InsufficientHistoryResult =
        InsufficientHistoryResult(diagnostic(emptyList()))

    fun noHistory(): NoHistoryResult = NoHistoryResult(diagnostic(emptyList()))
}
