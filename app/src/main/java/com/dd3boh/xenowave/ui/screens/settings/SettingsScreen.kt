/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 O﻿ute﻿rTu﻿ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.xenowave.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.constants.TopBarInsets
import com.dd3boh.xenowave.ui.component.ColumnWithContentPadding
import com.dd3boh.xenowave.ui.component.PreferenceEntry
import com.dd3boh.xenowave.ui.component.button.IconButton
import com.dd3boh.xenowave.ui.utils.backToMain

val SETTINGS_TAG = "Settings"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.grp_account_sync)) },
                icon = { com.dd3boh.xenowave.ui.icons.XenoIcon(icon = com.dd3boh.xenowave.ui.icons.XenoSystemIcons.AccountCircle, contentDescription = null) },
                onClick = { navController.navigate("settings/account_sync") }
            )
            PreferenceEntry(
                title = { Text(stringResource(R.string.grp_library_and_content)) },
                icon = { com.dd3boh.xenowave.ui.icons.XenoIcon(icon = com.dd3boh.xenowave.ui.icons.XenoIcons.AutoMirrored.LibraryBooks, contentDescription = null) },
                onClick = { navController.navigate("settings/library") }
            )
            PreferenceEntry(
                title = { Text(stringResource(R.string.local_player_settings_title)) },
                icon = { com.dd3boh.xenowave.ui.icons.XenoIcon(icon = com.dd3boh.xenowave.ui.icons.XenoSystemIcons.SdCard, contentDescription = null) },
                onClick = { navController.navigate("settings/local") }
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.appearance)) },
                icon = { com.dd3boh.xenowave.ui.icons.XenoIcon(icon = com.dd3boh.xenowave.ui.icons.XenoSystemIcons.Palette, contentDescription = null) },
                onClick = { navController.navigate("settings/appearance") }
            )
            PreferenceEntry(
                title = { Text(stringResource(R.string.grp_interface)) },
                icon = { com.dd3boh.xenowave.ui.icons.XenoIcon(icon = com.dd3boh.xenowave.ui.icons.XenoLibraryIcons.Interests, contentDescription = null) },
                onClick = { navController.navigate("settings/interface") }
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.player_and_audio)) },
                icon = { com.dd3boh.xenowave.ui.icons.XenoIcon(icon = com.dd3boh.xenowave.ui.icons.XenoPlayerIcons.PlayArrow, contentDescription = null) },
                onClick = { navController.navigate("settings/player") }
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.backup_restore)) },
                icon = { com.dd3boh.xenowave.ui.icons.XenoIcon(icon = com.dd3boh.xenowave.ui.icons.XenoSystemIcons.Restore, contentDescription = null) },
                onClick = { navController.navigate("settings/backup_restore") }
            )
            PreferenceEntry(
                title = { Text(stringResource(R.string.storage)) },
                icon = { com.dd3boh.xenowave.ui.icons.XenoIcon(icon = com.dd3boh.xenowave.ui.icons.XenoSystemIcons.Storage, contentDescription = null) },
                onClick = { navController.navigate("settings/storage") }
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.update_settings_title)) },
                icon = { com.dd3boh.xenowave.ui.icons.XenoIcon(icon = com.dd3boh.xenowave.ui.icons.XenoSystemIcons.Autorenew, contentDescription = null) },
                onClick = { navController.navigate("settings/updates") }
            )
            PreferenceEntry(
                title = { Text(stringResource(R.string.experimental_settings_title)) },
                icon = { com.dd3boh.xenowave.ui.icons.XenoIcon(icon = com.dd3boh.xenowave.ui.icons.XenoSystemIcons.WarningAmber, contentDescription = null) },
                onClick = { navController.navigate("settings/experimental") }
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.about)) },
                icon = { com.dd3boh.xenowave.ui.icons.XenoIcon(icon = com.dd3boh.xenowave.ui.icons.XenoSystemIcons.Info, contentDescription = null) },
                onClick = { navController.navigate("settings/about") }
            )
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.settings)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                com.dd3boh.xenowave.ui.icons.XenoIcon(
                    icon = com.dd3boh.xenowave.ui.icons.XenoIcons.AutoMirrored.ArrowBack,
                    contentDescription = null
                )
            }
        },
        windowInsets = TopBarInsets,
        scrollBehavior = scrollBehavior
    )
}
