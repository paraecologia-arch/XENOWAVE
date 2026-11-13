/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import com.dd3boh.xenowave.R

/**
 * Orbitron Medium (weight 500) is bundled from the file approved for the
 * XENOWAVE wordmark. It is not the interface reading font: Material/Inter
 * typography remains in charge of controls and long-form text.
 */
val XenoWaveOrbitron: FontFamily = FontFamily(
    Font(
        resId = R.font.orbitron_medium,
        weight = FontWeight.Medium,
        style = androidx.compose.ui.text.font.FontStyle.Normal,
    )
)

/**
 * Brand wordmark style: weight 500 and 0.14 em letter spacing, as approved.
 * Use this only where the product name is presented as the mark.
 */
val XenoWaveWordmarkStyle: TextStyle = TextStyle(
    fontFamily = XenoWaveOrbitron,
    fontWeight = FontWeight.Medium,
    letterSpacing = 0.14.em,
)
