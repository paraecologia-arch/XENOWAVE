package com.dd3boh.xenowave.insights.musicalage.ui.visual

import android.content.Context
import androidx.core.content.FileProvider
import androidx.core.content.WindowsSafePathStrategy
import java.io.File

/**
 * androidx FileProvider's root check appends a hard-coded '/', which never
 * matches Windows canonical paths ('\'). Install a strategy implementing the
 * real FileProvider.PathStrategy contract with separator normalization, while
 * tests still call FileProvider.getUriForFile. Production code is untouched.
 */
internal fun installWindowsSafeFileProvider(context: Context): WindowsSafePathStrategy {
    val authority = "${context.packageName}.FileProvider"
    val strategy = WindowsSafePathStrategy(authority)
    strategy.addRoot(
        "musical_age_cards",
        File(context.cacheDir, MusicalAgeCardFiles.DIRECTORY_NAME),
    )
    strategy.addRoot(
        "playlist_exports",
        File(context.cacheDir, "playlist-exports"),
    )
    val field = FileProvider::class.java.getDeclaredField("sCache")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val cache = field.get(null) as MutableMap<String, Any>
    cache[authority] = strategy
    return strategy
}
