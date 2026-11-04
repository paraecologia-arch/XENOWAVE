/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
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
import androidx.navigation.NavController
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.constants.AutoUpdateEnabledKey
import com.dd3boh.xenowave.constants.TopBarInsets
import com.dd3boh.xenowave.constants.UpdateChannelKey
import com.dd3boh.xenowave.ui.component.ColumnWithContentPadding
import com.dd3boh.xenowave.ui.component.PreferenceGroupTitle
import com.dd3boh.xenowave.ui.component.ListPreference
import com.dd3boh.xenowave.ui.component.SwitchPreference
import com.dd3boh.xenowave.ui.component.button.IconButton
import com.dd3boh.xenowave.ui.icons.XenoIcon
import com.dd3boh.xenowave.ui.icons.XenoIcons
import com.dd3boh.xenowave.ui.icons.XenoSystemIcons
import com.dd3boh.xenowave.ui.utils.backToMain
import com.dd3boh.xenowave.update.UpdateChannel
import com.dd3boh.xenowave.update.UpdateFailure
import com.dd3boh.xenowave.update.UpdateInfo
import com.dd3boh.xenowave.update.UpdateInstallCoordinator
import com.dd3boh.xenowave.update.UpdateInstaller
import com.dd3boh.xenowave.update.UpdateNotifications
import com.dd3boh.xenowave.update.UpdatePhase
import com.dd3boh.xenowave.update.UpdatePolicy
import com.dd3boh.xenowave.update.UpdatePromptController
import com.dd3boh.xenowave.update.UpdateScheduler
import com.dd3boh.xenowave.update.UpdateStatus
import com.dd3boh.xenowave.update.UpdateStatusStore
import com.dd3boh.xenowave.utils.dataStore
import com.dd3boh.xenowave.utils.rememberPreference
import kotlinx.coroutines.flow.map
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSettingsScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val installer = remember { UpdateInstaller(context) }

    val supported = UpdateInfo.isSupported
    val installedVersionName = remember { UpdateInfo.installedVersionName(context) }
    val installedVersionCode = remember { UpdateInfo.installedVersionCode(context) }

    val (autoUpdateEnabled, setAutoUpdateEnabled) =
        rememberPreference(AutoUpdateEnabledKey, defaultValue = true)
    val (channelName, setChannelName) =
        rememberPreference(UpdateChannelKey, defaultValue = UpdateChannel.BETA.name)
    val channel = UpdateChannel.fromString(channelName) ?: UpdateChannel.BETA

    val status by remember {
        context.dataStore.data.map { UpdateStatusStore.read(it) }
    }.collectAsState(initial = UpdateStatusStore.read(mutablePreferencesOf()))

    var canRequestInstalls by remember { mutableStateOf(installer.canRequestPackageInstalls()) }
    var canPostNotifications by remember { mutableStateOf(UpdateNotifications.canPostNotifications(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canRequestInstalls = installer.canRequestPackageInstalls()
                canPostNotifications = UpdateNotifications.canPostNotifications(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val pendingConfirmation by UpdateInstallCoordinator.pending.collectAsState()
    LaunchedEffect(pendingConfirmation) {
        val pending = pendingConfirmation ?: return@LaunchedEffect
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            runCatching {
                context.startActivity(
                    pending.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            UpdateInstallCoordinator.consume()
        }
    }

    // The package can be replaced even if the old process never saw the result.
    LaunchedEffect(installedVersionCode, status.availableVersionCode) {
        val available = status.availableVersionCode ?: return@LaunchedEffect
        if (installedVersionCode >= available) {
            UpdateStatusStore.clearPendingVersion(context)
            UpdateStatusStore.clearAvailable(context)
            UpdateStatusStore.setPhase(context, UpdatePhase.INSTALLED)
        }
    }

    val busy = status.phase == UpdatePhase.CHECKING ||
        status.phase == UpdatePhase.DOWNLOADING ||
        status.phase == UpdatePhase.WAITING_FOR_MUSIC ||
        status.phase == UpdatePhase.INSTALLING ||
        status.phase == UpdatePhase.PENDING_USER_ACTION
    val consent = status.availableVersionCode?.let { versionCode ->
        status.apkSha256?.let { sha256 ->
            UpdatePolicy.hasConsent(
                status = status,
                versionCode = versionCode,
                sha256 = sha256,
                applicationId = UpdateInfo.applicationId,
            )
        }
    } == true

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        PreferenceGroupTitle(title = stringResource(R.string.update_section_status))

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(
                        R.string.update_installed_version,
                        installedVersionName,
                        installedVersionCode
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))

                if (supported) {
                    Text(
                        text = statusText(status),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (status.phase == UpdatePhase.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (status.phase == UpdatePhase.DOWNLOADING) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { status.progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (status.lastCheckMillis > 0L) {
                            stringResource(
                                R.string.update_last_checked,
                                DateFormat.getDateTimeInstance(
                                    DateFormat.SHORT,
                                    DateFormat.SHORT
                                ).format(Date(status.lastCheckMillis))
                            )
                        } else {
                            stringResource(R.string.update_never_checked)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (status.phase == UpdatePhase.PENDING_USER_ACTION && !canPostNotifications) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.update_notifications_disabled),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(notificationSettingsIntent(context))
                                }
                            }
                        ) {
                            Text(stringResource(R.string.update_open_notification_settings))
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { UpdateScheduler.checkNow(context) },
                            enabled = !busy,
                        ) {
                            Text(stringResource(R.string.update_check_now))
                        }
                        if (status.updateAvailable &&
                            status.availableVersionCode != null &&
                            !consent
                        ) {
                            Button(
                                onClick = { UpdatePromptController.approve(context, status) },
                                enabled = !busy,
                            ) {
                                Text(stringResource(R.string.update_prompt_update))
                            }
                        }
                    }

                    if (consent && status.updateAvailable) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (status.phase) {
                                UpdatePhase.IDLE, UpdatePhase.FAILED -> {
                                    Button(
                                        onClick = { UpdatePromptController.retry(context) },
                                        enabled = !busy,
                                    ) {
                                        Text(stringResource(R.string.update_prompt_retry))
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            UpdatePromptController.cancel(
                                                context,
                                                status.availableVersionCode,
                                            )
                                        },
                                    ) {
                                        Text(stringResource(R.string.update_prompt_cancel))
                                    }
                                }

                                UpdatePhase.DOWNLOADING -> {
                                    Button(
                                        onClick = { UpdatePromptController.retry(context) },
                                    ) {
                                        Text(stringResource(R.string.update_prompt_retry))
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            UpdatePromptController.cancel(
                                                context,
                                                status.availableVersionCode,
                                            )
                                        },
                                    ) {
                                        Text(stringResource(R.string.update_prompt_cancel))
                                    }
                                }

                                UpdatePhase.READY_TO_INSTALL -> {
                                    Button(
                                        onClick = { UpdateScheduler.installNow(context) },
                                        enabled = canRequestInstalls,
                                    ) {
                                        Text(stringResource(R.string.update_install_now))
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            UpdatePromptController.cancel(
                                                context,
                                                status.availableVersionCode,
                                            )
                                        },
                                    ) {
                                        Text(stringResource(R.string.update_prompt_cancel))
                                    }
                                }

                                UpdatePhase.PENDING_USER_ACTION -> {
                                    Button(
                                        onClick = {
                                            val pending = pendingConfirmation
                                            if (pending != null) {
                                                runCatching {
                                                    context.startActivity(
                                                        pending.intent.addFlags(
                                                            Intent.FLAG_ACTIVITY_NEW_TASK
                                                        )
                                                    )
                                                }
                                                UpdateInstallCoordinator.consume()
                                            } else {
                                                UpdateScheduler.installNow(context)
                                            }
                                        },
                                    ) {
                                        Text(stringResource(R.string.update_prompt_confirm))
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            UpdatePromptController.cancel(
                                                context,
                                                status.availableVersionCode,
                                            )
                                        },
                                    ) {
                                        Text(stringResource(R.string.update_prompt_cancel))
                                    }
                                }

                                UpdatePhase.WAITING_FOR_MUSIC -> {
                                    Button(
                                        onClick = { UpdatePromptController.retry(context) },
                                    ) {
                                        Text(stringResource(R.string.update_prompt_retry))
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            UpdatePromptController.cancel(
                                                context,
                                                status.availableVersionCode,
                                            )
                                        },
                                    ) {
                                        Text(stringResource(R.string.update_prompt_cancel))
                                    }
                                }

                                UpdatePhase.INSTALLING -> {
                                    OutlinedButton(
                                        onClick = {
                                            UpdatePromptController.cancel(
                                                context,
                                                status.availableVersionCode,
                                            )
                                        },
                                    ) {
                                        Text(stringResource(R.string.update_prompt_cancel))
                                    }
                                }

                                UpdatePhase.CHECKING,
                                UpdatePhase.INSTALLED,
                                -> Unit
                            }
                        }
                    }

                    if (!canRequestInstalls) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                runCatching { context.startActivity(installer.settingsIntent()) }
                            }
                        ) {
                            Text(stringResource(R.string.update_allow_installs))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.update_allow_installs_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.update_unsupported_debug),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        if (supported) {
            PreferenceGroupTitle(title = stringResource(R.string.update_section_settings))

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                SwitchPreference(
                    title = { Text(stringResource(R.string.update_auto_check)) },
                    description = stringResource(R.string.update_auto_check_description),
                    icon = { XenoIcon(XenoSystemIcons.Autorenew, contentDescription = null) },
                    checked = autoUpdateEnabled,
                    onCheckedChange = { enabled ->
                        setAutoUpdateEnabled(enabled)
                        UpdateScheduler.setAutoUpdateEnabled(context, enabled)
                    },
                )
                ListPreference(
                    title = { Text(stringResource(R.string.update_channel)) },
                    icon = { XenoIcon(XenoSystemIcons.DeveloperMode, contentDescription = null) },
                    selectedValue = channel,
                    values = UpdateChannel.entries,
                    valueText = { value ->
                        when (value) {
                            UpdateChannel.BETA -> stringResource(R.string.update_channel_beta)
                            UpdateChannel.STABLE -> stringResource(R.string.update_channel_stable)
                        }
                    },
                    onValueSelected = { value ->
                        setChannelName(value.name)
                        UpdateScheduler.checkNow(context)
                    },
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.update_channel_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.update_manual_download_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.update_settings_title)) },
        navigationIcon = {
            IconButton(
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
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun statusText(status: UpdateStatus): String = when (status.phase) {
    UpdatePhase.CHECKING -> stringResource(R.string.update_checking)
    UpdatePhase.DOWNLOADING ->
        stringResource(R.string.update_downloading, status.progress.coerceIn(0, 100))

    UpdatePhase.READY_TO_INSTALL -> stringResource(R.string.update_ready)
    UpdatePhase.WAITING_FOR_MUSIC -> stringResource(R.string.update_waiting_music_status)
    UpdatePhase.INSTALLING -> stringResource(R.string.update_installing)
    UpdatePhase.PENDING_USER_ACTION -> stringResource(R.string.update_pending_user_action)
    UpdatePhase.INSTALLED -> stringResource(R.string.update_installed)
    UpdatePhase.FAILED -> stringResource(
        when (status.error) {
            UpdateFailure.NETWORK -> R.string.update_network_error
            UpdateFailure.RATE_LIMITED -> R.string.update_rate_limited
            UpdateFailure.SERVER -> R.string.update_server_error
            UpdateFailure.INVALID_METADATA -> R.string.update_invalid
            UpdateFailure.INSTALL -> R.string.update_error_install
            UpdateFailure.STORAGE -> R.string.update_storage_error
            null -> R.string.update_failed
        }
    )

    UpdatePhase.IDLE -> when {
        status.updateAvailable && status.availableVersionName != null ->
            stringResource(R.string.update_available_version, status.availableVersionName)

        status.lastCheckMillis > 0L -> stringResource(R.string.update_up_to_date)
        else -> stringResource(R.string.update_never_checked)
    }
}

private fun notificationSettingsIntent(context: Context): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(
            AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
    }
