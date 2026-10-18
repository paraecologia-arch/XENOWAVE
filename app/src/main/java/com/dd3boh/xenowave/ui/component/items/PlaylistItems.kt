/*
 * Copyright (C) 2025 O﻿ute﻿rTu﻿ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */
package com.dd3boh.xenowave.ui.component.items

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.constants.ListThumbnailSize
import com.dd3boh.xenowave.constants.ThumbnailCornerRadius
import com.dd3boh.xenowave.db.entities.Playlist
import com.dd3boh.xenowave.db.entities.PlaylistEntity
import com.dd3boh.xenowave.ui.component.items.Icon.PlaylistIcon
import com.dd3boh.xenowave.ui.utils.getNSongsString
import com.dd3boh.xenowave.utils.getThumbnailModel
import kotlin.math.roundToInt

@Composable
fun AutoPlaylistListItem(
    playlist: PlaylistEntity,
    thumbnail: Int,
    modifier: Modifier = Modifier,
    trailingContent: @Composable RowScope.() -> Unit = {},
) = ListItem(
    title = playlist.name,
    subtitle = stringResource(id = R.string.auto_playlist),
    thumbnailContent = {
        Box(
            modifier = Modifier
                .size(ListThumbnailSize)
                .background(
                    MaterialTheme.colorScheme.surfaceColorAtElevation(128.dp),
                    shape = RoundedCornerShape(ThumbnailCornerRadius)
                )
        ) {
            Icon(
                painter = painterResource(thumbnail),
                contentDescription = null,
                modifier = Modifier
                    .size(ListThumbnailSize / 2 + 4.dp)
                    .align(Alignment.Companion.Center)
            )
        }
    },
    trailingContent = trailingContent,
    modifier = modifier
)

@Composable
fun AutoPlaylistGridItem(
    playlist: PlaylistEntity,
    thumbnail: Int,
    modifier: Modifier = Modifier,
    fillMaxWidth: Boolean = false,
) = GridItem(
    title = playlist.name,
    subtitle = stringResource(id = R.string.auto_playlist),
    thumbnailContent = {
        val width = maxWidth
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(128.dp),
                    shape = RoundedCornerShape(ThumbnailCornerRadius)
                )
        ) {
            Icon(
                painter = painterResource(thumbnail),
                contentDescription = null,
                tint = LocalContentColor.current.copy(alpha = 0.8f),
                modifier = Modifier
                    .size(width / 2 + 10.dp)
                    .align(Alignment.Companion.Center)
            )
        }
    },
    fillMaxWidth = fillMaxWidth,
    modifier = modifier
)

@Composable
fun PlaylistListItem(
    playlist: Playlist,
    modifier: Modifier = Modifier,
    showBadges: Boolean = false,
    trailingContent: @Composable RowScope.() -> Unit = {},
) = ListItem(
    title = playlist.playlist.name,
    subtitle =
        if (playlist.songCount == 0 && playlist.playlist.remoteSongCount != null)
            getNSongsString(playlist.playlist.remoteSongCount)
        else
            getNSongsString(playlist.songCount, playlist.downloadCount),
    badges = {
        PlaylistIcon(playlist.playlist) // always show
        if (!showBadges) return@ListItem
        com.dd3boh.xenowave.ui.icons.XenoIcon(
            icon = if (playlist.playlist.isEditable) com.dd3boh.xenowave.ui.icons.XenoActionIcons.Edit else com.dd3boh.xenowave.ui.icons.XenoActionIcons.EditOff,
            contentDescription = null,
            modifier = Modifier
                .size(18.dp)
                .padding(end = 2.dp)
        )

        if (playlist.playlist.isLocal) {
            com.dd3boh.xenowave.ui.icons.XenoIcon(
                icon = com.dd3boh.xenowave.ui.icons.XenoSystemIcons.SdCard,
                contentDescription = null,
                modifier = Modifier
                    .size(18.dp)
                    .padding(end = 2.dp)
            )
        }

        if (playlist.downloadCount > 0) {
            com.dd3boh.xenowave.ui.icons.XenoIcon(
                icon = com.dd3boh.xenowave.ui.icons.XenoSystemIcons.OfflinePin,
                contentDescription = null,
                modifier = Modifier
                    .size(18.dp)
                    .padding(end = 2.dp)
            )
        }
    },
    thumbnailContent = {
        PlaylistThumbnail(
            playlist = playlist.playlist,
            thumbnails = playlist.thumbnails,
        )
    },
    trailingContent = trailingContent,
    modifier = modifier
)

@Composable
fun PlaylistGridItem(
    playlist: Playlist,
    modifier: Modifier = Modifier,
    fillMaxWidth: Boolean = false,
) = GridItem(
    title = playlist.playlist.name,
    subtitle =
        if (playlist.songCount == 0 && playlist.playlist.remoteSongCount != null)
            getNSongsString(playlist.playlist.remoteSongCount)
        else
            getNSongsString(playlist.songCount, playlist.downloadCount),
    badges = {
        PlaylistIcon(playlist.playlist)
        if (playlist.downloadCount > 0) {
            com.dd3boh.xenowave.ui.icons.XenoIcon(
                icon = com.dd3boh.xenowave.ui.icons.XenoSystemIcons.OfflinePin,
                contentDescription = null,
                modifier = Modifier
                    .size(18.dp)
                    .padding(end = 2.dp)
            )
        }
    },
    thumbnailContent = {
        val width = maxWidth
        PlaylistThumbnail(
            playlist = playlist.playlist,
            thumbnails = playlist.thumbnails,
            size = width,
            iconPadding = width / 6,
            iconTint = LocalContentColor.current.copy(alpha = 0.8f),
        )
    },
    fillMaxWidth = fillMaxWidth,
    modifier = modifier
)

@Composable
fun PlaylistThumbnail(
    playlist: PlaylistEntity,
    thumbnails: List<String>,
    size: Dp = ListThumbnailSize,
    shape: Shape = RoundedCornerShape(ThumbnailCornerRadius),
    iconPadding: Dp = 4.dp,
    iconTint: Color = LocalContentColor.current,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp))
    ) {
        val thumbnail = playlist.thumbnailUrl ?: thumbnails.firstOrNull()
        if (thumbnail != null) {
            val density = LocalDensity.current
            val px = (size.value * density.density).roundToInt()
            AsyncImage(
                model = getThumbnailModel(thumbnail, px, px),
                contentDescription = null,
                contentScale = ContentScale.Companion.Crop,
                modifier = Modifier.Companion
                    .size(size)
                    .clip(shape)
            )
        } else {
            /**
             * 8: Local playlist
             * 4: Synced/editable playlist
             * 2: Saved remote playlist
             * 1: Supports endpoints
             *
             */
            var features = 0
            if (playlist.isLocal) features += 8
            if (playlist.isEditable) features += 4
            if (playlist.bookmarkedAt != null) features += 2
            if ((playlist.playEndpointParams ?: playlist.radioEndpointParams
                ?: playlist.shuffleEndpointParams) != null
            ) features += 1
            com.dd3boh.xenowave.ui.icons.XenoIcon(
                icon = when {
                    // TODO: Icons that actually goddamn match with each other wth is this google???
                    features >= 8 -> com.dd3boh.xenowave.ui.icons.XenoIcons.AutoMirrored.QueueMusic
                    features >= 4 -> com.dd3boh.xenowave.ui.icons.XenoIcons.AutoMirrored.PlaylistAdd
                    features >= 2 -> com.dd3boh.xenowave.ui.icons.XenoIcons.AutoMirrored.PlaylistPlay
                    else -> com.dd3boh.xenowave.ui.icons.XenoSystemIcons.Error
                },
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(iconPadding)
            )
        }
    }
}
