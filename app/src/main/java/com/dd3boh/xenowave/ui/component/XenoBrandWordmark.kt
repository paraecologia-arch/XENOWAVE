/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.dd3boh.xenowave.ui.theme.XenoWaveWordmarkStyle

/**
 * The canonical XENOWAVE wordmark.
 *
 * Orbitron Medium (500) with 0.14 em tracking is used only for the product
 * name. Functional copy, menus and controls keep Material/Inter typography.
 */
@Composable
fun XenoBrandWordmark(
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 24.sp,
    color: Color = MaterialTheme.colorScheme.onBackground,
    textAlign: TextAlign = TextAlign.Center,
) {
    Text(
        text = "XENOWAVE",
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        style = XenoWaveWordmarkStyle,
        textAlign = textAlign,
        maxLines = 1,
    )
}
