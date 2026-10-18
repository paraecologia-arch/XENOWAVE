package com.dd3boh.xenowave.ui.icons

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dd3boh.xenowave.ui.theme.xenoWaveColors

object XenoIcons {
    val AutoMirrored = XenoAutoMirroredIcons
}

object XenoAutoMirroredIcons {
    @get:DrawableRes val ArrowBack get() = XenoNavigationIcons.ArrowBack
    @get:DrawableRes val ArrowForward get() = XenoNavigationIcons.ArrowForward
    @get:DrawableRes val Input get() = XenoNavigationIcons.Input
    @get:DrawableRes val LibraryBooks get() = XenoLibraryIcons.LibraryBooks
    @get:DrawableRes val List get() = XenoNavigationIcons.List
    @get:DrawableRes val Logout get() = XenoSystemIcons.Logout
    @get:DrawableRes val ManageSearch get() = XenoNavigationIcons.ManageSearch
    @get:DrawableRes val NavigateBefore get() = XenoNavigationIcons.NavigateBefore
    @get:DrawableRes val NavigateNext get() = XenoNavigationIcons.NavigateNext
    @get:DrawableRes val PlaylistAdd get() = XenoLibraryIcons.PlaylistAdd
    @get:DrawableRes val PlaylistPlay get() = XenoPlayerIcons.PlaylistPlay
    @get:DrawableRes val QueueMusic get() = XenoLibraryIcons.QueueMusic
    @get:DrawableRes val Sort get() = XenoNavigationIcons.Sort
    @get:DrawableRes val TrendingUp get() = XenoNavigationIcons.TrendingUp
    @get:DrawableRes val VolumeUp get() = XenoPlayerIcons.VolumeUp
}

@Composable
fun XenoIcon(
    @DrawableRes icon: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    accent: Boolean = false,
    autoMirror: Boolean = false,
    tint: Color? = null,
    iconSize: Dp = 24.dp,
) {
    val colors = xenoWaveColors()
    val resolvedTint = tint ?: when {
        !enabled -> colors.textDisabled
        accent -> colors.support
        selected -> colors.primary
        else -> colors.textPrimary
    }
    val glowColor = if (selected && enabled) colors.signal else colors.glow
    val mirror = autoMirror && LocalLayoutDirection.current == LayoutDirection.Rtl
    val semantics = if (contentDescription == null) {
        Modifier.clearAndSetSemantics { }
    } else {
        Modifier.clearAndSetSemantics { this.contentDescription = contentDescription }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .then(semantics)
            .drawBehind {
                if (enabled) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(glowColor, Color.Transparent),
                            radius = size.minDimension * 0.72f,
                        ),
                        radius = size.minDimension * 0.72f,
                    )
                }
            },
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(resolvedTint, BlendMode.SrcIn),
            modifier = Modifier
            .size(iconSize)
            .graphicsLayer(scaleX = if (mirror) -1f else 1f),
        )
    }
}
