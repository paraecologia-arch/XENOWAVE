/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.insights.musicalage.ui.visual

import androidx.annotation.DrawableRes
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.insights.musicalage.MusicalGeneration

/**
 * Approved character variants. They are fictional musical maturity stages,
 * never a demographic estimate of the listener.
 */
enum class MusicalAgeEtVariant(
    @DrawableRes val drawable: Int,
    val key: String,
) {
    JOVIAL(R.drawable.xenowave_et_jovial, "jovial"),
    MATURE(R.drawable.xenowave_et_maduro, "maduro"),
    VETERAN(R.drawable.xenowave_et_veterano, "veterano"),
}

/**
 * Visual convention v1 derived only from the musical generation already
 * calculated by the engine. Exhaustive by compiler check so a new enum value
 * cannot silently fall into an arbitrary character.
 */
fun MusicalGeneration.etVariant(): MusicalAgeEtVariant = when (this) {
    MusicalGeneration.ALPHA,
    MusicalGeneration.GENERATION_Z,
    -> MusicalAgeEtVariant.JOVIAL

    MusicalGeneration.MILLENNIAL,
    MusicalGeneration.GENERATION_X,
    -> MusicalAgeEtVariant.MATURE

    MusicalGeneration.BOOMER,
    MusicalGeneration.SILENT,
    MusicalGeneration.CLASSICS,
    -> MusicalAgeEtVariant.VETERAN
}

enum class MusicalAgeCardFormat(
    val width: Int,
    val height: Int,
    val suffix: String,
) {
    VERTICAL(1080, 1920, "9x16"),
    SQUARE(1080, 1080, "1x1"),
}
