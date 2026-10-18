/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.xenowave.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.models.MediaMetadata
import com.dd3boh.xenowave.ui.component.button.IconButton
import com.dd3boh.xenowave.ui.menu.MenuState
import com.dd3boh.xenowave.ui.menu.SelectionMediaMetadataMenu

@Composable
fun RowScope.SelectHeader(
    navController: NavController,
    selectedItems: List<MediaMetadata>,
    totalItemCount: Int,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    menuState: MenuState,
    onDismiss: () -> Unit = {},
    onRemoveFromHistory: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 2.dp, bottom = 3.dp)
    ) {
        Text(
            text = "${selectedItems.size}/${
                pluralStringResource(
                    R.plurals.n_selected,
                    totalItemCount,
                    totalItemCount,
                )
            }",
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1f, false)
        )

        // option menu
        IconButton(
            onClick = {
                menuState.show {
                    SelectionMediaMetadataMenu(
                        navController = navController,
                        selection = selectedItems,
                        onDismiss = menuState::dismiss,
                        clearAction = onDeselectAll,
                        onRemoveFromHistory = onRemoveFromHistory,
                        onRemoveFromPlaylist = onRemoveFromPlaylist,
                    )
                }
            }
        ) {
            com.dd3boh.xenowave.ui.icons.XenoIcon(
                icon = com.dd3boh.xenowave.ui.icons.XenoActionIcons.MoreVert,
                contentDescription = stringResource(R.string.options),
                tint = LocalContentColor.current
            )
        }

        // select/deselect all
        val allSelected = selectedItems.size < totalItemCount

        IconButton(
            onClick = if (allSelected) onSelectAll else onDeselectAll
        ) {
            com.dd3boh.xenowave.ui.icons.XenoIcon(
                icon = if (allSelected) com.dd3boh.xenowave.ui.icons.XenoSystemIcons.SelectAll else com.dd3boh.xenowave.ui.icons.XenoSystemIcons.Deselect,
                contentDescription = stringResource(
                    if (allSelected) R.string.select_all else R.string.deselect_all
                ),
                tint = LocalContentColor.current
            )
        }

        // close selection mode
        IconButton(
            onClick = onDismiss,
        ) {
            com.dd3boh.xenowave.ui.icons.XenoIcon(
                icon = com.dd3boh.xenowave.ui.icons.XenoActionIcons.Close,
                contentDescription = stringResource(R.string.dismiss)
            )
        }
    }
}
