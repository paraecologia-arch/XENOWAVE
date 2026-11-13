package com.dd3boh.xenowave.insights.musicalage.data

import com.dd3boh.xenowave.insights.musicalage.ContentEvidenceState
import com.dd3boh.xenowave.insights.musicalage.ListeningKind
import com.dd3boh.xenowave.insights.musicalage.LocalOnlyRecordingYearResolver
import com.dd3boh.xenowave.insights.musicalage.MusicalAgeDataOrigin
import com.dd3boh.xenowave.insights.musicalage.MusicalAgeEngine
import com.dd3boh.xenowave.insights.musicalage.MusicalAgeEvent
import com.dd3boh.xenowave.insights.musicalage.MusicalAgeResult
import com.dd3boh.xenowave.insights.musicalage.MusicalAgeV2
import com.dd3boh.xenowave.insights.musicalage.RecordingIdentity
import com.dd3boh.xenowave.insights.musicalage.RecordingYearResolver
import com.dd3boh.xenowave.insights.musicalage.YearEvidence
import com.dd3boh.xenowave.insights.musicalage.YearEvidencePrecision
import com.dd3boh.xenowave.insights.musicalage.YearEvidenceSource
import com.dd3boh.xenowave.insights.musicalage.YearEvidenceState
import com.dd3boh.xenowave.insights.musicalage.content.ContentClassifier
import com.dd3boh.xenowave.insights.musicalage.content.ContentEvidence
import com.dd3boh.xenowave.insights.musicalage.content.SongMetadata
import com.dd3boh.xenowave.insights.musicalage.content.UnknownContentClassifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MusicalAgeTrack(
    val songId: String,
    val title: String,
    val artists: List<String>,
    val durationMillis: Long?,
    val eventCount: Int,
    val contentState: ContentEvidenceState,
    val yearState: YearEvidenceState,
    val year: Int?,
    val precision: YearEvidencePrecision,
    val stale: Boolean,
)

data class MusicalAgeTrackPage(
    val items: List<MusicalAgeTrack>,
    val total: Int,
    val offset: Int,
    val limit: Int,
)

/**
 * Reads the local listen history on IO and delegates all calculation to the
 * pure engine.
 *
 * Local song ids are associated with the same canonical recording only when
 * an accepted/manual evidence row proves it. Associations are built before
 * daily caps, and the evidence of every member is merged (contradictions are
 * preserved for the engine).
 */
class MusicalAgeRepository(
    private val dao: MusicalAgeDao,
    private val engine: MusicalAgeEngine,
    private val contentClassifier: ContentClassifier = UnknownContentClassifier(),
    private val historyGeneration: HistoryGeneration = AppMusicalAgeHistoryGeneration,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val metadataLoader = SongMetadataLoader(dao)

    /**
     * Distinct local song ids in the current calculation period. It is used
     * only to load valid cached evidence on IO; it does not call the network.
     */
    suspend fun periodSongIds(): List<String> = withContext(ioDispatcher) {
        val asOf = engine.now()
        dao.songIdsBetween(
            fromInclusive = engine.periodStart(asOf),
            toInclusive = asOf,
        )
    }

    suspend fun diagnose(
        resolver: RecordingYearResolver = LocalOnlyRecordingYearResolver(),
        dataOrigin: MusicalAgeDataOrigin = MusicalAgeDataOrigin.ROOM_LOCAL_HISTORY,
        classifierOverride: ContentClassifier? = null,
    ): MusicalAgeResult = withContext(ioDispatcher) {
        val generationBefore = historyGeneration.current()
        val asOf = engine.now()
        val periodStart = engine.periodStart(asOf)
        val rows = dao.listeningRowsBetween(
            fromInclusive = periodStart,
            toInclusive = asOf,
        )
        val prepared = prepare(
            rows = rows,
            resolver = resolver,
            classifier = classifierOverride ?: contentClassifier,
        )
        val result = engine.calculateAt(
            events = prepared.events,
            asOf = asOf,
            resolver = prepared.resolver,
            dataOrigin = dataOrigin,
        )
        if (historyGeneration.current() != generationBefore) {
            throw HistoryInvalidatedException()
        }
        result
    }

    suspend fun tracks(
        resolver: RecordingYearResolver = LocalOnlyRecordingYearResolver(),
        limit: Int = 50,
        offset: Int = 0,
        classifierOverride: ContentClassifier? = null,
    ): MusicalAgeTrackPage = withContext(ioDispatcher) {
        val generationBefore = historyGeneration.current()
        val asOf = engine.now()
        val periodStart = engine.periodStart(asOf)
        val safeLimit = limit.coerceIn(1, 100)
        val safeOffset = offset.coerceAtLeast(0)
        val rows = dao.eligibleSongsBetween(
            fromInclusive = periodStart,
            toInclusive = asOf,
            limit = safeLimit,
            offset = safeOffset,
        )
        val total = dao.eligibleSongCountBetween(periodStart, asOf)
        val songIds = rows.map { it.songId }.distinct()
        val metadata = metadataLoader.load(songIds)
        val content = (classifierOverride ?: contentClassifier).classify(
            songIds.map { songId -> metadata[songId] ?: emptyMetadata(songId) }
        )
        val identities = songIds.map { songId ->
            val song = metadata[songId]
            RecordingIdentity(
                recordingKey = "local:$songId",
                localSongId = songId,
                localSongIds = listOf(songId),
                localYearHint = song?.localYearHint,
                contentEvidence = content[songId],
            )
        }
        val evidence = resolveSafely(resolver, identities)
        val items = rows.map { row ->
            val evidenceList = evidence["local:${row.songId}"].orEmpty()
            val chosen = chooseEvidence(evidenceList)
            val song = metadata[row.songId]
            MusicalAgeTrack(
                songId = row.songId,
                title = song?.title ?: row.title,
                artists = song?.artists.orEmpty(),
                durationMillis = song?.durationMillis,
                eventCount = row.eventCount,
                contentState = content[row.songId]?.state ?: ContentEvidenceState.UNKNOWN,
                yearState = chosen?.state ?: YearEvidenceState.MISSING,
                year = chosen?.year,
                precision = chosen?.precision ?: YearEvidencePrecision.UNKNOWN,
                stale = chosen?.stale ?: false,
            )
        }
        if (historyGeneration.current() != generationBefore) {
            throw HistoryInvalidatedException()
        }
        MusicalAgeTrackPage(
            items = items,
            total = total,
            offset = safeOffset,
            limit = safeLimit,
        )
    }

    private suspend fun prepare(
        rows: List<MusicalAgeListeningRow>,
        resolver: RecordingYearResolver,
        classifier: ContentClassifier,
    ): Prepared {
        val songIds = rows.map { it.songId }.distinct()
        val metadata = metadataLoader.load(songIds)
        val content = classifier.classify(
            songIds.map { songId -> metadata[songId] ?: emptyMetadata(songId) }
        )
        val identities = songIds.map { songId ->
            val song = metadata[songId]
            RecordingIdentity(
                recordingKey = "local:$songId",
                localSongId = songId,
                localSongIds = listOf(songId),
                localYearHint = song?.localYearHint,
                contentEvidence = content[songId],
            )
        }
        val evidenceBySong = resolveSafely(resolver, identities)
        val associations = buildAssociations(songIds, evidenceBySong)
        val groupEvidence = HashMap<String, MutableList<YearEvidence>>()
        for (songId in songIds) {
            val canonical = associations[songId]
            val key = canonical?.let { "canonical:$it" } ?: "local:$songId"
            groupEvidence.getOrPut(key) { mutableListOf() }
                .addAll(evidenceBySong["local:$songId"].orEmpty())
        }
        val precomputedResolver = RecordingYearResolver { recordings ->
            recordings.associate { recording ->
                recording.recordingKey to groupEvidence[recording.recordingKey].orEmpty().toList()
            }
        }
        val events = rows.map { row ->
            val canonical = associations[row.songId]
            row.toMusicalAgeEvent(
                content = content[row.songId],
                canonicalRecordingId = canonical,
            )
        }
        return Prepared(events = events, resolver = precomputedResolver)
    }

    /**
     * Only proven identities are merged. A catalog row needs an explicit
     * association proof; a manual row needs a canonical id chosen by the
     * person. If the accepted rows disagree on version, the songs stay
     * separate and the conflict stays visible in diagnostics.
     */
    private fun buildAssociations(
        songIds: List<String>,
        evidenceBySong: Map<String, List<YearEvidence>>,
    ): Map<String, String> {
        val byCanonical = HashMap<String, MutableList<String>>()
        for (songId in songIds) {
            val accepted = evidenceBySong["local:$songId"].orEmpty().filter {
                it.state == YearEvidenceState.ACCEPTED &&
                    !it.canonicalRecordingId.isNullOrBlank() &&
                    (
                        it.associationProof != null ||
                            it.source == YearEvidenceSource.USER_CONFIRMED
                        )
            }
            if (accepted.isEmpty()) continue
            val versions = accepted.mapNotNull { it.resolvedVersion }.distinct()
            if (versions.size > 1) continue
            val canonical = accepted.firstNotNullOfOrNull { it.canonicalRecordingId }
                ?: continue
            byCanonical.getOrPut(canonical) { mutableListOf() }.add(songId)
        }
        return buildMap {
            for ((canonical, members) in byCanonical) {
                if (members.size < 2) continue
                members.forEach { put(it, canonical) }
            }
        }
    }

    private fun resolveSafely(
        resolver: RecordingYearResolver,
        identities: List<RecordingIdentity>,
    ): Map<String, List<YearEvidence>> = try {
        resolver.resolve(identities)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        identities.associate { recording ->
            recording.recordingKey to listOf(
                YearEvidence(
                    recordingKey = recording.recordingKey,
                    state = YearEvidenceState.TRANSIENT_ERROR,
                    origin = "resolver",
                    source = YearEvidenceSource.NONE,
                    transientError = e.message ?: e.javaClass.simpleName,
                    reason = "falha ao resolver evidência de ano",
                    policyVersion = MusicalAgeV2.YEAR_EVIDENCE_POLICY_VERSION,
                )
            )
        }
    }

    private fun chooseEvidence(evidence: List<YearEvidence>): YearEvidence? = when {
        evidence.any { it.state == YearEvidenceState.CONFLICT } ->
            evidence.first { it.state == YearEvidenceState.CONFLICT }
        evidence.any { it.state == YearEvidenceState.ACCEPTED } ->
            evidence.first { it.state == YearEvidenceState.ACCEPTED }
        evidence.any { it.state == YearEvidenceState.CANDIDATE } ->
            evidence.first { it.state == YearEvidenceState.CANDIDATE }
        evidence.any { it.state == YearEvidenceState.TRANSIENT_ERROR } ->
            evidence.first { it.state == YearEvidenceState.TRANSIENT_ERROR }
        evidence.any { it.state == YearEvidenceState.UNVERIFIED } ->
            evidence.first { it.state == YearEvidenceState.UNVERIFIED }
        else -> evidence.firstOrNull { it.state == YearEvidenceState.MISSING }
    }

    private fun emptyMetadata(songId: String): SongMetadata = SongMetadata(
        songId = songId,
        title = "",
        artists = emptyList(),
        durationMillis = null,
    )

    private fun MusicalAgeListeningRow.toMusicalAgeEvent(
        content: ContentEvidence?,
        canonicalRecordingId: String?,
    ): MusicalAgeEvent {
        val durationMillis = durationSeconds
            ?.takeIf { it > 0 }
            ?.toLong()
            ?.times(1_000L)
        return MusicalAgeEvent(
            eventId = eventId,
            songId = songId,
            recordingKey = canonicalRecordingId
                ?.let { "canonical:$it" }
                ?: "local:$songId",
            canonicalRecordingId = canonicalRecordingId,
            localYearHint = localYear,
            timestamp = timestamp,
            playedMillis = playedMillis,
            durationMillis = durationMillis,
            kind = content?.kind ?: ListeningKind.UNKNOWN,
            explicitlyExcluded = false,
            contentEvidence = content,
        )
    }

    private data class Prepared(
        val events: List<MusicalAgeEvent>,
        val resolver: RecordingYearResolver,
    )
}
