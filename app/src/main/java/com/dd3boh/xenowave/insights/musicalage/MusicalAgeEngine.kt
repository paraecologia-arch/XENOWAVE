package com.dd3boh.xenowave.insights.musicalage

import java.math.BigInteger
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs

/**
 * Deterministic musical-age engine. Pure Kotlin: no Android, Room, network or
 * UI dependency.
 *
 * Window choice, weights, caps, medians and every gate come from
 * `musical-age-v1-proposta` as described in the phase-1 specification.
 */
class MusicalAgeEngine(
    val policy: MusicalAgePolicy = MusicalAgePolicy(),
    private val clock: MusicalAgeClock = MusicalAgeClock(),
) {
    fun now(): LocalDateTime = clock.now()

    fun periodStart(asOf: LocalDateTime): LocalDateTime = policy.periodStart(asOf)

    fun calculate(
        events: List<MusicalAgeEvent>,
        resolver: RecordingYearResolver = LocalOnlyRecordingYearResolver(),
        dataOrigin: MusicalAgeDataOrigin = MusicalAgeDataOrigin.UNSPECIFIED,
    ): MusicalAgeResult = calculateAt(events, now(), resolver, dataOrigin)

    fun calculateAt(
        events: List<MusicalAgeEvent>,
        asOf: LocalDateTime,
        resolver: RecordingYearResolver = LocalOnlyRecordingYearResolver(),
        dataOrigin: MusicalAgeDataOrigin = MusicalAgeDataOrigin.UNSPECIFIED,
    ): MusicalAgeResult {
        val periodStart = policy.periodStart(asOf)
        val inclusion = includeEvents(events, periodStart, asOf)
        val daily = aggregateByRecordingAndDay(inclusion.included)
        val recordingWeights = HashMap<String, Long>()
        val activeDays = HashSet<LocalDate>()
        var maxDailyWeightMillis = 0L

        for ((key, row) in daily) {
            val weight = minOf(row.rawWeight, row.cap, policy.maxDailyWeightMillis)
            recordingWeights[key.recordingKey] =
                saturatingAdd(recordingWeights[key.recordingKey] ?: 0L, weight)
            activeDays.add(key.day)
            if (weight > maxDailyWeightMillis) maxDailyWeightMillis = weight
        }

        val totalWeight = recordingWeights.values.fold(0L, ::saturatingAdd)
        val eventsByRecording = inclusion.included.groupBy { effectiveKey(it.event) }
        val recordings = eventsByRecording.map { (key, recordingEvents) ->
            val first = recordingEvents.minWith(
                compareBy<IncludedEvent>(
                    { it.event.songId },
                    { it.event.timestamp },
                    { it.event.eventId },
                )
            ).event
            RecordingIdentity(
                recordingKey = key,
                canonicalRecordingId = recordingEvents
                    .firstNotNullOfOrNull { it.event.canonicalRecordingId },
                localSongId = first.songId,
                localSongIds = recordingEvents.map { it.event.songId }.distinct(),
                localYearHint = first.localYearHint,
                contentEvidence = first.contentEvidence,
            )
        }
        val evidence = resolver.resolve(recordings)
        val contentCounts = contentCounts(inclusion.contentKindsByRecording)

        var acceptedRecordings = 0
        var incompleteEvidenceRecordings = 0
        var candidateRecordings = 0
        var transientErrorRecordings = 0
        var conflictingRecordings = 0
        var missingRecordings = 0
        var invalidYearRecordings = 0
        var unresolvedVersionRecordings = 0
        var unverifiedRecordings = 0
        var unconfirmedContentRecordings = 0
        var invalidIdentityRecordings = 0
        var staleEvidenceRecordings = 0
        var yearHintButUnverified = 0
        var datedWeight = 0L
        val byYear = HashMap<Int, Long>()

        for (recording in recordings) {
            val recordingEvents = eventsByRecording.getValue(recording.recordingKey)
            val contentStatus = contentStatus(
                inclusion.contentKindsByRecording[recording.recordingKey].orEmpty()
            )
            val status = classifyRecording(
                evidence[recording.recordingKey].orEmpty(),
                contentStatus,
                asOf.year,
                recording,
            )
            if (evidence[recording.recordingKey].orEmpty().any { it.stale }) {
                staleEvidenceRecordings++
            }
            if (recording.localYearHint != null && status != RecordingYearStatus.ACCEPTED) {
                yearHintButUnverified++
            }
            when (status) {
                RecordingYearStatus.ACCEPTED -> {
                    val year = evidence.getValue(recording.recordingKey)
                        .filter { it.state == YearEvidenceState.ACCEPTED }
                        .mapNotNull { it.year }
                        .distinct()
                        .single()
                    acceptedRecordings++
                    val weight = recordingWeights.getValue(recording.recordingKey)
                    byYear[year] = saturatingAdd(byYear[year] ?: 0L, weight)
                    datedWeight = saturatingAdd(datedWeight, weight)
                }

                RecordingYearStatus.CONFLICT -> conflictingRecordings++
                RecordingYearStatus.INCOMPLETE_EVIDENCE -> incompleteEvidenceRecordings++
                RecordingYearStatus.MISSING -> missingRecordings++
                RecordingYearStatus.INVALID_YEAR -> invalidYearRecordings++
                RecordingYearStatus.UNRESOLVED_VERSION -> unresolvedVersionRecordings++
                RecordingYearStatus.UNVERIFIED -> unverifiedRecordings++
                RecordingYearStatus.UNCONFIRMED_CONTENT -> unconfirmedContentRecordings++
                RecordingYearStatus.CONTENT_CONFLICT -> Unit
                RecordingYearStatus.CANDIDATE -> candidateRecordings++
                RecordingYearStatus.TRANSIENT_ERROR -> transientErrorRecordings++
                RecordingYearStatus.INVALID_IDENTITY -> invalidIdentityRecordings++
            }
        }

        val manualContentCorrections = recordingEventsWithManualContent(eventsByRecording)
        val manualYearCorrections = recordings.count { recording ->
            evidence[recording.recordingKey].orEmpty().any {
                it.source == YearEvidenceSource.USER_CONFIRMED &&
                    it.state == YearEvidenceState.ACCEPTED
            }
        }

        val coverage = if (totalWeight > 0L) datedWeight.toDouble() / totalWeight else 0.0
        val historyGaps = historyGaps(
            recordings = recordingWeights.size,
            days = activeDays.size,
            totalWeight = totalWeight,
        )
        val baseDiagnostic = MusicalAgeDiagnostic(
            algorithmVersion = policy.algorithmVersion,
            dataOrigin = dataOrigin,
            asOf = asOf,
            periodStart = periodStart,
            periodEnd = asOf,
            totalEventsRead = events.size,
            eligibleEvents = inclusion.included.size,
            duplicateEventsIgnored = inclusion.duplicates,
            futureEventsIgnored = inclusion.future,
            beforePeriodEventsIgnored = inclusion.beforePeriod,
            explicitlyExcludedEvents = inclusion.explicitlyExcluded,
            nonMusicEvents = inclusion.nonMusic,
            invalidPlayTimeEvents = inclusion.invalidPlayTime,
            invalidDurationMetadataEvents = inclusion.invalidDurationMetadata,
            belowMinimumListenEvents = inclusion.belowMinimumListen,
            distinctRecordings = recordingWeights.size,
            activeDays = activeDays.size,
            weightedMillis = totalWeight,
            maxDailyWeightMillis = maxDailyWeightMillis,
            recordingsWithLocalYearHint = recordings.count { it.localYearHint != null },
            recordingsWithYearHintButUnverified = yearHintButUnverified,
            acceptedYearRecordings = acceptedRecordings,
            incompleteEvidenceRecordings = incompleteEvidenceRecordings,
            yearCandidateRecordings = candidateRecordings,
            yearTransientErrorRecordings = transientErrorRecordings,
            conflictingYearRecordings = conflictingRecordings,
            missingYearRecordings = missingRecordings,
            invalidYearRecordings = invalidYearRecordings,
            unresolvedVersionRecordings = unresolvedVersionRecordings,
            unverifiedYearRecordings = unverifiedRecordings,
            unconfirmedContentRecordings = unconfirmedContentRecordings,
            musicContentRecordings = contentCounts.music,
            nonMusicContentRecordings = contentCounts.nonMusic,
            unknownContentRecordings = contentCounts.unknown,
            conflictingContentRecordings = contentCounts.conflicting,
            manualContentCorrections = manualContentCorrections,
            manualYearCorrections = manualYearCorrections,
            staleEvidenceRecordings = staleEvidenceRecordings,
            invalidIdentityRecordings = invalidIdentityRecordings,
            acceptedYears = byYear.keys.sorted(),
            coverage = coverage,
            unmetRequirements = historyGaps,
        )

        if (recordingWeights.isEmpty()) {
            return NoHistoryResult(baseDiagnostic)
        }
        if (historyGaps.isNotEmpty()) {
            return InsufficientHistoryResult(baseDiagnostic)
        }

        val metadataGaps = metadataGaps(
            acceptedRecordings = acceptedRecordings,
            datedWeight = datedWeight,
            totalWeight = totalWeight,
            coverage = coverage,
        )
        val metadataDiagnostic = baseDiagnostic.copy(unmetRequirements = metadataGaps)
        if (metadataGaps.isNotEmpty()) {
            return InsufficientMetadataResult(metadataDiagnostic)
        }

        val selection = selectWindows(byYear, datedWeight, asOf.year)
        val concentration = selection.concentration
        val margin = selection.margin
        val windowGaps = buildList {
            if (!atLeastPercent(
                    part = selection.winner.weight,
                    whole = datedWeight,
                    percent = policy.minWindowConcentrationPercent,
                )
            ) {
                add(
                    UnmetRequirement(
                        requirement = MusicalAgeRequirement.WINDOW_CONCENTRATION,
                        actual = concentration * 100.0,
                        minimum = policy.minWindowConcentrationPercent.toDouble(),
                    )
                )
            }
            if (!atLeastPercent(
                    part = (selection.winner.weight - selection.runnerWeight)
                        .coerceAtLeast(0L),
                    whole = datedWeight,
                    percent = policy.minNonOverlappingMarginPercent,
                )
            ) {
                add(
                    UnmetRequirement(
                        requirement = MusicalAgeRequirement.NON_OVERLAPPING_MARGIN,
                        actual = margin * 100.0,
                        minimum = policy.minNonOverlappingMarginPercent.toDouble(),
                    )
                )
            }
        }
        val windowDiagnostic = baseDiagnostic.copy(unmetRequirements = windowGaps)
        if (windowGaps.isNotEmpty()) {
            return MixedErasResult(
                diagnostic = windowDiagnostic,
                dominantWindow = selection.winner.toSummary(datedWeight),
                runnerUpWindow = selection.runner?.toSummary(datedWeight),
                eras = selection.topEras,
            )
        }

        val winnerYears = byYear.filterKeys {
            it in selection.winner.fromYear..selection.winner.toYear
        }
        val referenceYear = weightedMedian(winnerYears)
            ?: error("A ready result must have accepted years inside the winning window")
        val musicalBirthYear = referenceYear - policy.musicalBirthOffsetYears

        return ReadyResult(
            diagnostic = baseDiagnostic,
            musicalAge = asOf.year - musicalBirthYear,
            generation = MusicalGeneration.forBirthYear(musicalBirthYear),
            musicalBirthYear = musicalBirthYear,
            referenceYear = referenceYear,
            dominantWindow = selection.winner.toSummary(datedWeight),
            runnerUpWindow = selection.runner?.toSummary(datedWeight),
            coverage = coverage,
            concentration = concentration,
            margin = margin,
            eligibleRecordings = recordingWeights.size,
            activeDays = activeDays.size,
            weightedMinutes = totalWeight / 60_000.0,
            periodStart = periodStart,
            periodEnd = asOf,
        )
    }

    private fun includeEvents(
        events: List<MusicalAgeEvent>,
        periodStart: LocalDateTime,
        asOf: LocalDateTime,
    ): Inclusion {
        val ordered = events.sortedWith(
            compareBy<MusicalAgeEvent>(
                { it.eventId },
                { it.timestamp },
                { it.songId },
                { it.recordingKey },
                { it.playedMillis },
                { it.durationMillis ?: -1L },
                { it.kind.ordinal },
                { it.explicitlyExcluded },
            )
        )
        val seenIds = HashSet<Long>()
        var duplicates = 0
        var future = 0
        var beforePeriod = 0
        var explicitlyExcluded = 0
        var nonMusic = 0
        var invalidPlayTime = 0
        var invalidDurationMetadata = 0
        var belowMinimumListen = 0
        val included = ArrayList<IncludedEvent>()
        val contentKindsByRecording = HashMap<String, MutableSet<ListeningKind>>()

        for (event in ordered) {
            if (!seenIds.add(event.eventId)) {
                duplicates++
                continue
            }
            if (event.timestamp.isAfter(asOf)) {
                future++
                continue
            }
            if (event.timestamp.isBefore(periodStart)) {
                beforePeriod++
                continue
            }
            if (event.explicitlyExcluded) {
                explicitlyExcluded++
                continue
            }
            contentKindsByRecording
                .getOrPut(effectiveKey(event)) { mutableSetOf() }
                .add(event.kind)
            if (event.kind == ListeningKind.NON_MUSIC) {
                nonMusic++
                continue
            }
            if (event.playedMillis <= 0L) {
                invalidPlayTime++
                continue
            }

            val duration = event.durationMillis
            val knownDuration = duration != null && duration > 0L
            if (duration != null && duration <= 0L) {
                invalidDurationMetadata++
            }
            val effectiveDuration = if (knownDuration) {
                minOf(duration, policy.maxEventWeightMillis)
            } else {
                policy.maxEventWeightMillis
            }
            val minimumListen = if (knownDuration) {
                minOf(
                    policy.maxListenMillisKnownThreshold,
                    maxOf(policy.minListenMillisKnownDuration, duration / 2L),
                )
            } else {
                policy.minListenMillisUnknownDuration
            }
            if (event.playedMillis < minimumListen) {
                belowMinimumListen++
                continue
            }

            included.add(
                IncludedEvent(
                    event = event,
                    effectiveDurationMillis = effectiveDuration,
                    weightMillis = minOf(event.playedMillis, effectiveDuration),
                )
            )
        }

        return Inclusion(
            included = included,
            duplicates = duplicates,
            future = future,
            beforePeriod = beforePeriod,
            explicitlyExcluded = explicitlyExcluded,
            nonMusic = nonMusic,
            invalidPlayTime = invalidPlayTime,
            invalidDurationMetadata = invalidDurationMetadata,
            belowMinimumListen = belowMinimumListen,
            contentKindsByRecording = contentKindsByRecording,
        )
    }

    private fun aggregateByRecordingAndDay(
        events: List<IncludedEvent>,
    ): Map<DailyKey, DailyAggregate> {
        val daily = HashMap<DailyKey, DailyAggregate>()
        for (included in events) {
            val key = DailyKey(
                recordingKey = effectiveKey(included.event),
                day = included.event.timestamp.toLocalDate(),
            )
            val row = daily.getOrPut(key) {
                DailyAggregate(rawWeight = 0L, cap = policy.maxDailyWeightMillis)
            }
            row.rawWeight = saturatingAdd(row.rawWeight, included.weightMillis)
            val eventCap = saturatingMultiply(
                included.effectiveDurationMillis,
                policy.dailyCapMultiplier.toLong(),
            )
            row.cap = minOf(row.cap, eventCap, policy.maxDailyWeightMillis)
        }
        return daily
    }

    /**
     * Proven canonical ids group local song ids before daily caps are
     * applied. Without proof the local key remains separate.
     */
    private fun effectiveKey(event: MusicalAgeEvent): String =
        event.canonicalRecordingId
            ?.takeIf { it.isNotBlank() }
            ?.let { "canonical:$it" }
            ?: event.recordingKey

    private fun historyGaps(
        recordings: Int,
        days: Int,
        totalWeight: Long,
    ): List<UnmetRequirement> = buildList {
        if (recordings < policy.minEligibleRecordings) {
            add(
                UnmetRequirement(
                    MusicalAgeRequirement.ELIGIBLE_RECORDINGS,
                    actual = recordings.toDouble(),
                    minimum = policy.minEligibleRecordings.toDouble(),
                )
            )
        }
        if (days < policy.minActiveDays) {
            add(
                UnmetRequirement(
                    MusicalAgeRequirement.ACTIVE_DAYS,
                    actual = days.toDouble(),
                    minimum = policy.minActiveDays.toDouble(),
                )
            )
        }
        if (totalWeight < policy.minWeightedMillis) {
            add(
                UnmetRequirement(
                    MusicalAgeRequirement.WEIGHTED_MINUTES,
                    actual = totalWeight / 60_000.0,
                    minimum = policy.minWeightedMillis / 60_000.0,
                )
            )
        }
    }

    private fun metadataGaps(
        acceptedRecordings: Int,
        datedWeight: Long,
        totalWeight: Long,
        coverage: Double,
    ): List<UnmetRequirement> = buildList {
        if (acceptedRecordings < policy.minDatedRecordings) {
            add(
                UnmetRequirement(
                    MusicalAgeRequirement.DATED_RECORDINGS,
                    actual = acceptedRecordings.toDouble(),
                    minimum = policy.minDatedRecordings.toDouble(),
                )
            )
        }
        if (!atLeastPercent(
                part = datedWeight,
                whole = totalWeight,
                percent = policy.minCoveragePercent,
            )
        ) {
            add(
                UnmetRequirement(
                    MusicalAgeRequirement.COVERAGE,
                    actual = coverage * 100.0,
                    minimum = policy.minCoveragePercent.toDouble(),
                )
            )
        }
    }

    private fun classifyRecording(
        evidence: List<YearEvidence>,
        contentStatus: ContentStatus,
        currentYear: Int,
        recording: RecordingIdentity,
    ): RecordingYearStatus {
        if (evidence.any { !identityCoherent(recording, it) }) {
            return RecordingYearStatus.INVALID_IDENTITY
        }
        val accepted = evidence.filter { it.state == YearEvidenceState.ACCEPTED }
        val acceptedYears = accepted.mapNotNull { it.year }.distinct()
        val validAcceptedYears = acceptedYears.filter {
            it in policy.minReleaseYear..currentYear
        }
        val invalidAccepted = accepted.any {
            it.year == null || it.year !in policy.minReleaseYear..currentYear
        }
        val acceptedCanonicalIds = accepted
            .mapNotNull { it.canonicalRecordingId?.takeIf(String::isNotBlank) }
            .distinct()
        val acceptedLinkedIds = accepted
            .mapNotNull {
                it.linkedRecordingId?.takeIf { id -> MUSICBRAINZ_ID.matches(id) }
            }
            .distinct()
        val acceptedVersions = accepted
            .mapNotNull { it.resolvedVersion?.takeIf(String::isNotBlank) }
            .distinct()
        val conflict = evidence.any { it.state == YearEvidenceState.CONFLICT } ||
            validAcceptedYears.size > 1 ||
            (invalidAccepted && validAcceptedYears.isNotEmpty()) ||
            acceptedCanonicalIds.size > 1 ||
            acceptedLinkedIds.size > 1 ||
            acceptedVersions.size > 1

        return when {
            conflict -> RecordingYearStatus.CONFLICT
            invalidAccepted -> RecordingYearStatus.INVALID_YEAR
            accepted.isEmpty() -> {
                when {
                    evidence.any { it.state == YearEvidenceState.TRANSIENT_ERROR } ->
                        RecordingYearStatus.TRANSIENT_ERROR
                    evidence.any { it.state == YearEvidenceState.CANDIDATE } ->
                        RecordingYearStatus.CANDIDATE
                    evidence.any { it.state == YearEvidenceState.UNVERIFIED } ->
                        RecordingYearStatus.UNVERIFIED
                    else -> RecordingYearStatus.MISSING
                }
            }

            accepted.any { !isCurrentPolicy(it) } ->
                RecordingYearStatus.INCOMPLETE_EVIDENCE

            accepted.any {
                it.resolvedVersion.isNullOrBlank() ||
                    it.resolvedVersion.equals(RecordingVersion.UNKNOWN.label, ignoreCase = true)
            } ->
                RecordingYearStatus.UNRESOLVED_VERSION

            accepted.any {
                it.source == YearEvidenceSource.NONE ||
                    it.acceptanceRule.isNullOrBlank() ||
                    it.origin.isBlank()
            } -> RecordingYearStatus.INCOMPLETE_EVIDENCE

            accepted.any { it.precision == YearEvidencePrecision.UNKNOWN } ->
                RecordingYearStatus.CANDIDATE

            contentStatus == ContentStatus.CONFLICT ->
                RecordingYearStatus.CONTENT_CONFLICT

            contentStatus != ContentStatus.MUSIC ->
                RecordingYearStatus.UNCONFIRMED_CONTENT

            else -> RecordingYearStatus.ACCEPTED
        }
    }

    /**
     * The resolver may key evidence by a local song id while the engine groups
     * by canonical id. That is allowed only when the evidence really refers to
     * one of the group's songs and does not name a different recording.
     */
    private fun identityCoherent(
        recording: RecordingIdentity,
        evidence: YearEvidence,
    ): Boolean {
        val key = evidence.recordingKey
        val keyMatches = when {
            key == recording.recordingKey -> true
            key.startsWith("local:") ->
                key.removePrefix("local:") in recording.localSongIds
            key.startsWith("canonical:") ->
                recording.canonicalRecordingId != null &&
                    key == "canonical:${recording.canonicalRecordingId}"
            else -> false
        }
        val canonical = evidence.canonicalRecordingId
        val canonicalMatches = recording.canonicalRecordingId == null ||
            canonical == null ||
            canonical == recording.canonicalRecordingId
        val linked = evidence.linkedRecordingId
        val linkedMatches = linked == null ||
            linked == recording.recordingKey ||
            linked in recording.localSongIds ||
            (recording.canonicalRecordingId != null &&
                linked == recording.canonicalRecordingId) ||
            (recording.canonicalRecordingId == null &&
                linked == canonical) ||
            !MUSICBRAINZ_ID.matches(linked)
        return keyMatches && canonicalMatches && linkedMatches
    }

    private fun isCurrentPolicy(evidence: YearEvidence): Boolean =
        evidence.policyVersion == MusicalAgeV2.YEAR_EVIDENCE_POLICY_VERSION ||
            evidence.policyVersion == MusicalAgeV2.MANUAL_EVIDENCE_POLICY_VERSION

    private fun contentStatus(kinds: Set<ListeningKind>): ContentStatus = when {
        kinds.isEmpty() -> ContentStatus.UNKNOWN
        kinds == setOf(ListeningKind.MUSIC) -> ContentStatus.MUSIC
        kinds == setOf(ListeningKind.NON_MUSIC) -> ContentStatus.NON_MUSIC
        kinds == setOf(ListeningKind.UNKNOWN) -> ContentStatus.UNKNOWN
        else -> ContentStatus.CONFLICT
    }

    private fun contentCounts(
        contentKindsByRecording: Map<String, Set<ListeningKind>>,
    ): ContentCounts {
        var music = 0
        var nonMusic = 0
        var unknown = 0
        var conflicting = 0
        for (kinds in contentKindsByRecording.values) {
            when (contentStatus(kinds)) {
                ContentStatus.MUSIC -> music++
                ContentStatus.NON_MUSIC -> nonMusic++
                ContentStatus.UNKNOWN -> unknown++
                ContentStatus.CONFLICT -> conflicting++
            }
        }
        return ContentCounts(music, nonMusic, unknown, conflicting)
    }

    private fun recordingEventsWithManualContent(
        eventsByRecording: Map<String, List<IncludedEvent>>,
    ): Int = eventsByRecording.count { (_, events) ->
        events.any {
            it.event.contentEvidence?.source == ContentEvidenceSource.USER_CONFIRMED
        }
    }

    private fun selectWindows(
        byYear: Map<Int, Long>,
        datedWeight: Long,
        currentYear: Int,
    ): WindowSelection {
        val firstStart = policy.minReleaseYear
        val lastStart = policy.lastWindowStartYear(currentYear)
        val median = weightedMedian(byYear) ?: currentYear
        val candidates = if (lastStart < firstStart) {
            emptyList()
        } else {
            (firstStart..lastStart).mapNotNull { fromYear ->
                val toYear = fromYear + policy.windowYears - 1
                val weight = byYear.entries
                    .asSequence()
                    .filter { it.key in fromYear..toYear }
                    .fold(0L) { acc, entry -> saturatingAdd(acc, entry.value) }
                if (weight > 0L) WindowCandidate(fromYear, toYear, weight) else null
            }
        }
        if (candidates.isEmpty()) {
            return WindowSelection(
                winner = WindowCandidate(policy.minReleaseYear, policy.minReleaseYear + policy.windowYears - 1, 0L),
                runner = null,
                runnerWeight = 0L,
                concentration = 0.0,
                margin = 0.0,
                topEras = emptyList(),
            )
        }

        val comparator = compareByDescending<WindowCandidate> { it.weight }
            .thenBy { abs(it.center - median) }
            .thenBy { it.fromYear }
        val ordered = candidates.sortedWith(comparator)
        val winner = ordered.first()
        val nonOverlapping = ordered.filter {
            it.toYear < winner.fromYear || it.fromYear > winner.toYear
        }
        val runner = nonOverlapping.firstOrNull()
        val runnerWeight = runner?.weight ?: 0L
        val marginWeight = (winner.weight - runnerWeight).coerceAtLeast(0L)
        val concentration = if (datedWeight > 0L) winner.weight.toDouble() / datedWeight else 0.0
        val margin = if (datedWeight > 0L) marginWeight.toDouble() / datedWeight else 0.0
        val topEras = buildList {
            val selected = ArrayList<WindowCandidate>()
            for (candidate in ordered) {
                if (selected.none { it.overlaps(candidate) }) {
                    selected.add(candidate)
                    add(candidate.toSummary(datedWeight))
                }
                if (size == 3) break
            }
        }
        return WindowSelection(
            winner = winner,
            runner = runner,
            runnerWeight = runnerWeight,
            concentration = concentration,
            margin = margin,
            topEras = topEras,
        )
    }

    private fun weightedMedian(weights: Map<Int, Long>): Int? {
        if (weights.isEmpty()) return null
        val total = weights.values.fold(0L, ::saturatingAdd)
        if (total <= 0L) return null
        val threshold = if (total == Long.MAX_VALUE) {
            Long.MAX_VALUE / 2L + 1L
        } else {
            (total + 1L) / 2L
        }
        var accumulated = 0L
        for ((year, weight) in weights.toSortedMap()) {
            accumulated = saturatingAdd(accumulated, weight)
            if (accumulated >= threshold) return year
        }
        return weights.keys.maxOrNull()
    }

    private fun WindowCandidate.toSummary(datedWeight: Long): YearWindowSummary =
        YearWindowSummary(
            fromYear = fromYear,
            toYear = toYear,
            weightMillis = weight,
            share = if (datedWeight > 0L) weight.toDouble() / datedWeight else 0.0,
        )

    private fun WindowCandidate.overlaps(other: WindowCandidate): Boolean =
        fromYear <= other.toYear && other.fromYear <= toYear

    private fun saturatingAdd(left: Long, right: Long): Long {
        if (right <= 0L) return left
        return if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
    }

    private fun saturatingMultiply(left: Long, right: Long): Long {
        if (left <= 0L || right <= 0L) return 0L
        return if (left > Long.MAX_VALUE / right) Long.MAX_VALUE else left * right
    }

    private fun atLeastPercent(part: Long, whole: Long, percent: Int): Boolean {
        if (whole <= 0L || part <= 0L) return false
        return BigInteger.valueOf(part).multiply(BigInteger.valueOf(100L)) >=
            BigInteger.valueOf(whole).multiply(BigInteger.valueOf(percent.toLong()))
    }

    private data class Inclusion(
        val included: List<IncludedEvent>,
        val duplicates: Int,
        val future: Int,
        val beforePeriod: Int,
        val explicitlyExcluded: Int,
        val nonMusic: Int,
        val invalidPlayTime: Int,
        val invalidDurationMetadata: Int,
        val belowMinimumListen: Int,
        val contentKindsByRecording: Map<String, Set<ListeningKind>>,
    )

    private data class IncludedEvent(
        val event: MusicalAgeEvent,
        val effectiveDurationMillis: Long,
        val weightMillis: Long,
    )

    private data class DailyKey(
        val recordingKey: String,
        val day: LocalDate,
    )

    private data class DailyAggregate(
        var rawWeight: Long,
        var cap: Long,
    )

    private data class WindowCandidate(
        val fromYear: Int,
        val toYear: Int,
        val weight: Long,
    ) {
        val center: Double
            get() = fromYear + (toYear - fromYear) / 2.0
    }

    private data class WindowSelection(
        val winner: WindowCandidate,
        val runner: WindowCandidate?,
        val runnerWeight: Long,
        val concentration: Double,
        val margin: Double,
        val topEras: List<YearWindowSummary>,
    )

    private enum class RecordingYearStatus {
        ACCEPTED,
        CONFLICT,
        INCOMPLETE_EVIDENCE,
        MISSING,
        INVALID_YEAR,
        UNRESOLVED_VERSION,
        UNVERIFIED,
        UNCONFIRMED_CONTENT,
        CONTENT_CONFLICT,
        CANDIDATE,
        TRANSIENT_ERROR,
        INVALID_IDENTITY,
    }

    private enum class ContentStatus {
        MUSIC,
        NON_MUSIC,
        UNKNOWN,
        CONFLICT,
    }

    private data class ContentCounts(
        val music: Int,
        val nonMusic: Int,
        val unknown: Int,
        val conflicting: Int,
    )

    private companion object {
        private val MUSICBRAINZ_ID = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
        )
    }
}
