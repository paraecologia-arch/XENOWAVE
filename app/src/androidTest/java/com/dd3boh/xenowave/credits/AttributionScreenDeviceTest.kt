package com.dd3boh.xenowave.credits

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.debug.DebugAttributionHostActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Device-level visual check for the credit hierarchy.
 *
 * The host opens the real [com.dd3boh.xenowave.ui.screens.settings.AttributionScreen]
 * in Brazilian Portuguese with an enlarged font scale. The assertions only
 * cover the requested hierarchy: XENOWAVE authorship is visible before any
 * scrolling, the base projects are reachable separately, and the open source
 * licences stay accessible. Screenshots are written to the additional test
 * output directory uploaded by CI.
 */
@RunWith(AndroidJUnit4::class)
class AttributionScreenDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<DebugAttributionHostActivity>()

    @Test
    fun xenowaveAuthorshipIsFirstAndBaseProjectsStaySeparate() {
        val creator = compose.activity.getString(R.string.xenowave_creator_name)

        compose.onNodeWithText(compose.activity.getString(R.string.xenowave_creation_section))
            .assertIsDisplayed()
        compose.onNodeWithText(creator)
            .assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.xenowave_creator_role))
            .assertIsDisplayed()
        capture("credits-01-xenowave-first.png")

        compose.onNodeWithText(compose.activity.getString(R.string.xenowave_base_section))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Davide Garberi")
            .performScrollTo()
            .assertIsDisplayed()
        capture("credits-02-base-projects.png")

        compose.onNodeWithText(compose.activity.getString(R.string.xenowave_contributors_outertune))
            .performScrollTo()
            .assertIsDisplayed()

        compose.onNodeWithText(compose.activity.getString(R.string.oss_licenses_title))
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
        capture("credits-03-licenses.png")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val outputDir = InstrumentationRegistry.getArguments()
            .getString("additionalTestOutputDir")
            ?: InstrumentationRegistry.getInstrumentation()
                .targetContext
                .getExternalFilesDir(null)
                ?.absolutePath
            ?: error("No additional test output directory available")
        val file = File(outputDir, name)
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
