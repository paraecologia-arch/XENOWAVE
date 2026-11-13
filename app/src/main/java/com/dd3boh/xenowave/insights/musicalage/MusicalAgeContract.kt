package com.dd3boh.xenowave.insights.musicalage

import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Parameters proposed for the first version of the musical-age feature.
 *
 * These are product parameters, not Spotify coefficients and not statistical
 * probabilities. They are versioned so a later change cannot silently rewrite
 * an already presented result.
 */
object MusicalAgeV1 {
    const val ALGORITHM_VERSION = "musical-age-v1-proposta"

    // 90-day window: start at the beginning of the day 89 days before asOf.
    const val LOOKBACK_DAYS = 90
    const val WINDOW_YEARS = 5
    const val MIN_RELEASE_YEAR = 1850

    // Ludic convention only. It is not an official Spotify coefficient.
    const val MUSICAL_BIRTH_OFFSET_YEARS = 18

    const val MIN_ELIGIBLE_RECORDINGS = 20
    const val MIN_ACTIVE_DAYS = 3
    const val MIN_WEIGHTED_MINUTES = 120L
    const val MIN_DATED_RECORDINGS = 15
    const val MIN_COVERAGE_PERCENT = 70
    const val MIN_WINDOW_CONCENTRATION_PERCENT = 35
    const val MIN_NON_OVERLAPPING_MARGIN_PERCENT = 10

    const val MIN_LISTEN_MILLIS_KNOWN_DURATION = 10_000L
    const val MIN_LISTEN_MILLIS_UNKNOWN_DURATION = 30_000L
    const val MAX_LISTEN_MILLIS_KNOWN_THRESHOLD = 30_000L
    const val MAX_EVENT_WEIGHT_MILLIS = 5 * 60_000L
    const val MAX_DAILY_WEIGHT_MILLIS = 15 * 60_000L
    const val DAILY_CAP_MULTIPLIER = 3
}

/**
 * Phase-2 policy versions. These are XENOWAVE proposals, not MusicBrainz
 * guarantees or scientific validation of the musical-age result.
 */
object MusicalAgeV2 {
    const val CONTENT_POLICY_VERSION = "content-v2-proposta"
    const val YEAR_EVIDENCE_POLICY_VERSION = "year-evidence-v2-proposta"
    const val MATCHING_POLICY_VERSION = "recording-match-v2-proposta"
    const val ENRICHMENT_POLICY_VERSION = "musicbrainz-enrichment-v2-proposta"
    const val ASSOCIATION_POLICY_VERSION = "recording-association-v1-proposta"
    const val MANUAL_EVIDENCE_POLICY_VERSION = "manual-evidence-v2-proposta"
}

/**
 * Injectable parameters for the deterministic engine.
 *
 * The defaults are the explicit v1 proposal. Keep [algorithmVersion] in the
 * diagnostic so a result can always be traced back to the parameters that
 * produced it.
 */
data class MusicalAgePolicy(
    val algorithmVersion: String = MusicalAgeV1.ALGORITHM_VERSION,
    val lookbackDays: Int = MusicalAgeV1.LOOKBACK_DAYS,
    val windowYears: Int = MusicalAgeV1.WINDOW_YEARS,
    val minReleaseYear: Int = MusicalAgeV1.MIN_RELEASE_YEAR,
    val musicalBirthOffsetYears: Int = MusicalAgeV1.MUSICAL_BIRTH_OFFSET_YEARS,
    val minEligibleRecordings: Int = MusicalAgeV1.MIN_ELIGIBLE_RECORDINGS,
    val minActiveDays: Int = MusicalAgeV1.MIN_ACTIVE_DAYS,
    val minWeightedMillis: Long = MusicalAgeV1.MIN_WEIGHTED_MINUTES * 60_000L,
    val minDatedRecordings: Int = MusicalAgeV1.MIN_DATED_RECORDINGS,
    val minCoveragePercent: Int = MusicalAgeV1.MIN_COVERAGE_PERCENT,
    val minWindowConcentrationPercent: Int = MusicalAgeV1.MIN_WINDOW_CONCENTRATION_PERCENT,
    val minNonOverlappingMarginPercent: Int = MusicalAgeV1.MIN_NON_OVERLAPPING_MARGIN_PERCENT,
    val minListenMillisKnownDuration: Long = MusicalAgeV1.MIN_LISTEN_MILLIS_KNOWN_DURATION,
    val minListenMillisUnknownDuration: Long = MusicalAgeV1.MIN_LISTEN_MILLIS_UNKNOWN_DURATION,
    val maxListenMillisKnownThreshold: Long = MusicalAgeV1.MAX_LISTEN_MILLIS_KNOWN_THRESHOLD,
    val maxEventWeightMillis: Long = MusicalAgeV1.MAX_EVENT_WEIGHT_MILLIS,
    val maxDailyWeightMillis: Long = MusicalAgeV1.MAX_DAILY_WEIGHT_MILLIS,
    val dailyCapMultiplier: Int = MusicalAgeV1.DAILY_CAP_MULTIPLIER,
) {
    init {
        require(lookbackDays >= 1) { "lookbackDays must be at least 1" }
        require(windowYears >= 1) { "windowYears must be at least 1" }
        require(musicalBirthOffsetYears >= 0) { "musicalBirthOffsetYears must not be negative" }
        require(dailyCapMultiplier >= 1) { "dailyCapMultiplier must be at least 1" }
        require(maxEventWeightMillis > 0) { "maxEventWeightMillis must be positive" }
        require(maxDailyWeightMillis > 0) { "maxDailyWeightMillis must be positive" }
    }

    /**
     * Start of the inclusive period. With lookbackDays = 90 this is the
     * beginning of the day 89 days before [asOf].
     */
    fun periodStart(asOf: LocalDateTime): LocalDateTime =
        asOf.toLocalDate().minusDays(lookbackDays - 1L).atStartOfDay()

    /**
     * Last possible start year for a window that does not contain a future
     * year relative to [asOfYear].
     */
    fun lastWindowStartYear(asOfYear: Int): Int = asOfYear - windowYears + 1
}

enum class MusicalAgeStatus {
    NO_HISTORY,
    INSUFFICIENT_HISTORY,
    INSUFFICIENT_METADATA,
    MIXED_ERAS,
    READY,
}

enum class MusicalAgeDataOrigin {
    ROOM_LOCAL_HISTORY,
    SYNTHETIC_FIXTURE,
    UNSPECIFIED,
}

enum class ListeningKind {
    MUSIC,
    NON_MUSIC,
    UNKNOWN,
}

enum class YearEvidenceState {
    ACCEPTED,
    UNVERIFIED,
    CONFLICT,
    MISSING,
    CANDIDATE,
    TRANSIENT_ERROR,
}

enum class YearEvidencePrecision {
    RECORDING_RELEASE_DAY,
    RECORDING_RELEASE_MONTH,
    RECORDING_RELEASE_YEAR,
    ALBUM_RELEASE_YEAR,
    YEAR_ONLY,
    YEAR_MONTH,
    UNKNOWN,
}

enum class YearEvidenceSource {
    USER_CONFIRMED,
    LOCAL_TAG,
    LOCAL_ALBUM,
    CATALOG,
    NONE,
}

enum class ContentEvidenceState {
    CONFIRMED_MUSIC,
    CONFIRMED_NON_MUSIC,
    UNKNOWN,
    CONFLICT,
}

enum class ContentEvidenceSource {
    USER_CONFIRMED,
    LOCAL_METADATA,
    PLAYBACK_CONTEXT,
    CATALOG,
    NONE,
}

enum class RecordingVersion(val label: String) {
    STUDIO("studio"),
    /**
     * The local/catalog title has no version qualifier. This is deliberately
     * not called "studio": absence of a known word never proves a studio
     * recording.
     */
    UNQUALIFIED("unqualified"),
    LIVE("live"),
    REMIX("remix"),
    COVER("cover"),
    ACOUSTIC("acoustic"),
    INSTRUMENTAL("instrumental"),
    DEMO("demo"),
    KARAOKE("karaoke"),
    RADIO_EDIT("radio-edit"),
    EXTENDED("extended"),
    RERECORDING("re-recording"),
    UNKNOWN("unknown"),
}

enum class MusicalAgeRequirement {
    ELIGIBLE_RECORDINGS,
    ACTIVE_DAYS,
    WEIGHTED_MINUTES,
    DATED_RECORDINGS,
    COVERAGE,
    WINDOW_CONCENTRATION,
    NON_OVERLAPPING_MARGIN,
}

/**
 * Convention used by the feature. The labels are product copy, not a claim
 * that these boundaries are universal or scientifically exact.
 */
enum class MusicalGeneration(val label: String) {
    CLASSICS("Clássicos de outras gerações"),
    SILENT("Geração Silenciosa"),
    BOOMER("Baby Boomer"),
    GENERATION_X("Geração X"),
    MILLENNIAL("Millennial / Geração Y"),
    GENERATION_Z("Geração Z"),
    ALPHA("Geração Alpha");

    companion object {
        fun forBirthYear(year: Int): MusicalGeneration = when {
            year < 1928 -> CLASSICS
            year <= 1945 -> SILENT
            year <= 1964 -> BOOMER
            year <= 1980 -> GENERATION_X
            year <= 1996 -> MILLENNIAL
            year <= 2012 -> GENERATION_Z
            else -> ALPHA
        }
    }
}

/**
 * A clock/zone pair is injected so production never hard-codes 2026 and tests
 * can use a fixed instant.
 */
class MusicalAgeClock(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zoneId: ZoneId = clock.zone,
) {
    fun now(): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), zoneId)

    fun zoneId(): ZoneId = zoneId
}
