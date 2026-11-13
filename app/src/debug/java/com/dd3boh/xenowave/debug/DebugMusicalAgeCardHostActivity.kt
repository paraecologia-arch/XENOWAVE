/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.debug

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.dd3boh.xenowave.insights.musicalage.MusicalAgeResult
import com.dd3boh.xenowave.insights.musicalage.MusicalGeneration
import com.dd3boh.xenowave.insights.musicalage.ui.ResultCard
import com.dd3boh.xenowave.ui.theme.XenoWaveTheme
import java.util.Locale

/**
 * Debug-only host for a single synthetic musical-age card.
 *
 * It reuses the production ResultCard/MusicalAgeReadyCard and only exposes
 * the debug controls needed by instrumentation: reduced-motion override,
 * explicit replay and an unrelated recomposition trigger. It is declared only
 * in the debug manifest and never packaged in release.
 *
 * Example:
 * adb shell am start -n com.dd3boh.xenowave.debug/.debug.DebugMusicalAgeCardHostActivity \
 *   --es case veteran --es animation reduced --ef font_scale 1.5
 */
class DebugMusicalAgeCardHostActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val configuration = Configuration(newBase.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("pt-BR"))
        }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestedCase = intent.getStringExtra(EXTRA_CASE) ?: CASE_JOVIAL
        val initialOverride = when (intent.getStringExtra(EXTRA_ANIMATION)) {
            ANIMATION_REDUCED -> true
            ANIMATION_SYSTEM -> null
            else -> false
        }
        val controls = intent.getBooleanExtra(EXTRA_CONTROLS, true)
        val fontScale = intent.getFloatExtra(EXTRA_FONT_SCALE, 1f).coerceIn(0.85f, 2f)

        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = fontScale),
            ) {
                XenoWaveTheme(
                    context = LocalContext.current,
                    darkTheme = true,
                    highContrastCompat = false,
                ) {
                    var reduceMotionOverride by rememberSaveable {
                        mutableStateOf<Boolean?>(initialOverride)
                    }
                    var replayToken by rememberSaveable { mutableIntStateOf(0) }
                    var recomposeTick by rememberSaveable { mutableIntStateOf(0) }
                    val result = remember(requestedCase) { fixtureFor(requestedCase) }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .safeDrawingPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "DEBUG FIXTURE · dados sintéticos · sem conta pessoal",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (controls) {
                            OutlinedButton(
                                onClick = { reduceMotionOverride = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Reduzir movimento")
                            }
                            OutlinedButton(
                                onClick = { reduceMotionOverride = false },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Animar")
                            }
                            OutlinedButton(
                                onClick = { replayToken += 1 },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Repetir")
                            }
                            OutlinedButton(
                                onClick = { recomposeTick += 1 },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Recompor")
                            }
                            Text("Recomposição: $recomposeTick")
                        }

                        ResultCard(
                            result = result,
                            exporting = false,
                            onShare = {},
                            onSave = {},
                            onDownload = {},
                            onCopyLink = {},
                            onCancelExport = {},
                            reduceMotionOverride = reduceMotionOverride,
                            replayToken = replayToken,
                        )
                    }
                }
            }
        }
    }

    private fun fixtureFor(case: String): MusicalAgeResult = when (case) {
        CASE_MATURE -> DebugMusicalAgeFixtures.ready(MusicalGeneration.MILLENNIAL, 44)
        CASE_VETERAN -> DebugMusicalAgeFixtures.ready(MusicalGeneration.CLASSICS, 194)
        CASE_MIXED -> DebugMusicalAgeFixtures.mixed()
        CASE_INSUFFICIENT -> DebugMusicalAgeFixtures.insufficient()
        CASE_NO_HISTORY -> DebugMusicalAgeFixtures.noHistory()
        else -> DebugMusicalAgeFixtures.ready(MusicalGeneration.GENERATION_Z, 20)
    }

    companion object {
        const val EXTRA_CASE = "case"
        const val EXTRA_ANIMATION = "animation"
        const val EXTRA_CONTROLS = "controls"
        const val EXTRA_FONT_SCALE = "font_scale"

        const val CASE_JOVIAL = "jovial"
        const val CASE_MATURE = "mature"
        const val CASE_VETERAN = "veteran"
        const val CASE_MIXED = "mixed"
        const val CASE_INSUFFICIENT = "insufficient"
        const val CASE_NO_HISTORY = "no_history"

        const val ANIMATION_ON = "on"
        const val ANIMATION_REDUCED = "reduced"
        const val ANIMATION_SYSTEM = "system"
    }
}
