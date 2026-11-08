/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dd3boh.xenowave.insights.musicalage.MusicalGeneration
import com.dd3boh.xenowave.insights.musicalage.ui.ResultCard
import com.dd3boh.xenowave.ui.theme.XenoWaveTheme

@Preview(name = "Ready · jovial · dark", widthDp = 360, heightDp = 900, showBackground = true)
@Composable
private fun ReadyJovialPreview() = PreviewHost(dark = true) {
    ResultCard(
        result = DebugMusicalAgeFixtures.ready(MusicalGeneration.GENERATION_Z, 20),
        exporting = false,
        onShare = {},
        onSave = {},
        onDownload = {},
        onCopyLink = {},
        onCancelExport = {},
    )
}

@Preview(name = "Ready · maduro · light", widthDp = 360, heightDp = 900, showBackground = true)
@Composable
private fun ReadyMaturePreview() = PreviewHost(dark = false) {
    ResultCard(
        result = DebugMusicalAgeFixtures.ready(MusicalGeneration.MILLENNIAL, 44),
        exporting = false,
        onShare = {},
        onSave = {},
        onDownload = {},
        onCopyLink = {},
        onCancelExport = {},
    )
}

@Preview(name = "Ready · veterano · large text", widthDp = 360, heightDp = 1100, fontScale = 2f, showBackground = true)
@Composable
private fun ReadyVeteranLargeTextPreview() = PreviewHost(dark = true) {
    ResultCard(
        result = DebugMusicalAgeFixtures.ready(MusicalGeneration.CLASSICS, 194),
        exporting = false,
        onShare = {},
        onSave = {},
        onDownload = {},
        onCopyLink = {},
        onCancelExport = {},
    )
}

@Preview(name = "Ready · reduced motion", widthDp = 360, heightDp = 900, showBackground = true)
@Composable
private fun ReadyReducedMotionPreview() = PreviewHost(dark = true) {
    ResultCard(
        result = DebugMusicalAgeFixtures.ready(MusicalGeneration.GENERATION_X, 52),
        exporting = false,
        onShare = {},
        onSave = {},
        onDownload = {},
        onCopyLink = {},
        onCancelExport = {},
        reduceMotionOverride = true,
    )
}

@Preview(name = "Mixed eras · neutral", widthDp = 360, heightDp = 900, showBackground = true)
@Composable
private fun MixedErasPreview() = PreviewHost(dark = true) {
    ResultCard(
        result = DebugMusicalAgeFixtures.mixed(),
        exporting = false,
        onShare = {},
        onSave = {},
        onDownload = {},
        onCopyLink = {},
        onCancelExport = {},
    )
}

@Preview(name = "Insufficient history", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
private fun InsufficientPreview() = PreviewHost(dark = false) {
    ResultCard(
        result = DebugMusicalAgeFixtures.insufficient(),
        exporting = false,
        onShare = {},
        onSave = {},
        onDownload = {},
        onCopyLink = {},
        onCancelExport = {},
    )
}

@Preview(name = "No history", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
private fun NoHistoryPreview() = PreviewHost(dark = true) {
    ResultCard(
        result = DebugMusicalAgeFixtures.noHistory(),
        exporting = false,
        onShare = {},
        onSave = {},
        onDownload = {},
        onCopyLink = {},
        onCancelExport = {},
    )
}

@Composable
private fun PreviewHost(
    dark: Boolean,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    XenoWaveTheme(
        context = context,
        darkTheme = dark,
        highContrastCompat = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("DEBUG FIXTURE · dados sintéticos")
            content()
        }
    }
}
