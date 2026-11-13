/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.insights.musicalage.ui.visual

import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.insights.musicalage.MixedErasResult
import com.dd3boh.xenowave.insights.musicalage.ReadyResult
import com.dd3boh.xenowave.insights.musicalage.ui.visual.QrDownloadMatrix
import com.dd3boh.xenowave.insights.musicalage.ui.visual.etVariant
import com.dd3boh.xenowave.ui.component.XenoBrandWordmark
import com.dd3boh.xenowave.ui.theme.XenoWaveOrbitron
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val CARD_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

private val CARD_AGE_STYLE = TextStyle(
    fontFamily = XenoWaveOrbitron,
    fontWeight = FontWeight.Medium,
)

/**
 * Ready result presentation. The animation is a reveal of data that already
 * exists; it never performs calculation, network access or playback work.
 */
@Composable
fun MusicalAgeReadyCard(
    result: ReadyResult,
    isExporting: Boolean,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onDownload: () -> Unit,
    onCopyLink: () -> Unit,
    onCancelExport: () -> Unit,
    reduceMotionOverride: Boolean? = null,
    replayToken: Int = 0,
) {
    val context = LocalContext.current
    var systemReduceMotion by remember {
        mutableStateOf(systemAnimationsDisabled(context))
    }
    val reduceMotion = reduceMotionOverride ?: systemReduceMotion
    val initialFinished = MusicalAgeRevealState.initialFinished(reduceMotion)
    val progress = remember(result, replayToken) {
        Animatable(if (initialFinished) 1f else 0f)
    }
    var finished by rememberSaveable(
        result.musicalAge,
        result.generation,
        result.dominantWindow.fromYear,
        result.dominantWindow.toYear,
        replayToken,
    ) {
        mutableStateOf(initialFinished)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(result, finished, reduceMotion, replayToken) {
        if (MusicalAgeRevealState.shouldFinishImmediately(finished, reduceMotion)) {
            // Turning reduced motion on during the reveal must land directly
            // on the complete, accessible card rather than a partial frame.
            progress.snapTo(1f)
            finished = true
        } else if (MusicalAgeRevealState.shouldAnimate(finished, reduceMotion)) {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 3_400, easing = LinearEasing),
            )
            finished = true
        }
    }

    DisposableEffect(lifecycleOwner, result) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // Re-read the real system preference when the host resumes.
                // It is not frozen in remember(context) forever.
                Lifecycle.Event.ON_RESUME -> {
                    systemReduceMotion = systemAnimationsDisabled(context)
                }
                // Background/navigation/rotation ends the reveal. It never
                // loops when the screen returns.
                Lifecycle.Event.ON_STOP -> {
                    if (!finished) {
                        finished = MusicalAgeRevealState.onStopped()
                        scope.launch { progress.snapTo(1f) }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val reveal = MusicalAgeRevealState.reveal(reduceMotion, finished, progress.value)
    val contentAlpha = ((reveal - 0.66f) / 0.28f).coerceIn(0f, 1f)
    val etScale = 0.94f + contentAlpha * 0.06f
    val space = colorResource(R.color.xenowave_space_background)
    val cyan = colorResource(R.color.xenowave_luminous_cyan)
    val light = colorResource(R.color.xenowave_primary_text)
    val muted = Color(light.red, light.green, light.blue, 0.62f)
    val actionsVisible = finished || contentAlpha > 0.985f
    val cardDescription = stringResource(
        R.string.musical_age_card_description,
        result.musicalAge,
        result.generation.label,
        result.dominantWindow.fromYear,
        result.dominantWindow.toYear,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(space)
            .spaceCardBackground(space, cyan)
            .semantics { contentDescription = cardDescription }
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            XenoBrandWordmark(
                modifier = Modifier.weight(1f),
                fontSize = 19.sp,
                color = light,
            )
            if (!finished) {
                TextButton(
                    onClick = {
                        finished = MusicalAgeRevealState.skip()
                        scope.launch { progress.snapTo(1f) }
                    },
                ) {
                    Text(
                        text = stringResource(R.string.musical_age_card_skip),
                        color = light,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            } else {
                Spacer(Modifier.width(56.dp))
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp)
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                cyan.copy(alpha = 0.16f * contentAlpha),
                                Color.Transparent,
                            ),
                            center = center,
                            radius = size.minDimension * 0.72f,
                        ),
                        radius = size.minDimension * 0.72f,
                        center = center,
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawStarTravel(reveal = reveal, cyan = cyan, light = light)
            }
            Image(
                painter = painterResource(result.generation.etVariant().drawable),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth(0.66f)
                    .aspectRatio(1f)
                    .graphicsLayer {
                        alpha = contentAlpha
                        scaleX = etScale
                        scaleY = etScale
                    },
            )
        }

        Column(
            modifier = Modifier.graphicsLayer { alpha = contentAlpha },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.musical_age_card_title).uppercase(),
                color = muted,
                fontSize = 11.sp,
                letterSpacing = 2.8.sp,
                textAlign = TextAlign.Center,
            )
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = result.musicalAge.toString(),
                    style = CARD_AGE_STYLE,
                    color = Color.White,
                    fontSize = 64.sp,
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.musical_age_card_years),
                    color = cyan,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
            Text(
                text = result.generation.label,
                color = cyan,
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.musical_age_card_era,
                    result.dominantWindow.fromYear,
                    result.dominantWindow.toYear,
                ),
                color = light,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(
                    R.string.musical_age_card_reference,
                    result.referenceYear,
                ),
                color = muted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(
                    R.string.musical_age_card_period,
                    result.periodStart.format(CARD_DATE_FORMAT),
                    result.periodEnd.format(CARD_DATE_FORMAT),
                ),
                color = muted,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
        Spacer(Modifier.height(14.dp))

        if (actionsVisible) {
            Actions(
                isExporting = isExporting,
                onShare = onShare,
                onSave = onSave,
                onDownload = onDownload,
                onCopyLink = onCopyLink,
                onCancelExport = onCancelExport,
            )
        } else {
            Text(
                text = stringResource(R.string.musical_age_card_ready),
                color = light,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(
    isExporting: Boolean,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onDownload: () -> Unit,
    onCopyLink: () -> Unit,
    onCancelExport: () -> Unit,
) {
    val cyan = colorResource(R.color.xenowave_luminous_cyan)
    val space = colorResource(R.color.xenowave_space_background)
    val light = colorResource(R.color.xenowave_primary_text)
    val canSave = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    Button(
        onClick = onDownload,
        colors = ButtonDefaults.buttonColors(
            containerColor = cyan,
            contentColor = space,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.musical_age_card_download),
            fontWeight = FontWeight.SemiBold,
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.musical_age_card_download_hint),
            color = light.copy(alpha = 0.72f),
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            QrCode(
                modifier = Modifier.size(96.dp),
                dark = space,
            )
            Text(
                text = stringResource(R.string.musical_age_card_qr_caption),
                color = light.copy(alpha = 0.62f),
                fontSize = 7.sp,
                letterSpacing = 0.8.sp,
            )
        }
    }

    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedButton(
            onClick = onShare,
            enabled = !isExporting,
        ) {
            Text(stringResource(R.string.musical_age_card_share))
        }
        if (canSave) {
            OutlinedButton(
                onClick = onSave,
                enabled = !isExporting,
            ) {
                Text(stringResource(R.string.musical_age_card_save))
            }
        }
        TextButton(onClick = onCopyLink) {
            Text(stringResource(R.string.musical_age_card_copy_link))
        }
    }

    if (isExporting) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = light,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.musical_age_card_exporting),
                color = light,
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onCancelExport) {
                Text(
                    text = stringResource(R.string.musical_age_cancel),
                    color = light,
                )
            }
        }
    }
}

/**
 * Neutral MIXED_ERAS presentation: official ET and message only, never an
 * invented age, generation or age-specific character.
 */
@Composable
fun MusicalAgeMixedCard(
    result: MixedErasResult,
    onDownload: () -> Unit,
    onCopyLink: () -> Unit,
) {
    val space = colorResource(R.color.xenowave_space_background)
    val cyan = colorResource(R.color.xenowave_luminous_cyan)
    val light = colorResource(R.color.xenowave_primary_text)
    val muted = Color(light.red, light.green, light.blue, 0.68f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(space)
            .spaceCardBackground(space, cyan)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        XenoBrandWordmark(fontSize = 19.sp, color = light)
        Image(
            painter = painterResource(R.mipmap.xeno_launcher_foreground),
            contentDescription = stringResource(R.string.musical_age_card_neutral_description),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth(0.48f)
                .aspectRatio(1f)
                .padding(top = 8.dp),
        )
        Text(
            text = stringResource(R.string.musical_age_mixed_title),
            color = cyan,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.musical_age_mixed_body),
            color = muted,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        result.eras.forEach { era ->
            Text(
                text = stringResource(
                    R.string.musical_age_era_format,
                    era.fromYear,
                    era.toYear,
                    era.share * 100.0,
                ),
                color = light,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onDownload,
                colors = ButtonDefaults.buttonColors(
                    containerColor = cyan,
                    contentColor = space,
                ),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.musical_age_card_download))
            }
            TextButton(onClick = onCopyLink) {
                Text(stringResource(R.string.musical_age_card_copy_link), color = light)
            }
        }
    }
}

@Composable
private fun QrCode(
    modifier: Modifier = Modifier,
    dark: Color,
    background: Color = Color.White,
) {
    Canvas(modifier = modifier) {
        drawRoundRect(
            color = background,
            size = size,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
        )
        val module = min(size.width, size.height) / QrDownloadMatrix.SIZE
        for (row in 0 until QrDownloadMatrix.SIZE) {
            for (column in 0 until QrDownloadMatrix.SIZE) {
                if (!QrDownloadMatrix.isDark(column, row)) continue
                drawRect(
                    color = dark,
                    topLeft = androidx.compose.ui.geometry.Offset(
                        column * module,
                        row * module,
                    ),
                    size = androidx.compose.ui.geometry.Size(module + 0.2f, module + 0.2f),
                )
            }
        }
    }
}

/**
 * Deterministic space background. It intentionally contains no random state:
 * the same card looks the same after rotation or re-composition.
 */
private fun Modifier.spaceCardBackground(space: Color, cyan: Color): Modifier =
    drawBehind {
        drawRect(space)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(cyan.copy(alpha = 0.14f), Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.3f),
                radius = size.minDimension * 0.9f,
            ),
            radius = size.minDimension * 0.9f,
            center = androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.3f),
        )
        repeat(120) { index ->
            val x = ((index * 73) % 997) / 997f * size.width
            val y = ((index * 199) % 991) / 991f * size.height
            val radius = 0.5f + ((index * 17) % 9) / 9f * 1.4f
            val alpha = 0.08f + ((index * 29) % 11) / 11f * 0.24f
            drawCircle(
                color = Color.White.copy(alpha = alpha),
                radius = radius,
                center = androidx.compose.ui.geometry.Offset(x, y),
            )
        }
    }

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStarTravel(
    reveal: Float,
    cyan: Color,
    light: Color,
) {
    val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
    val maxRadius = size.minDimension * 0.62f
    val signal = (1f - (reveal / 0.22f)).coerceIn(0f, 1f)
    val travel = ((reveal - 0.18f) / 0.46f).coerceIn(0f, 1f)
    val deceleration = ((reveal - 0.62f) / 0.18f).coerceIn(0f, 1f)
    val speed = (travel * (1f - deceleration)).coerceIn(0f, 1f)
    val fade = 1f - ((reveal - 0.70f) / 0.30f).coerceIn(0f, 1f)

    if (signal > 0.01f) {
        repeat(3) { ring ->
            val phase = ((reveal * 5f + ring / 3f) % 1f)
            drawCircle(
                color = cyan.copy(alpha = (1f - phase) * 0.34f * signal),
                radius = size.minDimension * (0.045f + phase * 0.18f),
                center = center,
                style = Stroke(width = 2f),
            )
        }
        val bars = 11
        repeat(bars) { index ->
            val offset = index - (bars - 1) / 2f
            val envelope = 1f - abs(offset) / (bars / 2f)
            val pulse = 0.35f + 0.65f * abs(sin(reveal * 42f + index * 0.8f))
            val height = (6.dp.toPx() + 24.dp.toPx() * envelope * pulse) * signal
            val x = center.x + offset * 7.dp.toPx()
            drawLine(
                color = cyan.copy(alpha = 0.82f * signal),
                start = androidx.compose.ui.geometry.Offset(x, center.y - height / 2f),
                end = androidx.compose.ui.geometry.Offset(x, center.y + height / 2f),
                strokeWidth = 2.6f,
                cap = StrokeCap.Round,
            )
        }
    }

    if (speed > 0.001f || travel > 0.001f) {
        val streaks = 150
        repeat(streaks) { index ->
            val angle = index * 2.399963f
            val seed = ((index * 37) % 101) / 101f
            val distance = seed * maxRadius + speed * maxRadius * 0.72f
            val length = (2.dp.toPx() + 54.dp.toPx() * speed * (0.35f + seed * 0.65f))
            val start = androidx.compose.ui.geometry.Offset(
                center.x + cos(angle) * distance,
                center.y + sin(angle) * distance,
            )
            val end = androidx.compose.ui.geometry.Offset(
                center.x + cos(angle) * (distance + length),
                center.y + sin(angle) * (distance + length),
            )
            val color = if (index % 7 == 0) cyan else light
            drawLine(
                color = color.copy(
                    alpha = (0.12f + seed * 0.30f) *
                        (0.30f + speed * 0.70f) * fade,
                ),
                start = start,
                end = end,
                strokeWidth = 1f + seed * 1.4f,
                cap = StrokeCap.Round,
            )
        }
    }

    val starAlpha = ((reveal - 0.58f) / 0.12f).coerceIn(0f, 1f) *
        (1f - ((reveal - 0.82f) / 0.18f).coerceIn(0f, 1f))
    if (starAlpha > 0.01f) {
        val arm = 10.dp.toPx() + 14.dp.toPx() * starAlpha
        drawLine(
            color = Color.White.copy(alpha = 0.72f * starAlpha),
            start = androidx.compose.ui.geometry.Offset(center.x - arm, center.y),
            end = androidx.compose.ui.geometry.Offset(center.x + arm, center.y),
            strokeWidth = 2f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = Color.White.copy(alpha = 0.72f * starAlpha),
            start = androidx.compose.ui.geometry.Offset(center.x, center.y - arm),
            end = androidx.compose.ui.geometry.Offset(center.x, center.y + arm),
            strokeWidth = 2f,
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = cyan.copy(alpha = 0.42f * starAlpha),
            radius = 4.dp.toPx(),
            center = center,
        )
    }
}

/**
 * Pure state transitions for the local reveal. Extracted so JVM tests can
 * prove the reduced-motion/skip/background/replay rules without pretending to
 * execute Compose UI on a device.
 */
internal object MusicalAgeRevealState {
    fun initialFinished(reduceMotion: Boolean): Boolean = reduceMotion

    fun shouldAnimate(finished: Boolean, reduceMotion: Boolean): Boolean =
        !finished && !reduceMotion

    fun shouldFinishImmediately(finished: Boolean, reduceMotion: Boolean): Boolean =
        !finished && reduceMotion

    fun skip(): Boolean = true

    fun onStopped(): Boolean = true

    fun reveal(
        reduceMotion: Boolean,
        finished: Boolean,
        animatedProgress: Float,
    ): Float = if (finished || reduceMotion) {
        1f
    } else {
        animatedProgress.coerceIn(0f, 1f)
    }
}

internal fun shouldReduceMotion(animatorScale: Float, transitionScale: Float): Boolean =
    min(animatorScale, transitionScale) < 1f

private fun systemAnimationsDisabled(context: android.content.Context): Boolean {
    val resolver = context.contentResolver
    val animator = Settings.Global.getFloat(
        resolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
    val transition = Settings.Global.getFloat(
        resolver,
        Settings.Global.TRANSITION_ANIMATION_SCALE,
        1f,
    )
    return shouldReduceMotion(animator, transition)
}
