package com.dd3boh.xenowave.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dd3boh.xenowave.R

@Immutable
data class XenoWaveColors(
    val background: Color,
    val surface: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textDisabled: Color,
    val primary: Color,
    val onPrimary: Color,
    val signal: Color,
    val support: Color,
    val selectedContainer: Color,
    val onSelectedContainer: Color,
    val focusBorder: Color,
    val border: Color,
    val signalGradient: List<Color>,
    val glow: Color,
)

@Immutable
data class XenoWaveMetrics(
    val compactSpacing: Dp,
    val contentSpacing: Dp,
    val sectionSpacing: Dp,
    val cornerRadius: Dp,
    val focusBorderWidth: Dp,
    val glowBorderWidth: Dp,
)

val LocalXenoWaveColors = staticCompositionLocalOf<XenoWaveColors> {
    error("XenoWaveColors was not provided")
}

val LocalXenoWaveMetrics = staticCompositionLocalOf {
    XenoWaveMetrics(
        compactSpacing = 8.dp,
        contentSpacing = 16.dp,
        sectionSpacing = 24.dp,
        cornerRadius = 16.dp,
        focusBorderWidth = 2.dp,
        glowBorderWidth = 1.dp,
    )
}

val XenoWaveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun xenoWaveColors(): XenoWaveColors = LocalXenoWaveColors.current

@Composable
fun xenoWaveMetrics(): XenoWaveMetrics = LocalXenoWaveMetrics.current

@Composable
internal fun xenoWaveColors(darkTheme: Boolean): XenoWaveColors {
    val space = colorResource(R.color.xenowave_space_background)
    val blue = colorResource(R.color.xenowave_primary_blue)
    val cyan = colorResource(R.color.xenowave_luminous_cyan)
    val purple = colorResource(R.color.xenowave_support_purple)
    val secondarySurface = colorResource(R.color.xenowave_secondary_surface)
    val primaryText = colorResource(R.color.xenowave_primary_text)

    return if (darkTheme) {
        val textSecondary = lerp(Color.White, space, 0.28f)
        XenoWaveColors(
            background = space,
            surface = lerp(space, Color.White, 0.04f),
            surfaceContainer = lerp(space, Color.White, 0.07f),
            surfaceContainerHigh = secondarySurface,
            surfaceContainerHighest = lerp(space, Color.White, 0.15f),
            textPrimary = primaryText,
            textSecondary = textSecondary,
            textDisabled = lerp(textSecondary, space, 0.35f),
            primary = blue,
            onPrimary = Color.White,
            signal = cyan,
            support = purple,
            selectedContainer = lerp(blue, space, 0.78f),
            onSelectedContainer = cyan,
            focusBorder = cyan,
            border = lerp(cyan, space, 0.65f),
            signalGradient = listOf(blue, cyan),
            glow = cyan.copy(alpha = 0.18f),
        )
    } else {
        val readableSignal = lerp(cyan, space, 0.62f)
        XenoWaveColors(
            background = lerp(space, Color.White, 0.93f),
            surface = lerp(space, Color.White, 0.97f),
            surfaceContainer = lerp(space, Color.White, 0.91f),
            surfaceContainerHigh = lerp(space, Color.White, 0.86f),
            surfaceContainerHighest = lerp(space, Color.White, 0.80f),
            textPrimary = space,
            textSecondary = lerp(space, Color.White, 0.26f),
            textDisabled = lerp(space, Color.White, 0.58f),
            primary = blue,
            onPrimary = Color.White,
            signal = readableSignal,
            support = purple,
            selectedContainer = lerp(blue, Color.White, 0.82f),
            onSelectedContainer = blue,
            focusBorder = blue,
            border = lerp(blue, Color.White, 0.72f),
            signalGradient = listOf(blue, cyan, purple),
            glow = cyan.copy(alpha = 0.14f),
        )
    }
}

internal fun XenoWaveColors.toColorScheme(darkTheme: Boolean): ColorScheme {
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = selectedContainer,
            onPrimaryContainer = signal,
            secondary = support,
            onSecondary = Color.White,
            secondaryContainer = selectedContainer,
            onSecondaryContainer = signal,
            tertiary = signal,
            onTertiary = background,
            background = background,
            onBackground = textPrimary,
            surface = surface,
            onSurface = textPrimary,
            surfaceVariant = surfaceContainer,
            onSurfaceVariant = textSecondary,
            surfaceTint = primary,
            outline = border,
            outlineVariant = surfaceContainerHighest,
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = selectedContainer,
            onPrimaryContainer = textPrimary,
            secondary = support,
            onSecondary = Color.White,
            secondaryContainer = selectedContainer,
            onSecondaryContainer = textPrimary,
            tertiary = signal,
            onTertiary = Color.White,
            background = background,
            onBackground = textPrimary,
            surface = surface,
            onSurface = textPrimary,
            surfaceVariant = surfaceContainer,
            onSurfaceVariant = textSecondary,
            surfaceTint = primary,
            outline = border,
            outlineVariant = surfaceContainerHighest,
        )
    }

    return scheme.copy(
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        surfaceContainerLow = surfaceContainer,
        surfaceContainerLowest = surface,
    )
}
