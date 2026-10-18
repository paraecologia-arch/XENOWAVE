/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.xenowave.ui.screens.settings.fragments

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.constants.SlimNavBarKey
import com.dd3boh.xenowave.ui.component.SwitchPreference
import com.dd3boh.xenowave.utils.rememberPreference

@Composable
fun ColumnScope.AppearanceMiscFrag() {
    val (slimNav, onSlimNavChange) = rememberPreference(SlimNavBarKey, defaultValue = false)

    SwitchPreference(
        title = { Text(stringResource(R.string.slim_navbar_title)) },
        description = stringResource(R.string.slim_navbar_description),
        icon = { com.dd3boh.xenowave.ui.icons.XenoIcon(icon = com.dd3boh.xenowave.ui.icons.XenoActionIcons.MoreHoriz, contentDescription = null) },
        checked = slimNav,
        onCheckedChange = onSlimNavChange
    )
}
