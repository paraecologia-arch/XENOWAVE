package com.dd3boh.xenowave.insights.musicalage.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.dd3boh.xenowave.LocalPlayerAwareWindowInsets
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.constants.TopBarInsets
import com.dd3boh.xenowave.insights.musicalage.ContentEvidenceState
import com.dd3boh.xenowave.insights.musicalage.InsufficientHistoryResult
import com.dd3boh.xenowave.insights.musicalage.InsufficientMetadataResult
import com.dd3boh.xenowave.insights.musicalage.MixedErasResult
import com.dd3boh.xenowave.insights.musicalage.MusicalAgeRequirement
import com.dd3boh.xenowave.insights.musicalage.MusicalAgeResult
import com.dd3boh.xenowave.insights.musicalage.NoHistoryResult
import com.dd3boh.xenowave.insights.musicalage.ReadyResult
import com.dd3boh.xenowave.insights.musicalage.RecordingVersion
import com.dd3boh.xenowave.insights.musicalage.UnmetRequirement
import com.dd3boh.xenowave.insights.musicalage.YearEvidencePrecision
import com.dd3boh.xenowave.insights.musicalage.YearEvidenceState
import com.dd3boh.xenowave.insights.musicalage.data.MusicalAgeTrack
import com.dd3boh.xenowave.insights.musicalage.metadata.EnrichmentSnapshot
import com.dd3boh.xenowave.insights.musicalage.metadata.EnrichmentStopReason
import com.dd3boh.xenowave.insights.musicalage.metadata.LookupProgress
import com.dd3boh.xenowave.insights.musicalage.content.ManualEvidence
import com.dd3boh.xenowave.insights.musicalage.ui.visual.MusicalAgeCardFiles
import com.dd3boh.xenowave.insights.musicalage.ui.visual.MusicalAgeCardFormat
import com.dd3boh.xenowave.insights.musicalage.ui.visual.MusicalAgeCardRenderer
import com.dd3boh.xenowave.insights.musicalage.ui.visual.CachedMusicalAgeCardStore
import com.dd3boh.xenowave.insights.musicalage.ui.visual.MediaStorePendingImageSession
import com.dd3boh.xenowave.insights.musicalage.ui.visual.MusicalAgeExportOperation
import com.dd3boh.xenowave.insights.musicalage.ui.visual.MusicalAgeExportOutcome
import com.dd3boh.xenowave.insights.musicalage.ui.visual.MusicalAgeGalleryWriter
import com.dd3boh.xenowave.insights.musicalage.ui.visual.MusicalAgeMixedCard
import com.dd3boh.xenowave.insights.musicalage.ui.visual.MusicalAgeReadyCard
import com.dd3boh.xenowave.insights.musicalage.ui.visual.executeMusicalAgeExport
import com.dd3boh.xenowave.sharing.OfficialAppLinkProvider
import com.dd3boh.xenowave.ui.component.LazyColumnScrollbar
import com.dd3boh.xenowave.ui.icons.XenoIcon
import com.dd3boh.xenowave.ui.icons.XenoIcons
import com.dd3boh.xenowave.ui.utils.backToMain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicalAgeScreen(
    navController: NavController,
    viewModel: MusicalAgeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    var showClearCacheDialog by rememberSaveable { mutableStateOf(false) }
    var showClearManualDialog by rememberSaveable { mutableStateOf(false) }
    var exportRequest by remember { mutableStateOf<ExportRequest?>(null) }
    val exportSession = remember { MusicalAgeExportSession() }
    val activeExport = exportSession.active
    val exporting = activeExport != null
    var exportMessage by remember { mutableStateOf<Int?>(null) }
    val cardStore = remember(context) { CachedMusicalAgeCardStore(context) }
    val galleryWriter = remember(context) {
        MusicalAgeGalleryWriter(
            available = MusicalAgeCardFiles::canSaveToGallery,
            newSession = { MediaStorePendingImageSession(context.contentResolver) },
        )
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    fun copyLink() {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(
            ClipData.newPlainText(
                context.getString(R.string.app_name),
                OfficialAppLinkProvider.DOWNLOAD_URL,
            )
        )
        exportMessage = R.string.musical_age_card_link_copied
    }

    fun openDownload() {
        runCatching {
            uriHandler.openUri(OfficialAppLinkProvider.DOWNLOAD_URL)
        }.onFailure {
            exportMessage = R.string.musical_age_card_open_failed
        }
    }

    fun startExport(format: MusicalAgeCardFormat, saveToGallery: Boolean) {
        if (exportSession.active != null) return
        val token = viewModel.createExportToken()
        if (token == null) {
            exportMessage = R.string.musical_age_card_invalidated
            return
        }
        val operation = MusicalAgeExportOperation(
            token = token,
            validity = { viewModel.isExportTokenCurrent(token) },
        )
        // Claim the operation synchronously so two quick taps cannot render
        // and share two cards at the same time.
        val active = exportSession.begin(operation) ?: return
        exportMessage = null
        active.job = scope.launch {
            val outcome = executeMusicalAgeExport(
                operation = operation,
                format = format,
                saveToGallery = saveToGallery,
                renderer = {
                    // NonCancellable keeps ownership of the bitmap if the host
                    // is disposed mid-render; the flow then recycles it.
                    withContext(Dispatchers.Default + NonCancellable) {
                        MusicalAgeCardRenderer.render(context, token.result, format)
                    }
                },
                cardStore = cardStore,
                gallery = galleryWriter,
                launchChooser = { uri, result ->
                    context.startActivity(
                        MusicalAgeCardFiles.shareChooser(context, uri, result),
                    )
                },
            )
            // Only the operation that still owns the session may publish its
            // message or release the controls.
            if (exportSession.finish(active)) {
                exportMessage = outcome.messageResource()
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                // Background/navigation/rotation ends the metadata operation.
                // Returning to the screen does not start a new lookup.
                viewModel.onHostStopped()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(exportSession) {
        onDispose {
            // Leaving/rotating the screen must not deliver a card after the
            // chooser host has disappeared.
            exportSession.cancelActive()
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = LocalPlayerAwareWindowInsets.current
            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
            .asPaddingValues(),
        modifier = Modifier.windowInsetsPadding(
            LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)
        ),
    ) {
        item(key = "status") {
            Column(Modifier.padding(16.dp)) {
                if (state.loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.musical_age_loading))
                    }
                    OutlinedButton(onClick = viewModel::cancelCalculation) {
                        Text(stringResource(R.string.musical_age_cancel))
                    }
                }
                state.result?.let { result ->
                    ResultCard(
                        result = result,
                        exporting = exporting,
                        onShare = {
                            exportRequest = ExportRequest(saveToGallery = false)
                        },
                        onSave = {
                            exportRequest = ExportRequest(saveToGallery = true)
                        },
                        onDownload = ::openDownload,
                        onCopyLink = ::copyLink,
                        onCancelExport = {
                            // Keep the operation active until its IO boundary
                            // returns; the flow cleans its own artifact and
                            // then releases the controls.
                            exportSession.cancelActive()
                            if (exportSession.active != null) {
                                exportMessage = R.string.musical_age_msg_cancelled
                            }
                        },
                    )
                }
                exportMessage?.let { message ->
                    Text(
                        text = stringResource(message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (state.historyPaused) {
                    Text(
                        text = stringResource(R.string.musical_age_history_paused),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                if (state.error) {
                    Text(
                        text = stringResource(R.string.musical_age_error),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    OutlinedButton(onClick = viewModel::refresh) {
                        Text(stringResource(R.string.musical_age_retry))
                    }
                }
                if (state.cancelled && !state.enrichmentLoading) {
                    Text(
                        text = stringResource(R.string.musical_age_cancelled),
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    OutlinedButton(onClick = viewModel::refresh) {
                        Text(stringResource(R.string.musical_age_retry))
                    }
                }
                state.message?.let { message ->
                    Text(
                        text = messageText(message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                OutlinedButton(
                    onClick = viewModel::refresh,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.musical_age_refresh))
                }
            }
        }

        item(key = "details") {
            state.result?.let { result ->
                DetailsSection(result)
            }
        }

        item(key = "manual_header") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.musical_age_manual_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    text = stringResource(R.string.musical_age_manual_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.musical_age_tracks_title,
                        state.trackTotal,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = viewModel::previousTracks,
                        enabled = state.trackOffset > 0,
                    ) {
                        Text(stringResource(R.string.musical_age_prev_page))
                    }
                    Text(
                        text = stringResource(
                            R.string.musical_age_page,
                            (state.trackOffset + 1).coerceAtMost(state.trackTotal),
                            (state.trackOffset + state.tracks.size),
                            state.trackTotal,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = viewModel::nextTracks,
                        enabled = state.trackOffset + state.trackLimit < state.trackTotal,
                    ) {
                        Text(stringResource(R.string.musical_age_next_page))
                    }
                }
            }
        }

        if (state.tracks.isEmpty()) {
            item(key = "tracks_empty") {
                Text(
                    text = stringResource(R.string.musical_age_tracks_empty),
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        items(
            items = state.tracks,
            key = { it.songId },
        ) { track ->
            TrackRow(
                track = track,
                selected = track.songId in state.selected,
                manual = state.manualEntries[track.songId].orEmpty(),
                onToggleSelection = { viewModel.toggleSelection(track.songId) },
                onConfirmMusic = { viewModel.confirmMusic(track.songId, true) },
                onConfirmNonMusic = { viewModel.confirmMusic(track.songId, false) },
                onConfirmYear = { year, precision, version, canonical ->
                    viewModel.confirmYear(
                        songId = track.songId,
                        year = year,
                        precision = precision,
                        version = version,
                        canonicalRecordingId = canonical,
                    )
                },
                onRemove = { viewModel.removeConfirmation(track.songId) },
            )
        }

        item(key = "enrichment") {
            EnrichmentSection(
                state = state,
                onConsult = viewModel::consultSelected,
                onCancel = viewModel::cancelEnrichment,
                onOffline = viewModel::clearSelection,
            )
        }

        item(key = "cleanup") {
            Column(Modifier.padding(16.dp)) {
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.musical_age_scope_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                OutlinedButton(
                    onClick = { showClearCacheDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Text(stringResource(R.string.musical_age_clear_cache))
                }
                OutlinedButton(
                    onClick = { showClearManualDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Text(stringResource(R.string.musical_age_clear_manual))
                }
            }
        }
    }

    LazyColumnScrollbar(state = listState)

    TopAppBar(
        title = { Text(stringResource(R.string.musical_age_title)) },
        navigationIcon = {
            com.dd3boh.xenowave.ui.component.button.IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                XenoIcon(
                    icon = XenoIcons.AutoMirrored.ArrowBack,
                    contentDescription = null,
                )
            }
        },
        windowInsets = TopBarInsets,
    )

    if (showClearCacheDialog) {
        ConfirmDialog(
            title = stringResource(R.string.musical_age_clear_cache),
            body = stringResource(R.string.musical_age_clear_cache_confirm),
            onConfirm = {
                showClearCacheDialog = false
                viewModel.clearTechnicalCache()
            },
            onDismiss = { showClearCacheDialog = false },
        )
    }
    if (showClearManualDialog) {
        ConfirmDialog(
            title = stringResource(R.string.musical_age_clear_manual),
            body = stringResource(R.string.musical_age_clear_manual_confirm),
            onConfirm = {
                showClearManualDialog = false
                viewModel.clearManualConfirmations()
            },
            onDismiss = { showClearManualDialog = false },
        )
    }

    exportRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { exportRequest = null },
            title = { Text(stringResource(R.string.musical_age_card_format_title)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.musical_age_card_format_body))
                    OutlinedButton(
                        onClick = {
                            exportRequest = null
                            startExport(MusicalAgeCardFormat.VERTICAL, request.saveToGallery)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.musical_age_card_format_vertical))
                    }
                    OutlinedButton(
                        onClick = {
                            exportRequest = null
                            startExport(MusicalAgeCardFormat.SQUARE, request.saveToGallery)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.musical_age_card_format_square))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { exportRequest = null }) {
                    Text(stringResource(R.string.musical_age_dialog_cancel))
                }
            },
        )
    }
}

private data class ExportRequest(val saveToGallery: Boolean)

private fun MusicalAgeExportOutcome.messageResource(): Int? = when (this) {
    MusicalAgeExportOutcome.SHARED -> null
    MusicalAgeExportOutcome.SAVED -> R.string.musical_age_card_saved
    MusicalAgeExportOutcome.CANCELLED -> R.string.musical_age_msg_cancelled
    MusicalAgeExportOutcome.INVALIDATED -> R.string.musical_age_card_invalidated
    MusicalAgeExportOutcome.SAVE_UNAVAILABLE -> R.string.musical_age_card_save_unavailable
    MusicalAgeExportOutcome.FAILED -> R.string.musical_age_card_export_failed
}

@Composable
internal fun ResultCard(
    result: MusicalAgeResult,
    exporting: Boolean,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onDownload: () -> Unit,
    onCopyLink: () -> Unit,
    onCancelExport: () -> Unit,
    reduceMotionOverride: Boolean? = null,
    replayToken: Int = 0,
) {
    val diagnostic = result.diagnostic
    when (result) {
        is ReadyResult -> {
            MusicalAgeReadyCard(
                result = result,
                isExporting = exporting,
                onShare = onShare,
                onSave = onSave,
                onDownload = onDownload,
                onCopyLink = onCopyLink,
                onCancelExport = onCancelExport,
                reduceMotionOverride = reduceMotionOverride,
                replayToken = replayToken,
            )
            StaleEvidenceNote(diagnostic.staleEvidenceRecordings)
        }

        is MixedErasResult -> {
            MusicalAgeMixedCard(
                result = result,
                onDownload = onDownload,
                onCopyLink = onCopyLink,
            )
            StaleEvidenceNote(diagnostic.staleEvidenceRecordings)
        }

        is NoHistoryResult -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.musical_age_no_history_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(stringResource(R.string.musical_age_no_history_body))
                StaleEvidenceNote(diagnostic.staleEvidenceRecordings)
                NeutralActions(onDownload, onCopyLink)
            }
        }

        is InsufficientHistoryResult -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.musical_age_insufficient_history_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(stringResource(R.string.musical_age_insufficient_history_body))
                UnmetRequirements(diagnostic.unmetRequirements)
                StaleEvidenceNote(diagnostic.staleEvidenceRecordings)
                NeutralActions(onDownload, onCopyLink)
            }
        }

        is InsufficientMetadataResult -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.musical_age_insufficient_metadata_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(stringResource(R.string.musical_age_insufficient_metadata_body))
                UnmetRequirements(diagnostic.unmetRequirements)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.musical_age_counts,
                        diagnostic.acceptedYearRecordings,
                        diagnostic.yearCandidateRecordings,
                        diagnostic.conflictingYearRecordings,
                        diagnostic.unconfirmedContentRecordings,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                StaleEvidenceNote(diagnostic.staleEvidenceRecordings)
                NeutralActions(onDownload, onCopyLink)
            }
        }
    }
}

@Composable
private fun NeutralActions(
    onDownload: () -> Unit,
    onCopyLink: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onDownload,
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(R.string.musical_age_card_download))
        }
        TextButton(onClick = onCopyLink) {
            Text(stringResource(R.string.musical_age_card_copy_link))
        }
    }
}

@Composable
private fun StaleEvidenceNote(count: Int) {
    if (count <= 0) return
    Text(
        text = stringResource(R.string.musical_age_stale, count),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun DetailsSection(result: MusicalAgeResult) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(
                stringResource(
                    if (expanded) {
                        R.string.musical_age_details_hide
                    } else {
                        R.string.musical_age_details_show
                    }
                )
            )
        }
        if (expanded) {
            val diagnostic = result.diagnostic
            Text(stringResource(R.string.musical_age_details_title))
            Text(
                text = stringResource(
                    R.string.musical_age_details_method,
                    diagnostic.algorithmVersion,
                    diagnostic.periodStart.format(DATE_FORMAT),
                    diagnostic.periodEnd.format(DATE_FORMAT),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(
                    R.string.musical_age_details_events,
                    diagnostic.totalEventsRead,
                    diagnostic.eligibleEvents,
                    diagnostic.distinctRecordings,
                    diagnostic.activeDays,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(
                    R.string.musical_age_details_coverage,
                    diagnostic.coverage * 100.0,
                    diagnostic.acceptedYearRecordings,
                    diagnostic.unknownContentRecordings,
                    diagnostic.nonMusicContentRecordings,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(
                    R.string.musical_age_details_evidence,
                    diagnostic.yearCandidateRecordings,
                    diagnostic.conflictingYearRecordings,
                    diagnostic.missingYearRecordings,
                    diagnostic.yearTransientErrorRecordings,
                    diagnostic.unverifiedYearRecordings,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(
                    R.string.musical_age_details_limits,
                    diagnostic.staleEvidenceRecordings,
                    diagnostic.invalidIdentityRecordings,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun UnmetRequirements(items: List<UnmetRequirement>) {
    if (items.isEmpty()) return
    Spacer(Modifier.height(8.dp))
    items.forEach { item ->
        val text = when (item.requirement) {
            MusicalAgeRequirement.ELIGIBLE_RECORDINGS -> stringResource(
                R.string.musical_age_unmet_recordings,
                item.actual.roundToInt(),
                item.minimum.roundToInt(),
            )
            MusicalAgeRequirement.ACTIVE_DAYS -> stringResource(
                R.string.musical_age_unmet_days,
                item.actual.roundToInt(),
                item.minimum.roundToInt(),
            )
            MusicalAgeRequirement.WEIGHTED_MINUTES -> stringResource(
                R.string.musical_age_unmet_minutes,
                item.actual.roundToInt(),
                item.minimum.roundToInt(),
            )
            MusicalAgeRequirement.DATED_RECORDINGS -> stringResource(
                R.string.musical_age_unmet_dated,
                item.actual.roundToInt(),
                item.minimum.roundToInt(),
            )
            MusicalAgeRequirement.COVERAGE -> stringResource(
                R.string.musical_age_unmet_coverage,
                item.actual.roundToInt(),
                item.minimum.roundToInt(),
            )
            MusicalAgeRequirement.WINDOW_CONCENTRATION -> stringResource(
                R.string.musical_age_unmet_concentration,
                item.actual.roundToInt(),
                item.minimum.roundToInt(),
            )
            MusicalAgeRequirement.NON_OVERLAPPING_MARGIN -> stringResource(
                R.string.musical_age_unmet_margin,
                item.actual.roundToInt(),
                item.minimum.roundToInt(),
            )
        }
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TrackRow(
    track: MusicalAgeTrack,
    selected: Boolean,
    manual: List<ManualEvidence>,
    onToggleSelection: () -> Unit,
    onConfirmMusic: () -> Unit,
    onConfirmNonMusic: () -> Unit,
    onConfirmYear: (Int, YearEvidencePrecision, RecordingVersion, String?) -> Unit,
    onRemove: () -> Unit,
) {
    val manualYear = manual.firstOrNull { it.year != null }
    val manualContent = manual.firstOrNull { it.contentState != null }
    var yearText by rememberSaveable(
        track.songId,
        manualYear?.year,
        manualYear?.resolvedVersion,
    ) {
        mutableStateOf(manualYear?.year?.toString().orEmpty())
    }
    var version by remember(
        track.songId,
        manualYear?.year,
        manualYear?.resolvedVersion,
    ) {
        mutableStateOf(manualYear?.resolvedVersion ?: RecordingVersion.UNQUALIFIED)
    }
    var versionMenu by remember { mutableStateOf(false) }
    val selectDescription = stringResource(R.string.musical_age_select_track)
    val selectWithTitle = "$selectDescription: ${track.title}"

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelection() },
                modifier = Modifier.semantics {
                    contentDescription = selectWithTitle
                },
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = track.title.ifBlank { track.songId },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = track.artists.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.musical_age_track_events,
                        track.eventCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = contentStatusText(track.contentState) + " · " +
                        yearStatusText(track.yearState, track.year, track.stale),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (manualContent != null || manualYear != null) {
                    Text(
                        text = stringResource(R.string.musical_age_confirmed_by_you) +
                            (manualYear?.year?.let { ": $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            TextButton(onClick = onConfirmMusic) {
                Text(stringResource(R.string.musical_age_confirm_music))
            }
            TextButton(onClick = onConfirmNonMusic) {
                Text(stringResource(R.string.musical_age_confirm_non_music))
            }
            if (manual.isNotEmpty()) {
                TextButton(onClick = onRemove) {
                    Text(stringResource(R.string.musical_age_remove_confirmation))
                }
            }
        }
        OutlinedTextField(
            value = yearText,
            onValueChange = { value ->
                yearText = value.filter { it.isDigit() }.take(4)
            },
            label = { Text(stringResource(R.string.musical_age_year_label)) },
            supportingText = { Text(stringResource(R.string.musical_age_year_only_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { versionMenu = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(versionText(version))
                }
                DropdownMenu(
                    expanded = versionMenu,
                    onDismissRequest = { versionMenu = false },
                ) {
                    VERSION_OPTIONS.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(versionText(option)) },
                            onClick = {
                                version = option
                                versionMenu = false
                            },
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    onConfirmYear(
                        yearText.toIntOrNull() ?: -1,
                        // Only a year is collected; month/day precision is
                        // never invented by the UI.
                        YearEvidencePrecision.YEAR_ONLY,
                        version,
                        manualYear?.canonicalRecordingId,
                    )
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.musical_age_confirm_year))
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun EnrichmentSection(
    state: MusicalAgeUiState,
    onConsult: () -> Unit,
    onCancel: () -> Unit,
    onOffline: () -> Unit,
) {
    Column(Modifier.padding(16.dp)) {
        HorizontalDivider()
        Text(
            text = stringResource(R.string.musical_age_enrichment_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = stringResource(R.string.musical_age_network_off_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.selected.isNotEmpty()) {
            Text(
                text = stringResource(
                    R.string.musical_age_selected_count,
                    state.selected.size,
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            state.selectedTracks.forEach { track ->
                Text(
                    text = buildString {
                        append(track.title)
                        if (track.artists.isNotEmpty()) {
                            append(" — ")
                            append(track.artists.joinToString(", "))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = stringResource(R.string.musical_age_metadata_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onConsult,
                    enabled = !state.enrichmentLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.musical_age_consult_selected))
                }
                OutlinedButton(
                    onClick = onOffline,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.musical_age_continue_offline))
                }
            }
        }
        if (state.enrichmentLoading) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = progressText(state.progress),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onCancel) {
                Text(stringResource(R.string.musical_age_cancel))
            }
        }
        state.enrichment?.let { snapshot ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = enrichmentSummary(snapshot),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.musical_age_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.musical_age_dialog_cancel))
            }
        },
    )
}

@Composable
private fun messageText(message: MusicalAgeMessage): String = stringResource(
    when (message) {
        MusicalAgeMessage.SAVED -> R.string.musical_age_msg_saved
        MusicalAgeMessage.INVALID_YEAR -> R.string.musical_age_msg_invalid_year
        MusicalAgeMessage.SAVE_FAILED -> R.string.musical_age_msg_save_failed
        MusicalAgeMessage.SELECTION_LIMIT -> R.string.musical_age_msg_selection_limit
        MusicalAgeMessage.SELECTION_REQUIRED -> R.string.musical_age_msg_selection_required
        MusicalAgeMessage.CONFIRM_REMOVE -> R.string.musical_age_remove_confirmation
        MusicalAgeMessage.CANCELLED -> R.string.musical_age_msg_cancelled
    }
)

@Composable
private fun contentStatusText(state: ContentEvidenceState): String = stringResource(
    when (state) {
        ContentEvidenceState.CONFIRMED_MUSIC -> R.string.musical_age_content_music
        ContentEvidenceState.CONFIRMED_NON_MUSIC -> R.string.musical_age_content_non_music
        ContentEvidenceState.CONFLICT -> R.string.musical_age_content_conflict
        ContentEvidenceState.UNKNOWN -> R.string.musical_age_content_unknown
    }
)

@Composable
private fun yearStatusText(
    state: YearEvidenceState,
    year: Int?,
    stale: Boolean,
): String {
    val base = stringResource(
        when (state) {
            YearEvidenceState.ACCEPTED -> R.string.musical_age_year_accepted
            YearEvidenceState.CANDIDATE -> R.string.musical_age_year_candidate
            YearEvidenceState.CONFLICT -> R.string.musical_age_year_conflict
            YearEvidenceState.MISSING -> R.string.musical_age_year_missing
            YearEvidenceState.TRANSIENT_ERROR -> R.string.musical_age_year_transient
            YearEvidenceState.UNVERIFIED -> R.string.musical_age_year_unverified
        }
    )
    val withYear = year?.let { "$base ($it)" } ?: base
    return if (stale) "$withYear · ${stringResource(R.string.musical_age_stale_short)}" else withYear
}

@Composable
private fun versionText(version: RecordingVersion): String = stringResource(
    when (version) {
        RecordingVersion.STUDIO -> R.string.musical_age_version_studio
        RecordingVersion.UNQUALIFIED -> R.string.musical_age_version_unqualified
        RecordingVersion.LIVE -> R.string.musical_age_version_live
        RecordingVersion.REMIX -> R.string.musical_age_version_remix
        RecordingVersion.COVER -> R.string.musical_age_version_cover
        RecordingVersion.ACOUSTIC -> R.string.musical_age_version_acoustic
        RecordingVersion.INSTRUMENTAL -> R.string.musical_age_version_instrumental
        RecordingVersion.DEMO -> R.string.musical_age_version_demo
        RecordingVersion.KARAOKE -> R.string.musical_age_version_karaoke
        RecordingVersion.RADIO_EDIT -> R.string.musical_age_version_radio_edit
        RecordingVersion.EXTENDED -> R.string.musical_age_version_extended
        RecordingVersion.RERECORDING -> R.string.musical_age_version_rerecording
        RecordingVersion.UNKNOWN -> R.string.musical_age_version_unknown
    }
)

@Composable
private fun progressText(progress: LookupProgress?): String =
    if (progress == null) {
        stringResource(R.string.musical_age_loading)
    } else {
        stringResource(
            R.string.musical_age_progress,
            progress.completedSongs,
            progress.selectedSongs,
            progress.requestsAttempted,
        )
    }

@Composable
private fun enrichmentSummary(snapshot: EnrichmentSnapshot): String = stringResource(
    R.string.musical_age_enrichment_done,
    snapshot.diagnostic.requestsAttempted,
    snapshot.diagnostic.acceptedYears,
    snapshot.diagnostic.candidateYears,
    snapshot.diagnostic.conflictedYears,
    snapshot.diagnostic.staleEvidenceUsed,
) + if (
    snapshot.stoppedReason == EnrichmentStopReason.DEFERRED_RETRY_AFTER ||
    snapshot.stoppedReason == EnrichmentStopReason.BUDGET_EXHAUSTED
) {
    " · " + stringResource(R.string.musical_age_enrichment_failed)
} else {
    ""
}

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

private val VERSION_OPTIONS = listOf(
    RecordingVersion.UNQUALIFIED,
    RecordingVersion.STUDIO,
    RecordingVersion.LIVE,
    RecordingVersion.REMIX,
    RecordingVersion.COVER,
    RecordingVersion.ACOUSTIC,
    RecordingVersion.INSTRUMENTAL,
    RecordingVersion.DEMO,
    RecordingVersion.KARAOKE,
    RecordingVersion.RADIO_EDIT,
    RecordingVersion.EXTENDED,
    RecordingVersion.RERECORDING,
)

