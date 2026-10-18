package com.dd3boh.xenowave.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dd3boh.xenowave.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Ensures the branded sequence runs once per process, not on activity recreation or warm return. */
internal class StartupGate {
    private val shown = AtomicBoolean(false)

    fun acquire(): Boolean = shown.compareAndSet(false, true)
}

internal object XenoWaveStartupGate {
    private val processGate = StartupGate()

    fun acquire(): Boolean = processGate.acquire()
}

@Composable
fun XenoWaveStartupSplash(onFinished: () -> Unit) {
    val splashLight = colorResource(R.color.xenowave_primary_text)
    val splashBlue = colorResource(R.color.xenowave_primary_blue)
    val splashDark = colorResource(R.color.xenowave_space_background)
    val splashCyan = colorResource(R.color.xenowave_luminous_cyan)
    val splashPurple = colorResource(R.color.xenowave_support_purple)
    val backgroundProgress = remember { Animatable(0f) }
    val markAlpha = remember { Animatable(0f) }
    val markScale = remember { Animatable(0.94f) }
    val glowAlpha = remember { Animatable(0f) }
    var showName by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        launch {
            delay(200)
            markAlpha.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        }
        launch {
            delay(200)
            markScale.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        }
        launch {
            delay(350)
            glowAlpha.animateTo(0.38f, tween(1250, easing = FastOutSlowInEasing))
        }
        launch {
            delay(400)
            backgroundProgress.animateTo(1f, tween(1200, easing = LinearEasing))
        }
        delay(1700)
        showName = true
        delay(700)
        onFinished()
    }

    val background = when {
        backgroundProgress.value < 0.5f -> lerp(
            splashLight,
            splashBlue,
            backgroundProgress.value * 2f,
        )
        else -> lerp(
            splashBlue,
            splashDark,
            (backgroundProgress.value - 0.5f) * 2f,
        )
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(background),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(220.dp)
                    .drawBehind {
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(
                                    splashCyan.copy(alpha = glowAlpha.value),
                                    splashPurple.copy(alpha = glowAlpha.value * 0.55f),
                                    Color.Transparent,
                                )
                            ),
                            radius = size.minDimension * 0.5f,
                        )
                    },
            ) {
                Image(
                    painter = painterResource(R.drawable.xenowave_splash_icon),
                    contentDescription = null,
                    modifier = Modifier
                        .size(192.dp)
                        .graphicsLayer {
                            alpha = markAlpha.value
                            scaleX = markScale.value
                            scaleY = markScale.value
                        },
                )
            }
            Spacer(Modifier.height(8.dp))
            AnimatedVisibility(
                visible = showName,
                enter = fadeIn(tween(450)),
            ) {
                Text(
                    text = "XENOWAVE",
                    color = splashLight,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 4.sp,
                )
            }
        }
    }
}
