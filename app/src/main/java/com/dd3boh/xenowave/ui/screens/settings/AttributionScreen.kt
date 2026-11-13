/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.constants.TopBarInsets
import com.dd3boh.xenowave.ui.component.ColumnWithContentPadding
import com.dd3boh.xenowave.ui.component.ContributorCard
import com.dd3boh.xenowave.ui.component.ContributorInfo
import com.dd3boh.xenowave.ui.component.ContributorType.CUSTOM
import com.dd3boh.xenowave.ui.component.PreferenceEntry
import com.dd3boh.xenowave.ui.component.button.IconButton
import com.dd3boh.xenowave.ui.utils.backToMain

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttributionScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        CreditSectionHeader(stringResource(R.string.xenowave_creation_section))

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.xenowave_creation_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp)
            )
            ContributorCard(
                contributor = ContributorInfo(
                    name = stringResource(R.string.xenowave_creator_name),
                    type = listOf(CUSTOM),
                    description = stringResource(R.string.xenowave_creator_role),
                    url = XENOWAVE_REPOSITORY_URL
                )
            )
            ContributorCard(
                contributor = ContributorInfo(
                    name = stringResource(R.string.xenowave_ai_name),
                    type = listOf(CUSTOM),
                    description = stringResource(R.string.xenowave_ai_role)
                ),
                descriptionColour = MaterialTheme.colorScheme.secondary
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        CreditSectionHeader(stringResource(R.string.xenowave_base_section))

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.xenowave_base_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp)
            )
            baseProjectContributors().forEach {
                ContributorCard(
                    contributor = it,
                    descriptionColour = MaterialTheme.colorScheme.secondary
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            ContributorCard(
                contributor = ContributorInfo(
                    name = stringResource(R.string.xenowave_contributors_xenowave),
                    type = listOf(CUSTOM),
                    description = "GitHub",
                    url = "$XENOWAVE_REPOSITORY_URL/graphs/contributors"
                )
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            ContributorCard(
                contributor = ContributorInfo(
                    name = stringResource(R.string.xenowave_contributors_outertune),
                    type = listOf(CUSTOM),
                    description = "GitHub",
                    url = OUTERTUNE_CONTRIBUTORS_URL
                )
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.oss_licenses_title)) },
                description = stringResource(R.string.xenowave_licenses_note),
                onClick = {
                    navController.navigate("settings/about/oss_licenses")
                }
            )
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.xenowave_credits_title)) },
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

@Composable
private fun CreditSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp)
    )
}

@Composable
private fun baseProjectContributors(): List<ContributorInfo> {
    val outertuneMaintainer = stringResource(R.string.xenowave_outertune_maintainer_role)
    return listOf(
        ContributorInfo(
            name = "Davide Garberi",
            alias = "DD3Boh",
            type = listOf(CUSTOM),
            description = outertuneMaintainer,
            url = "https://github.com/DD3Boh"
        ),
        ContributorInfo(
            name = "Michael Zh",
            alias = "mikooomich",
            type = listOf(CUSTOM),
            description = outertuneMaintainer,
            url = "https://github.com/mikooomich"
        ),
        ContributorInfo(
            name = "Zion Huang",
            alias = "z-huang",
            type = listOf(CUSTOM),
            description = stringResource(R.string.xenowave_innertune_creator_role),
            url = "https://github.com/z-huang"
        ),
    )
}

private const val XENOWAVE_REPOSITORY_URL = "https://github.com/paraecologia-arch/XENOWAVE"
private const val OUTERTUNE_CONTRIBUTORS_URL = "https://github.com/OuterTune/OuterTune/graphs/contributors"
