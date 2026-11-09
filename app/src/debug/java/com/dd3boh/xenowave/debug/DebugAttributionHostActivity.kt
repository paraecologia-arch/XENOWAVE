package com.dd3boh.xenowave.debug

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.navigation.compose.rememberNavController
import com.dd3boh.xenowave.ui.screens.settings.AttributionScreen
import com.dd3boh.xenowave.ui.theme.XenoWaveTheme
import java.util.Locale

/**
 * Debug-only host used by instrumentation tests. It is merged only into the
 * debug variant and is not part of the release manifest.
 *
 * The host applies the Brazilian Portuguese locale and an enlarged font scale,
 * so the credit hierarchy can be inspected on a real emulator in the same
 * conditions used by the accessibility check.
 */
@OptIn(ExperimentalMaterial3Api::class)
class DebugAttributionHostActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val configuration = Configuration(newBase.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("pt-BR"))
            fontScale = LARGE_FONT_SCALE
        }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = LARGE_FONT_SCALE),
            ) {
                XenoWaveTheme(
                    context = LocalContext.current,
                    highContrastCompat = false,
                ) {
                    AttributionScreen(
                        navController = rememberNavController(),
                        scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(),
                    )
                }
            }
        }
    }

    private companion object {
        const val LARGE_FONT_SCALE = 1.3f
    }
}
