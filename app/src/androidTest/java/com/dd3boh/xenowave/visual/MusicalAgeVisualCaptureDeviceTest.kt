package com.dd3boh.xenowave.visual

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.debug.DebugMusicalAgeCardHostActivity
import com.dd3boh.xenowave.debug.DebugMusicalAgeCardHostActivity.Companion.ANIMATION_REDUCED
import com.dd3boh.xenowave.debug.DebugMusicalAgeCardHostActivity.Companion.CASE_INSUFFICIENT
import com.dd3boh.xenowave.debug.DebugMusicalAgeCardHostActivity.Companion.CASE_JOVIAL
import com.dd3boh.xenowave.debug.DebugMusicalAgeCardHostActivity.Companion.CASE_MATURE
import com.dd3boh.xenowave.debug.DebugMusicalAgeCardHostActivity.Companion.CASE_MIXED
import com.dd3boh.xenowave.debug.DebugMusicalAgeCardHostActivity.Companion.CASE_VETERAN
import com.dd3boh.xenowave.debug.DebugMusicalAgeCardHostActivity.Companion.EXTRA_ANIMATION
import com.dd3boh.xenowave.debug.DebugMusicalAgeCardHostActivity.Companion.EXTRA_CASE
import com.dd3boh.xenowave.debug.DebugMusicalAgeCardHostActivity.Companion.EXTRA_CONTROLS
import com.dd3boh.xenowave.debug.DebugMusicalAgeCardHostActivity.Companion.EXTRA_FONT_SCALE
import com.dd3boh.xenowave.debug.DebugMusicalAgeFixtures
import com.dd3boh.xenowave.insights.musicalage.MusicalGeneration
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Real emulator screenshots of the production ready card.
 *
 * These are Compose captures from the API 35 emulator, not Robolectric
 * previews. The host uses synthetic fixtures and the pt-BR locale; the card is
 * captured at the top, at the download CTA and at the bottom actions so a
 * clipped action cannot be hidden by a single screenshot.
 */
@RunWith(AndroidJUnit4::class)
class MusicalAgeVisualCaptureDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun captureReadyCasesIncludingThreeDigitAgeAndLongGeneration() {
        val cases = listOf(
            CaptureCase("jovial", CASE_JOVIAL, 20, MusicalGeneration.GENERATION_Z, 1f),
            CaptureCase("mature", CASE_MATURE, 44, MusicalGeneration.MILLENNIAL, 1f),
            CaptureCase("veteran", CASE_VETERAN, 194, MusicalGeneration.CLASSICS, 1f),
            CaptureCase("veteran-largefont", CASE_VETERAN, 194, MusicalGeneration.CLASSICS, 1.5f),
        )

        cases.forEach { case ->
            ActivityScenario.launch<DebugMusicalAgeCardHostActivity>(intent(case)).use {
                compose.waitForIdle()
                compose.onNodeWithText(case.age.toString()).assertExists()
                compose.onNodeWithText(case.generation.label).assertExists()
                compose.onNodeWithContentDescription(cardDescription(case.age, case.generation))
                    .assertExists()

                compose.onNodeWithText(DEBUG_BANNER).performScrollTo()
                DeviceTestOutput.capture(compose, "ui-${case.name}-top.png")

                compose.onNodeWithText("Baixar XENOWAVE")
                    .performScrollTo()
                    .assertIsDisplayed()
                    .assertHasClickAction()
                DeviceTestOutput.capture(compose, "ui-${case.name}-cta.png")

                compose.onNodeWithText("Copiar link")
                    .performScrollTo()
                    .assertIsDisplayed()
                    .assertHasClickAction()
                DeviceTestOutput.capture(compose, "ui-${case.name}-actions.png")
            }
        }
    }

    @Test
    fun mixedAndInsufficientCasesNeverShowAnInventedAge() {
        listOf(
            "mixed" to CASE_MIXED,
            "insufficient" to CASE_INSUFFICIENT,
        ).forEach { (name, requestedCase) ->
            ActivityScenario.launch<DebugMusicalAgeCardHostActivity>(
                intent(CaptureCase(name, requestedCase, 0, MusicalGeneration.MILLENNIAL, 1f)),
            ).use {
                compose.waitForIdle()
                compose.onNodeWithText("ANOS").assertDoesNotExist()
                compose.onNodeWithText("Baixar XENOWAVE").performScrollTo().assertIsDisplayed()
                DeviceTestOutput.capture(compose, "ui-$name.png")
            }
        }
    }

    private fun intent(case: CaptureCase): Intent =
        Intent(
            ApplicationProvider.getApplicationContext(),
            DebugMusicalAgeCardHostActivity::class.java,
        )
            .putExtra(EXTRA_CASE, case.requestedCase)
            .putExtra(EXTRA_ANIMATION, ANIMATION_REDUCED)
            .putExtra(EXTRA_CONTROLS, false)
            .putExtra(EXTRA_FONT_SCALE, case.fontScale)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun cardDescription(age: Int, generation: MusicalGeneration): String {
        val result = DebugMusicalAgeFixtures.ready(generation, age)
        return ptContext().getString(
            R.string.musical_age_card_description,
            result.musicalAge,
            result.generation.label,
            result.dominantWindow.fromYear,
            result.dominantWindow.toYear,
        )
    }

    private fun ptContext(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("pt-BR"))
        }
        return base.createConfigurationContext(configuration)
    }

    private data class CaptureCase(
        val name: String,
        val requestedCase: String,
        val age: Int,
        val generation: MusicalGeneration,
        val fontScale: Float,
    )

    private companion object {
        const val DEBUG_BANNER = "DEBUG FIXTURE · dados sintéticos · sem conta pessoal"
    }
}
