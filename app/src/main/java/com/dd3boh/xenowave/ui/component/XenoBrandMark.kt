package com.dd3boh.xenowave.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.ui.theme.xenoWaveColors

@Composable
fun XenoBrandMark(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    contentDescription: String? = null,
    activated: Boolean = false,
) {
    val colors = xenoWaveColors()
    val glowAlpha by animateFloatAsState(
        targetValue = if (activated) 0.42f else 0.16f,
        animationSpec = tween(durationMillis = 450),
        label = "XENOWAVE signal glow",
    )
    val scale by animateFloatAsState(
        targetValue = if (activated) 1.035f else 1f,
        animationSpec = tween(durationMillis = 450),
        label = "XENOWAVE signal pulse",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            colors.signal.copy(alpha = glowAlpha),
                            colors.support.copy(alpha = glowAlpha * 0.45f),
                            Color.Transparent,
                        ),
                        radius = this.size.minDimension * 0.48f,
                    ),
                    radius = this.size.minDimension * 0.48f,
                )
            },
    ) {
        Image(
            painter = painterResource(R.drawable.xenowave_splash_icon),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha = if (activated) 1f else 0.9f
                },
        )
    }
}
