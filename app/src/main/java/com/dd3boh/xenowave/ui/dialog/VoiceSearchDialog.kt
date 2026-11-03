/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.ui.dialog

import android.os.Build
import android.provider.Settings
import android.speech.SpeechRecognizer
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.ui.icons.XenoIcon
import com.dd3boh.xenowave.ui.icons.XenoSystemIcons
import com.dd3boh.xenowave.utils.voice.AndroidVoiceEngine
import com.dd3boh.xenowave.utils.voice.AndroidVoiceTimerFactory
import com.dd3boh.xenowave.utils.voice.VoiceSearchController
import com.dd3boh.xenowave.utils.voice.VoiceSearchError
import com.dd3boh.xenowave.utils.voice.VoiceSearchState
import java.util.Locale

/**
 * Voice input dialog for the search bar.
 *
 * The controller only delivers one final result per session. The dialog closes itself
 * immediately after handing the recognised text to [onResult].
 */
@Composable
fun VoiceSearchDialog(
    onDismiss: () -> Unit,
    onResult: (String) -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val languageTag = remember(configuration) {
        val tag = configuration.locales[0].toLanguageTag()
        if (tag.isBlank() || tag == "und") Locale.getDefault().toLanguageTag() else tag
    }
    val preferOnDevice = remember {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    }

    val controller = remember {
        VoiceSearchController(
            engineFactory = { callbacks -> AndroidVoiceEngine(context, callbacks) },
            timerFactory = AndroidVoiceTimerFactory(),
        )
    }
    val state by controller.state.collectAsState()

    DisposableEffect(controller) {
        controller.start(languageTag, preferOnDevice)
        onDispose { controller.release() }
    }

    // Never keep the microphone open when the app leaves the foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                controller.cancel()
                onDismiss()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state) {
        val finished = state as? VoiceSearchState.Finished ?: return@LaunchedEffect
        onResult(finished.text)
        onDismiss()
    }

    // Honour the system animation settings (0 disables window animations).
    val animationsEnabled = remember {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) > 0f
        }.getOrDefault(true)
    }

    val phase = if (animationsEnabled) {
        val transition = rememberInfiniteTransition(label = "voicePulse")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "voicePulsePhase",
        )
        value
    } else {
        0.5f
    }

    val statusText = statusText(state)
    val message = when (val current = state) {
        is VoiceSearchState.Listening -> current.partialText
        is VoiceSearchState.Processing -> current.text
        is VoiceSearchState.Finished -> current.text
        else -> ""
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
            ) {
                Text(
                    text = stringResource(R.string.voice_search_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(20.dp))

                VoicePulse(
                    level = (state as? VoiceSearchState.Listening)?.level ?: 0f,
                    phase = phase,
                    animated = animationsEnabled,
                    contentDescription = stringResource(R.string.voice_search_title),
                    stateDescription = statusText,
                )

                Spacer(Modifier.height(20.dp))

                if (message.isNotEmpty()) {
                    Text(
                        text = "\u201C$message\u201D",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                }

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state is VoiceSearchState.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )

                Spacer(Modifier.height(12.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.voice_search_cancel))
                    }
                    TextButton(
                        onClick = { controller.retry(languageTag, preferOnDevice) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.voice_search_retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun VoicePulse(
    level: Float,
    phase: Float,
    animated: Boolean,
    contentDescription: String,
    stateDescription: String,
) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(156.dp)
            .semantics {
                this.contentDescription = contentDescription
                this.stateDescription = stateDescription
            },
    ) {
        Canvas(modifier = Modifier.size(156.dp)) {
            val maxRadius = size.minDimension / 2f
            if (animated) {
                repeat(3) { index ->
                    val progress = (phase + index / 3f) % 1f
                    val radius = maxRadius * (0.34f + 0.6f * progress) *
                        (0.85f + 0.3f * level)
                    val alpha = ((1f - progress) * (0.28f + 0.45f * level))
                        .coerceIn(0f, 1f)
                    drawCircle(
                        color = primary.copy(alpha = alpha),
                        radius = radius,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
            drawCircle(
                color = primary.copy(alpha = 0.14f),
                radius = maxRadius * (0.58f + 0.08f * level),
            )
            drawCircle(
                color = tertiary.copy(alpha = 0.12f),
                radius = maxRadius * (0.44f + 0.06f * level),
            )
        }

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(72.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                XenoIcon(
                    icon = XenoSystemIcons.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    iconSize = 36.dp,
                )
            }
        }
    }
}

@Composable
private fun statusText(state: VoiceSearchState): String = when (state) {
    VoiceSearchState.Idle -> stringResource(R.string.voice_search_listening)
    is VoiceSearchState.Listening -> stringResource(R.string.voice_search_listening)
    is VoiceSearchState.Processing -> stringResource(R.string.voice_search_processing)
    is VoiceSearchState.Finished -> stringResource(R.string.voice_search_processing)
    is VoiceSearchState.Failed -> stringResource(errorTextResource(state.error))
}

private fun errorTextResource(error: VoiceSearchError): Int = when (error) {
    VoiceSearchError.PERMISSION_DENIED -> R.string.voice_search_permission_denied
    VoiceSearchError.BUSY -> R.string.voice_search_busy
    VoiceSearchError.NO_SPEECH -> R.string.voice_search_no_speech
    VoiceSearchError.NETWORK -> R.string.voice_search_network
    VoiceSearchError.SERVICE_UNAVAILABLE -> R.string.voice_search_unavailable
    VoiceSearchError.LANGUAGE_NOT_SUPPORTED -> R.string.voice_search_language
    VoiceSearchError.TIMEOUT -> R.string.voice_search_timeout
    VoiceSearchError.AUDIO -> R.string.voice_search_audio
    VoiceSearchError.UNKNOWN -> R.string.voice_search_error
}
