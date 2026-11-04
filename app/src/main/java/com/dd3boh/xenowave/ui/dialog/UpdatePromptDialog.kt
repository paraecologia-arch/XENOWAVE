/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.ui.dialog

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.update.UpdateFailure
import com.dd3boh.xenowave.update.UpdateInfo
import com.dd3boh.xenowave.update.UpdateInstallCoordinator
import com.dd3boh.xenowave.update.UpdateInstaller
import com.dd3boh.xenowave.update.UpdatePhase
import com.dd3boh.xenowave.update.UpdatePolicy
import com.dd3boh.xenowave.update.UpdatePrompt
import com.dd3boh.xenowave.update.UpdatePromptController
import com.dd3boh.xenowave.update.UpdateScheduler
import com.dd3boh.xenowave.update.UpdateStatus
import com.dd3boh.xenowave.update.UpdateStatusStore
import com.dd3boh.xenowave.utils.dataStore
import kotlinx.coroutines.flow.map
import java.util.Locale

/**
 * Global in-app update notice.
 *
 * It is intentionally rendered by MainActivity, not by a single screen, so the
 * notice follows the user while navigation and playback continue normally.
 */
@Composable
fun UpdatePromptHost(enabled: Boolean = true) {
    if (!enabled) return
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prompt by UpdatePromptController.prompt.collectAsState()
    val status by remember {
        context.dataStore.data.map { UpdateStatusStore.read(it) }
    }.collectAsState(initial = UpdateStatusStore.read(mutablePreferencesOf()))
    val blockingCount by ModalGate.blockingCount.collectAsState()
    val installer = remember { UpdateInstaller(context) }
    var canInstall by remember { mutableStateOf(installer.canRequestPackageInstalls()) }

    DisposableEffect(lifecycleOwner, installer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canInstall = installer.canRequestPackageInstalls()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(
        status.updateAvailable,
        status.availableVersionCode,
        status.apkSha256,
        status.apkSizeBytes,
        status.phase,
        status.consentVersionCode,
        status.consentApkSha256,
    ) {
        UpdatePromptController.consider(context, status)
    }

    val current = prompt ?: return
    if (blockingCount > 0) return
    if (!status.updateAvailable || status.availableVersionCode != current.versionCode) return

    val consent = UpdatePolicy.hasConsent(
        status = status,
        versionCode = current.versionCode,
        sha256 = current.sha256,
        applicationId = UpdateInfo.applicationId,
    )

    when {
        !consent -> UpdateQuestionDialog(current)
        status.phase == UpdatePhase.PENDING_USER_ACTION -> UpdatePendingDialog(current)

        status.phase == UpdatePhase.READY_TO_INSTALL -> UpdateReadyDialog(
            prompt = current,
            canInstall = canInstall,
        )

        status.phase == UpdatePhase.FAILED -> UpdateFailureDialog(current, status)
        status.phase == UpdatePhase.DOWNLOADING -> UpdateProgressDialog(current, status)
        status.phase == UpdatePhase.CHECKING -> UpdateCheckingDialog(current)
        status.phase == UpdatePhase.WAITING_FOR_MUSIC -> UpdateMusicDialog(current)

        status.phase == UpdatePhase.INSTALLING -> UpdateInstallingDialog(current)
        else -> UpdateWaitingDialog(current)
    }
}

@Composable
private fun UpdateQuestionDialog(prompt: UpdatePrompt) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { UpdatePromptController.decline(context, prompt) },
        title = { Text(stringResource(R.string.update_prompt_title)) },
        text = {
            Column {
                Text(
                    stringResource(
                        R.string.update_prompt_message,
                        prompt.installedVersionName,
                        prompt.installedVersionCode,
                        prompt.versionName ?: "#${prompt.versionCode}",
                        prompt.versionCode,
                    )
                )
                prompt.sizeBytes?.let { size ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.update_prompt_size,
                            formatSize(size),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { UpdatePromptController.approve(context, prompt) }
            ) {
                Text(stringResource(R.string.update_prompt_update))
            }
        },
        dismissButton = {
            TextButton(
                onClick = { UpdatePromptController.decline(context, prompt) }
            ) {
                Text(stringResource(R.string.update_prompt_not_now))
            }
        },
    )
}

@Composable
private fun UpdateCheckingDialog(prompt: UpdatePrompt) {
    AlertDialog(
        onDismissRequest = { UpdatePromptController.hide(prompt) },
        title = { Text(stringResource(R.string.update_prompt_preparing)) },
        text = { Text(stringResource(R.string.update_prompt_waiting_text)) },
        confirmButton = {
            TextButton(onClick = { UpdatePromptController.hide(prompt) }) {
                Text(stringResource(R.string.update_prompt_hide))
            }
        },
    )
}

@Composable
private fun UpdateProgressDialog(prompt: UpdatePrompt, status: UpdateStatus) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { UpdatePromptController.hide(prompt) },
        title = { Text(stringResource(R.string.update_prompt_downloading_title)) },
        text = {
            Column {
                Text(
                    stringResource(
                        R.string.update_prompt_downloading_text,
                        prompt.versionName ?: "#${prompt.versionCode}",
                        status.progress.coerceIn(0, 100),
                    )
                )
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { status.progress.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { UpdatePromptController.hide(prompt) }) {
                Text(stringResource(R.string.update_prompt_hide))
            }
        },
        dismissButton = {
            TextButton(
                onClick = { UpdatePromptController.cancel(context, prompt.versionCode) }
            ) {
                Text(stringResource(R.string.update_prompt_cancel))
            }
        },
    )
}

@Composable
private fun UpdateReadyDialog(prompt: UpdatePrompt, canInstall: Boolean) {
    val context = LocalContext.current
    val installer = remember { UpdateInstaller(context) }
    AlertDialog(
        onDismissRequest = { UpdatePromptController.hide(prompt) },
        title = {
            Text(
                stringResource(
                    if (canInstall) {
                        R.string.update_prompt_install_ready_title
                    } else {
                        R.string.update_prompt_ready_title
                    }
                )
            )
        },
        text = {
            Text(
                stringResource(
                    if (canInstall) {
                        R.string.update_prompt_install_ready_text
                    } else {
                        R.string.update_prompt_ready_text
                    }
                )
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (canInstall) {
                        UpdateScheduler.installNow(context)
                    } else {
                        runCatching { context.startActivity(installer.settingsIntent()) }
                    }
                }
            ) {
                Text(
                    stringResource(
                        if (canInstall) {
                            R.string.update_install_now
                        } else {
                            R.string.update_prompt_allow
                        }
                    )
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = { UpdatePromptController.cancel(context, prompt.versionCode) }
            ) {
                Text(stringResource(R.string.update_prompt_cancel))
            }
        },
    )
}

@Composable
private fun UpdatePendingDialog(prompt: UpdatePrompt) {
    val context = LocalContext.current
    val pending = UpdateInstallCoordinator.pending.collectAsState().value
    AlertDialog(
        onDismissRequest = { UpdatePromptController.hide(prompt) },
        title = { Text(stringResource(R.string.update_prompt_confirm_title)) },
        text = {
            Text(
                stringResource(
                    R.string.update_prompt_confirm_text,
                    prompt.versionName ?: "#${prompt.versionCode}",
                )
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val intent = pending?.intent
                    if (intent != null) {
                        runCatching {
                            context.startActivity(
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                        UpdateInstallCoordinator.consume()
                    } else {
                        UpdateScheduler.installNow(context)
                    }
                }
            ) {
                Text(stringResource(R.string.update_prompt_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = { UpdatePromptController.cancel(context, prompt.versionCode) }
            ) {
                Text(stringResource(R.string.update_prompt_cancel))
            }
        },
    )
}

@Composable
private fun UpdateMusicDialog(prompt: UpdatePrompt) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { UpdatePromptController.hide(prompt) },
        title = { Text(stringResource(R.string.update_waiting_music_title)) },
        text = { Text(stringResource(R.string.update_waiting_music_text)) },
        confirmButton = {
            TextButton(onClick = { UpdatePromptController.retry(context) }) {
                Text(stringResource(R.string.update_prompt_retry))
            }
        },
        dismissButton = {
            TextButton(onClick = { UpdatePromptController.hide(prompt) }) {
                Text(stringResource(R.string.update_prompt_hide))
            }
        },
    )
}

@Composable
private fun UpdateInstallingDialog(prompt: UpdatePrompt) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { UpdatePromptController.hide(prompt) },
        title = { Text(stringResource(R.string.update_prompt_installing_title)) },
        text = { Text(stringResource(R.string.update_prompt_installing_text)) },
        confirmButton = {
            TextButton(onClick = { UpdatePromptController.hide(prompt) }) {
                Text(stringResource(R.string.update_prompt_hide))
            }
        },
        dismissButton = {
            TextButton(
                onClick = { UpdatePromptController.cancel(context, prompt.versionCode) }
            ) {
                Text(stringResource(R.string.update_prompt_cancel))
            }
        },
    )
}

@Composable
private fun UpdateWaitingDialog(prompt: UpdatePrompt) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { UpdatePromptController.hide(prompt) },
        title = { Text(stringResource(R.string.update_prompt_waiting_title)) },
        text = { Text(stringResource(R.string.update_prompt_waiting_text)) },
        confirmButton = {
            TextButton(onClick = { UpdatePromptController.retry(context) }) {
                Text(stringResource(R.string.update_prompt_retry))
            }
        },
        dismissButton = {
            TextButton(onClick = { UpdatePromptController.hide(prompt) }) {
                Text(stringResource(R.string.update_prompt_hide))
            }
        },
    )
}

@Composable
private fun UpdateFailureDialog(prompt: UpdatePrompt, status: UpdateStatus) {
    val context = LocalContext.current
    val message = when (status.error) {
        UpdateFailure.NETWORK -> R.string.update_network_error
        UpdateFailure.RATE_LIMITED -> R.string.update_rate_limited
        UpdateFailure.SERVER -> R.string.update_server_error
        UpdateFailure.INVALID_METADATA -> R.string.update_invalid
        UpdateFailure.INSTALL -> R.string.update_error_install
        UpdateFailure.STORAGE -> R.string.update_storage_error
        null -> R.string.update_prompt_failed_text
    }
    AlertDialog(
        onDismissRequest = { UpdatePromptController.hide(prompt) },
        title = { Text(stringResource(R.string.update_prompt_failed_title)) },
        text = { Text(stringResource(message)) },
        confirmButton = {
            TextButton(onClick = { UpdatePromptController.retry(context) }) {
                Text(stringResource(R.string.update_prompt_retry))
            }
        },
        dismissButton = {
            TextButton(
                onClick = { UpdatePromptController.cancel(context, prompt.versionCode) }
            ) {
                Text(stringResource(R.string.update_prompt_cancel))
            }
        },
    )
}

private fun formatSize(bytes: Long): String =
    String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
