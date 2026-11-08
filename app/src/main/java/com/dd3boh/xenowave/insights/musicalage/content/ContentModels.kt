package com.dd3boh.xenowave.insights.musicalage.content

import com.dd3boh.xenowave.insights.musicalage.ContentEvidenceSource
import com.dd3boh.xenowave.insights.musicalage.ContentEvidenceState
import com.dd3boh.xenowave.insights.musicalage.ListeningKind
import com.dd3boh.xenowave.insights.musicalage.MusicalAgeV2
import com.dd3boh.xenowave.insights.musicalage.RecordingVersion
import com.dd3boh.xenowave.insights.musicalage.YearEvidencePrecision
import java.time.Instant

/**
 * Metadata available locally for classification and cautious matching.
 *
 * The current schema does not expose validated ISRC/MBID fields. The optional
 * fields exist so a future tag/source can supply them without treating a raw
 * tag as proof automatically.
 */
data class SongMetadata(
    val songId: String,
    val title: String,
    val artists: List<String>,
    val durationMillis: Long?,
    /**
     * Optional catalog/local disambiguation that must not be discarded when
     * comparing versions (e.g. "live at Wembley", "US single edit").
     */
    val disambiguation: String? = null,
    val albumTitle: String? = null,
    val localYearHint: Int? = null,
    val isLocal: Boolean = false,
    val localPathExtension: String? = null,
    val isrc: String? = null,
    val mbid: String? = null,
)

/**
 * Content evidence is separate from year evidence. Playback context, MIME,
 * file extension, artist presence or a title fragment never confirm music by
 * themselves.
 */
data class ContentEvidence(
    val songId: String,
    val state: ContentEvidenceState,
    val source: ContentEvidenceSource,
    val reason: String,
    val policyVersion: String = MusicalAgeV2.CONTENT_POLICY_VERSION,
    val obtainedAt: Instant? = null,
    val linkedRecordingId: String? = null,
) {
    val kind: ListeningKind
        get() = when (state) {
            ContentEvidenceState.CONFIRMED_MUSIC -> ListeningKind.MUSIC
            ContentEvidenceState.CONFIRMED_NON_MUSIC -> ListeningKind.NON_MUSIC
            ContentEvidenceState.UNKNOWN,
            ContentEvidenceState.CONFLICT,
            -> ListeningKind.UNKNOWN
        }
}

fun interface ContentClassifier {
    fun classify(songs: List<SongMetadata>): Map<String, ContentEvidence>
}

/**
 * Conservative classifier: every item starts UNKNOWN unless an explicit,
 * traceable confirmation is supplied. Weak signals are intentionally ignored.
 */
class UnknownContentClassifier(
    private val reason: String = "sem confirmação explícita de conteúdo no schema atual",
) : ContentClassifier {
    override fun classify(songs: List<SongMetadata>): Map<String, ContentEvidence> =
        songs.associate { song ->
            song.songId to ContentEvidence(
                songId = song.songId,
                state = ContentEvidenceState.UNKNOWN,
                source = ContentEvidenceSource.NONE,
                reason = reason,
            )
        }
}

/**
 * Explicit evidence resolver. Manual evidence wins over weak/non-manual
 * evidence; two different manual confirmations remain a visible conflict.
 */
class ExplicitContentClassifier(
    private val confirmed: Map<String, List<ContentEvidence>> = emptyMap(),
    private val manualEvidenceStore: ManualEvidenceStore = EmptyManualEvidenceStore,
) : ContentClassifier {
    override fun classify(songs: List<SongMetadata>): Map<String, ContentEvidence> {
        val manual = manualEvidenceStore.forSongs(songs.map { it.songId })
        return songs.associate { song ->
            val manualKinds = manual[song.songId].orEmpty()
                .mapNotNull { it.contentState }
                .toSet()
            val explicitKinds = confirmed[song.songId].orEmpty()
                .map { it.state }
                .toSet()
            val state = when {
                manualKinds.size > 1 -> ContentEvidenceState.CONFLICT
                manualKinds.size == 1 -> manualKinds.single()
                explicitKinds.size > 1 -> ContentEvidenceState.CONFLICT
                explicitKinds.size == 1 -> explicitKinds.single()
                else -> ContentEvidenceState.UNKNOWN
            }
            val source = when {
                manualKinds.isNotEmpty() -> ContentEvidenceSource.USER_CONFIRMED
                explicitKinds.isNotEmpty() -> ContentEvidenceSource.LOCAL_METADATA
                else -> ContentEvidenceSource.NONE
            }
            song.songId to ContentEvidence(
                songId = song.songId,
                state = state,
                source = source,
                reason = when (state) {
                    ContentEvidenceState.CONFLICT ->
                        "evidências de conteúdo contraditórias; não promovido"
                    ContentEvidenceState.UNKNOWN ->
                        "sem confirmação explícita de conteúdo"
                    else -> "evidência explícita fornecida ao classificador"
                },
            )
        }
    }
}

data class ManualEvidence(
    val songId: String,
    val contentState: ContentEvidenceState? = null,
    val recordingKey: String? = null,
    val canonicalRecordingId: String? = null,
    val year: Int? = null,
    val precision: YearEvidencePrecision = YearEvidencePrecision.UNKNOWN,
    val resolvedVersion: RecordingVersion = RecordingVersion.UNKNOWN,
    val chosenAt: Instant,
    val replacesEvidence: String? = null,
    val policyVersion: String = MusicalAgeV2.MANUAL_EVIDENCE_POLICY_VERSION,
)

interface ManualEvidenceStore {
    fun forSongs(songIds: List<String>): Map<String, List<ManualEvidence>>

    /**
     * Upsert by [ManualEvidence.identityKey]. A legitimate edit replaces the
     * previous value for the same identity instead of accumulating an
     * unfixable conflict. Different identities remain separate and visible.
     */
    fun put(evidence: ManualEvidence)

    fun replace(evidence: ManualEvidence)

    fun remove(songId: String)

    fun clear()
}

object EmptyManualEvidenceStore : ManualEvidenceStore {
    override fun forSongs(songIds: List<String>): Map<String, List<ManualEvidence>> = emptyMap()
    override fun put(evidence: ManualEvidence) = Unit
    override fun replace(evidence: ManualEvidence) = Unit
    override fun remove(songId: String) = Unit
    override fun clear() = Unit
}

class InMemoryManualEvidenceStore(
    initial: Map<String, List<ManualEvidence>> = emptyMap(),
) : ManualEvidenceStore {
    private val data = HashMap<String, MutableList<ManualEvidence>>()
    private val lock = Any()

    init {
        initial.forEach { (songId, entries) ->
            data[songId] = entries.toMutableList()
        }
    }

    override fun forSongs(songIds: List<String>): Map<String, List<ManualEvidence>> =
        synchronized(lock) {
            songIds.mapNotNull { songId ->
                data[songId]?.let { songId to it.toList() }
            }.toMap()
        }

    override fun put(evidence: ManualEvidence) {
        synchronized(lock) {
            upsertLocked(evidence)
        }
    }

    override fun replace(evidence: ManualEvidence) {
        synchronized(lock) {
            upsertLocked(evidence)
        }
    }

    override fun remove(songId: String) {
        synchronized(lock) {
            data.remove(songId)
        }
    }

    override fun clear() {
        synchronized(lock) {
            data.clear()
        }
    }

    fun snapshot(): Map<String, List<ManualEvidence>> = synchronized(lock) {
        data.mapValues { (_, values) -> values.toList() }
    }

    private fun upsertLocked(evidence: ManualEvidence) {
        val list = data.getOrPut(evidence.songId) { mutableListOf() }
        val index = list.indexOfFirst { it.identityKey == evidence.identityKey }
        if (index >= 0) {
            val previous = list[index]
            list[index] = evidence.copy(
                replacesEvidence = evidence.replacesEvidence
                    ?: previous.shortDescription(),
            )
        } else {
            list += evidence
        }
    }
}

val ManualEvidence.identityKey: String
    get() = when {
        year != null -> listOf(
            "year",
            recordingKey.orEmpty(),
            canonicalRecordingId.orEmpty(),
            resolvedVersion.label,
        ).joinToString("|")
        contentState != null -> "content"
        else -> listOf(
            "other",
            recordingKey.orEmpty(),
            canonicalRecordingId.orEmpty(),
            resolvedVersion.label,
        ).joinToString("|")
    }

fun ManualEvidence.shortDescription(): String = buildString {
    append("ano=").append(year ?: "?")
    append(", precisão=").append(precision.name)
    append(", versão=").append(resolvedVersion.label)
    canonicalRecordingId?.let { append(", gravação=").append(it) }
}
