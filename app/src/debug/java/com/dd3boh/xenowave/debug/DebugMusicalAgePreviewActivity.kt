/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dd3boh.xenowave.insights.musicalage.MusicalGeneration
import com.dd3boh.xenowave.insights.musicalage.ui.ResultCard
import com.dd3boh.xenowave.ui.theme.XenoWaveTheme

/**
 * Debug-only host for synthetic visual inspection on a test device. It is
 * declared only in the debug manifest and never packaged in release.
 *
 * Launch with:
 * adb shell am start -n com.dd3boh.xenowave.debug/com.dd3boh.xenowave.debug.DebugMusicalAgePreviewActivity
 */
class DebugMusicalAgePreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            XenoWaveTheme(
                context = this,
                darkTheme = true,
                highContrastCompat = false,
            ) {
                var reduceMotion by rememberSaveable { mutableStateOf(true) }
                var replayToken by rememberSaveable { mutableIntStateOf(0) }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "DEBUG FIXTURE · dados sintéticos · sem conta pessoal",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Reduzir movimento (forçar cartão estático)")
                        Switch(
                            checked = reduceMotion,
                            onCheckedChange = { reduceMotion = it },
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            // Explicit debug replay: production keeps the
                            // default token and never restarts on recomposition.
                            reduceMotion = false
                            replayToken += 1
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Iniciar / repetir apresentação")
                    }

                    PreviewCase("Jovial · Geração Z / 20", MusicalGeneration.GENERATION_Z, 20, reduceMotion, replayToken)
                    PreviewCase("Maduro · Millennial / 44", MusicalGeneration.MILLENNIAL, 44, reduceMotion, replayToken)
                    PreviewCase("Veterano · Clássicos / 194", MusicalGeneration.CLASSICS, 194, reduceMotion, replayToken)

                    Text("MIXED_ERAS · neutro", style = MaterialTheme.typography.titleMedium)
                    ResultCard(
                        result = DebugMusicalAgeFixtures.mixed(),
                        exporting = false,
                        onShare = {},
                        onSave = {},
                        onDownload = {},
                        onCopyLink = {},
                        onCancelExport = {},
                        reduceMotionOverride = reduceMotion,
                        replayToken = replayToken,
                    )

                    Text("INSUFFICIENT_HISTORY", style = MaterialTheme.typography.titleMedium)
                    ResultCard(
                        result = DebugMusicalAgeFixtures.insufficient(),
                        exporting = false,
                        onShare = {},
                        onSave = {},
                        onDownload = {},
                        onCopyLink = {},
                        onCancelExport = {},
                        reduceMotionOverride = reduceMotion,
                        replayToken = replayToken,
                    )

                    Text("NO_HISTORY", style = MaterialTheme.typography.titleMedium)
                    ResultCard(
                        result = DebugMusicalAgeFixtures.noHistory(),
                        exporting = false,
                        onShare = {},
                        onSave = {},
                        onDownload = {},
                        onCopyLink = {},
                        onCancelExport = {},
                        reduceMotionOverride = reduceMotion,
                        replayToken = replayToken,
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun PreviewCase(
        label: String,
        generation: MusicalGeneration,
        age: Int,
        reduceMotion: Boolean,
        replayToken: Int,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        ResultCard(
            result = DebugMusicalAgeFixtures.ready(generation, age),
            exporting = false,
            onShare = {},
            onSave = {},
            onDownload = {},
            onCopyLink = {},
            onCancelExport = {},
            reduceMotionOverride = reduceMotion,
            replayToken = replayToken,
        )
    }
}
