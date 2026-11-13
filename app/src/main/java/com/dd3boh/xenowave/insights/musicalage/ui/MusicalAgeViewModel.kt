package com.dd3boh.xenowave.insights.musicalage.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.xenowave.constants.PauseListenHistoryKey
import com.dd3boh.xenowave.insights.musicalage.ContentEvidenceState
import com.dd3boh.xenowave.insights.musicalage.LocalOnlyRecordingYearResolver
import com.dd3boh.xenowave.insights.musicalage.MusicalAgeResult
import com.dd3boh.xenowave.insights.musicalage.ReadyResult
import com.dd3boh.xenowave.insights.musicalage.RecordingVersion
import com.dd3boh.xenowave.insights.musicalage.YearEvidence
import com.dd3boh.xenowave.insights.musicalage.YearEvidencePrecision
import com.dd3boh.xenowave.insights.musicalage.content.ExplicitContentClassifier
import com.dd3boh.xenowave.insights.musicalage.content.ManualEvidence
import com.dd3boh.xenowave.insights.musicalage.data.AppMusicalAgeHistoryGeneration
import com.dd3boh.xenowave.insights.musicalage.data.HistoryInvalidatedException
import com.dd3boh.xenowave.insights.musicalage.data.ManualEvidenceRepository
import com.dd3boh.xenowave.insights.musicalage.data.MusicalAgeRepository
import com.dd3boh.xenowave.insights.musicalage.data.MusicalAgeTrack
import com.dd3boh.xenowave.insights.musicalage.metadata.ConsentRevokedException
import com.dd3boh.xenowave.insights.musicalage.metadata.EnrichmentSnapshot
import com.dd3boh.xenowave.insights.musicalage.metadata.EnrichmentStopReason
import com.dd3boh.xenowave.insights.musicalage.metadata.LookupProgress
import com.dd3boh.xenowave.insights.musicalage.metadata.MetadataConsent
import com.dd3boh.xenowave.insights.musicalage.metadata.MetadataLookupAuthorization
import com.dd3boh.xenowave.insights.musicalage.metadata.MetadataProvider
import com.dd3boh.xenowave.insights.musicalage.metadata.MusicBrainzEnrichmentService
import com.dd3boh.xenowave.insights.musicalage.metadata.RecordingEvidenceCache
import com.dd3boh.xenowave.utils.dataStore
import com.dd3boh.xenowave.utils.get
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

enum class MusicalAgeMessage {
    SAVED,
    INVALID_YEAR,
    SAVE_FAILED,
    SELECTION_LIMIT,
    SELECTION_REQUIRED,
    CONFIRM_REMOVE,
    CANCELLED,
}

data class SelectedTrack(
    val songId: String,
    val title: String,
    val artists: List<String>,
)

/**
 * A one-shot authorization captured from the visible result. It is valid only
 * while the exact same result object and data revision are still current.
 */
data class MusicalAgeExportToken(
    val result: ReadyResult,
    val revision: Long,
)

data class MusicalAgeUiState(
    val loading: Boolean = false,
    val result: MusicalAgeResult? = null,
    val resultRevision: Long = -1L,
    val error: Boolean = false,
    val cancelled: Boolean = false,
    val tracks: List<MusicalAgeTrack> = emptyList(),
    val trackTotal: Int = 0,
    val trackOffset: Int = 0,
    val trackLimit: Int = PAGE_SIZE,
    val manualEntries: Map<String, List<ManualEvidence>> = emptyMap(),
    val selected: Set<String> = emptySet(),
    val selectedTracks: List<SelectedTrack> = emptyList(),
    val enrichment: EnrichmentSnapshot? = null,
    val enrichmentLoading: Boolean = false,
    val progress: LookupProgress? = null,
    val message: MusicalAgeMessage? = null,
    val historyPaused: Boolean = false,
) {
    companion object {
        const val PAGE_SIZE = 50
        const val MAX_SELECTION = 20
    }
}

@HiltViewModel
class MusicalAgeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MusicalAgeRepository,
    private val enrichmentService: MusicBrainzEnrichmentService,
    private val manualEvidenceRepository: ManualEvidenceRepository,
    private val evidenceCache: RecordingEvidenceCache,
) : ViewModel() {
    private val _state = MutableStateFlow(MusicalAgeUiState())
    val state: StateFlow<MusicalAgeUiState> = _state.asStateFlow()

    private var calculationJob: Job? = null
    private var trackJob: Job? = null
    private var enrichmentJob: Job? = null
    private var activeConsent: MetadataConsent? = null

    // Every state-changing operation carries an id/revision. A callback from an
    // old job can no longer publish progress, results or errors over a newer
    // operation.
    private var calculationOperation = 0L
    private var trackOperation = 0L
    private var enrichmentOperation = 0L
    private var dataRevision = 0L
    private var localCacheEvidence: Map<String, List<YearEvidence>> = emptyMap()

    init {
        viewModelScope.launch {
            val loaded = manualEvidenceRepository.load()
            if (!loaded) {
                _state.update { it.copy(message = MusicalAgeMessage.SAVE_FAILED) }
            }
            refresh()
            loadTracks()
        }
        viewModelScope.launch {
            context.dataStore.data
                .map { it[PauseListenHistoryKey] ?: false }
                .distinctUntilChanged()
                .collect { paused ->
                    _state.update { it.copy(historyPaused = paused) }
                }
        }
        viewModelScope.launch {
            AppMusicalAgeHistoryGeneration.changes.drop(1).collect {
                cancelAll()
                invalidateResult()
                _state.update {
                    it.copy(
                        error = false,
                        cancelled = false,
                        selected = emptySet(),
                        selectedTracks = emptyList(),
                        enrichment = null,
                        progress = null,
                        tracks = emptyList(),
                        trackOffset = 0,
                        message = null,
                    )
                }
                localCacheEvidence = emptyMap()
                refresh()
                loadTracks()
            }
        }
    }

    fun refresh() {
        calculationJob?.cancel()
        val operation = ++calculationOperation
        val revision = dataRevision
        calculationJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = false, cancelled = false) }
            try {
                val cache = loadLocalEvidence()
                if (operation != calculationOperation || revision != dataRevision) return@launch
                val result = repository.diagnose(
                    resolver = resolver(cache),
                    classifierOverride = classifier(),
                )
                if (operation != calculationOperation || revision != dataRevision) return@launch
                _state.update {
                    it.copy(
                        loading = false,
                        result = result,
                        resultRevision = revision,
                        error = false,
                    )
                }
            } catch (_: HistoryInvalidatedException) {
                if (operation == calculationOperation) {
                    _state.update { it.copy(loading = false, cancelled = true) }
                }
            } catch (_: ConsentRevokedException) {
                if (operation == calculationOperation) {
                    _state.update { it.copy(loading = false, cancelled = true) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (operation == calculationOperation) {
                    _state.update { it.copy(loading = false, error = true) }
                }
            }
        }
    }

    fun cancelCalculation() {
        calculationOperation++
        calculationJob?.cancel()
        _state.update {
            it.copy(
                loading = false,
                cancelled = true,
                message = MusicalAgeMessage.CANCELLED,
            )
        }
    }

    fun loadTracks() {
        trackJob?.cancel()
        val operation = ++trackOperation
        trackJob = viewModelScope.launch {
            val current = _state.value
            try {
                val cache = loadLocalEvidence()
                if (operation != trackOperation) return@launch
                val page = repository.tracks(
                    resolver = resolver(cache),
                    limit = current.trackLimit,
                    offset = current.trackOffset,
                    classifierOverride = classifier(),
                )
                if (operation != trackOperation) return@launch
                val manual = manualEvidenceRepository.snapshotStore()
                    .forSongs(page.items.map { it.songId })
                _state.update {
                    it.copy(
                        tracks = page.items,
                        trackTotal = page.total,
                        trackOffset = page.offset,
                        trackLimit = page.limit,
                        manualEntries = manual,
                    )
                }
            } catch (_: HistoryInvalidatedException) {
                if (operation == trackOperation) {
                    _state.update { it.copy(cancelled = true) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (operation == trackOperation) {
                    _state.update { it.copy(error = true) }
                }
            }
        }
    }

    fun nextTracks() {
        val current = _state.value
        if (current.trackOffset + current.trackLimit >= current.trackTotal) return
        _state.update { it.copy(trackOffset = it.trackOffset + it.trackLimit) }
        loadTracks()
    }

    fun previousTracks() {
        val current = _state.value
        if (current.trackOffset <= 0) return
        _state.update {
            it.copy(trackOffset = (it.trackOffset - it.trackLimit).coerceAtLeast(0))
        }
        loadTracks()
    }

    fun toggleSelection(songId: String) {
        val current = _state.value
        // Changing the authorization scope ends any lookup still running for
        // the previous scope. A completed snapshot may remain: it belongs to
        // current history data, not to the selection.
        stopActiveEnrichment(announceCancel = current.enrichmentLoading)
        if (songId in current.selected) {
            _state.update {
                it.copy(
                    selected = it.selected - songId,
                    selectedTracks = it.selectedTracks.filterNot { track ->
                        track.songId == songId
                    },
                )
            }
            return
        }
        if (current.selected.size >= MusicalAgeUiState.MAX_SELECTION) {
            _state.update { it.copy(message = MusicalAgeMessage.SELECTION_LIMIT) }
            return
        }
        val track = current.tracks.firstOrNull { it.songId == songId }
        _state.update {
            it.copy(
                selected = it.selected + songId,
                selectedTracks = it.selectedTracks + SelectedTrack(
                    songId = songId,
                    title = track?.title?.takeIf(String::isNotBlank) ?: songId,
                    artists = track?.artists.orEmpty(),
                ),
            )
        }
    }

    fun clearSelection() {
        stopActiveEnrichment(announceCancel = _state.value.enrichmentLoading)
        _state.update {
            it.copy(
                selected = emptySet(),
                selectedTracks = emptyList(),
                message = null,
            )
        }
    }

    fun consultSelected() {
        val selected = _state.value.selected.toList()
        if (selected.isEmpty()) {
            _state.update { it.copy(message = MusicalAgeMessage.SELECTION_REQUIRED) }
            return
        }
        stopActiveEnrichment(announceCancel = _state.value.enrichmentLoading)
        val operation = ++enrichmentOperation
        lateinit var consent: MetadataConsent
        consent = MetadataConsent {
            // Revoking the token cancels the coroutine/HTTP call, not just the
            // publication of its late result.
            if (operation == enrichmentOperation) {
                enrichmentJob?.cancel()
            }
        }
        activeConsent = consent
        val authorization = MetadataLookupAuthorization(
            provider = MetadataProvider.MUSICBRAINZ,
            approvedSongIds = selected.toSet(),
            purpose = "confirmar data de gravação das faixas escolhidas",
            approvedAt = Instant.now(),
            consent = consent,
        )
        enrichmentJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    enrichmentLoading = true,
                    error = false,
                    cancelled = false,
                    progress = LookupProgress(
                        selectedSongs = selected.size,
                        completedSongs = 0,
                        currentIndex = 0,
                        requestsAttempted = 0,
                    ),
                )
            }
            try {
                val snapshot = enrichmentService.enrich(
                    authorization = authorization,
                    songIds = selected,
                    onProgress = { progress ->
                        if (operation == enrichmentOperation &&
                            activeConsent === consent
                        ) {
                            _state.update { it.copy(progress = progress) }
                        }
                    },
                )
                if (operation != enrichmentOperation || activeConsent !== consent) {
                    return@launch
                }
                val stopped = snapshot.stoppedReason == EnrichmentStopReason.REVOKED ||
                    snapshot.stoppedReason == EnrichmentStopReason.HISTORY_INVALIDATED
                _state.update {
                    it.copy(
                        enrichmentLoading = false,
                        enrichment = if (stopped) null else snapshot,
                        cancelled = stopped,
                    )
                }
                activeConsent = null
                if (!stopped && operation == enrichmentOperation) {
                    refresh()
                    loadTracks()
                }
            } catch (_: ConsentRevokedException) {
                if (operation == enrichmentOperation) {
                    _state.update {
                        it.copy(enrichmentLoading = false, cancelled = true)
                    }
                    activeConsent = null
                }
            } catch (_: HistoryInvalidatedException) {
                if (operation == enrichmentOperation) {
                    _state.update {
                        it.copy(
                            enrichmentLoading = false,
                            cancelled = true,
                            enrichment = null,
                        )
                    }
                    activeConsent = null
                }
            } catch (e: CancellationException) {
                if (operation == enrichmentOperation) {
                    _state.update {
                        it.copy(
                            enrichmentLoading = false,
                            progress = null,
                            cancelled = true,
                        )
                    }
                    activeConsent = null
                }
            } catch (_: Exception) {
                if (operation == enrichmentOperation) {
                    _state.update {
                        it.copy(
                            enrichmentLoading = false,
                            error = true,
                        )
                    }
                    activeConsent = null
                }
            }
        }
    }

    fun cancelEnrichment() {
        stopActiveEnrichment(announceCancel = true)
        _state.update {
            it.copy(message = MusicalAgeMessage.CANCELLED)
        }
    }

    /**
     * Called when the screen host stops (background/navigation/rotation).
     * Metadata work is revoked; local calculation is not restarted
     * automatically when the UI returns.
     */
    fun onHostStopped() {
        stopActiveEnrichment(announceCancel = _state.value.enrichmentLoading)
    }

    fun confirmMusic(songId: String, music: Boolean) {
        viewModelScope.launch {
            val ok = manualEvidenceRepository.put(
                ManualEvidence(
                    songId = songId,
                    contentState = if (music) {
                        ContentEvidenceState.CONFIRMED_MUSIC
                    } else {
                        ContentEvidenceState.CONFIRMED_NON_MUSIC
                    },
                    chosenAt = Instant.now(),
                )
            )
            applyManualResult(ok)
        }
    }

    fun confirmYear(
        songId: String,
        year: Int,
        precision: YearEvidencePrecision,
        version: RecordingVersion,
        canonicalRecordingId: String? = null,
    ) {
        val currentYear = LocalDate.now().year
        if (year !in 1850..currentYear ||
            precision == YearEvidencePrecision.UNKNOWN ||
            version == RecordingVersion.UNKNOWN
        ) {
            _state.update { it.copy(message = MusicalAgeMessage.INVALID_YEAR) }
            return
        }
        viewModelScope.launch {
            val ok = manualEvidenceRepository.put(
                ManualEvidence(
                    songId = songId,
                    recordingKey = "local:$songId",
                    canonicalRecordingId = canonicalRecordingId
                        ?.takeIf { it.isNotBlank() },
                    year = year,
                    precision = precision,
                    resolvedVersion = version,
                    chosenAt = Instant.now(),
                )
            )
            applyManualResult(ok)
        }
    }

    fun removeConfirmation(songId: String) {
        viewModelScope.launch {
            val ok = manualEvidenceRepository.remove(songId)
            applyManualResult(ok)
        }
    }

    fun clearTechnicalCache() {
        stopActiveEnrichment(announceCancel = false)
        invalidateResult()
        localCacheEvidence = emptyMap()
        viewModelScope.launch {
            val cleared = withContext(Dispatchers.IO) { evidenceCache.clear() }
            _state.update {
                it.copy(
                    enrichment = null,
                    enrichmentLoading = false,
                    progress = null,
                    message = if (cleared) {
                        MusicalAgeMessage.SAVED
                    } else {
                        MusicalAgeMessage.SAVE_FAILED
                    },
                )
            }
            refresh()
        }
    }

    fun clearManualConfirmations() {
        viewModelScope.launch {
            val ok = manualEvidenceRepository.clear()
            applyManualResult(ok)
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    private suspend fun applyManualResult(ok: Boolean) {
        if (!ok) {
            _state.update { it.copy(message = MusicalAgeMessage.SAVE_FAILED) }
            return
        }
        // The persisted data changed: the previous result no longer describes
        // the current evidence, even if the recalculation fails.
        invalidateResult()
        stopActiveEnrichment(announceCancel = false)
        _state.update {
            it.copy(
                message = MusicalAgeMessage.SAVED,
                cancelled = false,
            )
        }
        refresh()
        loadTracks()
    }

    /**
     * Invalidates any visible derived result. [refresh] captures the new
     * revision and refuses to publish a result calculated from older data.
     */
    private fun invalidateResult() {
        dataRevision++
        _state.update { it.copy(result = null, resultRevision = -1L) }
    }

    /**
     * Captures the result and revision that the user is acting on. Rendering
     * and sharing must call [isExportTokenCurrent] immediately before writing
     * and again before opening the chooser.
     */
    fun createExportToken(): MusicalAgeExportToken? {
        val current = _state.value
        val result = current.result as? ReadyResult ?: return null
        if (current.loading || current.resultRevision != dataRevision) return null
        return MusicalAgeExportToken(result, dataRevision)
    }

    fun isExportTokenCurrent(token: MusicalAgeExportToken): Boolean {
        val current = _state.value
        return !current.loading &&
            current.result === token.result &&
            current.resultRevision == token.revision &&
            dataRevision == token.revision
    }

    private suspend fun loadLocalEvidence(): Map<String, List<YearEvidence>> =
        try {
            val songIds = repository.periodSongIds()
            enrichmentService.cachedEvidenceFor(songIds).also {
                localCacheEvidence = it
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            localCacheEvidence
        }

    private fun resolver(
        cache: Map<String, List<YearEvidence>> = localCacheEvidence,
    ): LocalOnlyRecordingYearResolver {
        val snapshotEvidence = _state.value.enrichment?.evidence.orEmpty()
        val confirmed = LinkedHashMap<String, List<YearEvidence>>()
        confirmed.putAll(cache)
        confirmed.putAll(snapshotEvidence)
        // The enrichment snapshot is keyed by local song id; the pure engine
        // asks for the resolver's `local:<songId>` key.
        snapshotEvidence.forEach { (songId, evidence) ->
            if (!songId.startsWith("local:") && !songId.startsWith("canonical:")) {
                confirmed["local:$songId"] = evidence
            }
        }
        return LocalOnlyRecordingYearResolver(
            confirmedEvidence = confirmed,
            manualEvidenceStore = manualEvidenceRepository.snapshotStore(),
        )
    }

    private fun classifier() = ExplicitContentClassifier(
        manualEvidenceStore = manualEvidenceRepository.snapshotStore(),
    )

    /**
     * Ends the current enrichment operation and makes every callback from it
     * inert. Must be called before changing selection, clearing evidence,
     * leaving the screen or applying a manual correction.
     */
    private fun stopActiveEnrichment(announceCancel: Boolean) {
        enrichmentOperation++
        activeConsent?.revoke()
        activeConsent = null
        enrichmentJob?.cancel()
        enrichmentJob = null
        _state.update {
            it.copy(
                enrichmentLoading = false,
                progress = null,
                cancelled = it.cancelled || announceCancel,
            )
        }
    }

    private fun cancelAll() {
        calculationOperation++
        trackOperation++
        activeConsent?.revoke()
        activeConsent = null
        calculationJob?.cancel()
        trackJob?.cancel()
        enrichmentJob?.cancel()
        enrichmentJob = null
        enrichmentOperation++
    }

    override fun onCleared() {
        cancelAll()
        super.onCleared()
    }
}
