package com.dd3boh.xenowave.visual

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.SystemClock
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.debug.DebugMusicalAgeCardHostActivity
import com.dd3boh.xenowave.debug.DebugMusicalAgeCardHostActivity.Companion.ANIMATION_ON
import com.dd3boh.xenowave.debug.DebugMusicalAgeCardHostActivity.Companion.CASE_JOVIAL
import com.dd3boh.xenowave.debug.DebugMusicalAgeCardHostActivity.Companion.EXTRA_ANIMATION
import com.dd3boh.xenowave.debug.DebugMusicalAgeCardHostActivity.Companion.EXTRA_CASE
import com.dd3boh.xenowave.debug.DebugMusicalAgeCardHostActivity.Companion.EXTRA_CONTROLS
import com.dd3boh.xenowave.debug.DebugMusicalAgeFixtures
import com.dd3boh.xenowave.insights.musicalage.MusicalGeneration
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Device-level proof of the production reveal state machine.
 *
 * The CI emulator boots with animations disabled, so the host forces the
 * production animation path (`reduceMotionOverride=false`). UiAutomator
 * performs the real touches while the Compose test clock is paused; this
 * avoids `waitForIdle` waiting forever for an animation that is deliberately
 * not advancing. Once the card is finished, the normal Compose test tree is
 * used to assert that every action is displayed and clickable.
 */
@RunWith(AndroidJUnit4::class)
class MusicalAgeRevealDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var device: UiDevice
    private lateinit var scenario: ActivityScenario<DebugMusicalAgeCardHostActivity>

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // Pause the Compose frame clock before the activity composes, so the
        // reveal stays at its initial frame until the test advances time.
        compose.mainClock.autoAdvance = false
        scenario = ActivityScenario.launch(
            Intent(
                ApplicationProvider.getApplicationContext(),
                DebugMusicalAgeCardHostActivity::class.java,
            )
                .putExtra(EXTRA_CASE, CASE_JOVIAL)
                .putExtra(EXTRA_ANIMATION, ANIMATION_ON)
                .putExtra(EXTRA_CONTROLS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        // The paused clock still needs one frame to compose, measure, lay out
        // and expose the initial card to UiAutomator.
        compose.mainClock.advanceTimeByFrame()
    }

    @After
    fun tearDown() {
        compose.mainClock.autoAdvance = true
        scenario.close()
    }

    @Test
    fun skipLeadsToTheCompleteCardWithAccessibleActions() {
        assertTrue("Skip must be visible during the reveal", waitForText("Pular"))
        assertFalse(
            "Actions must stay hidden during the reveal",
            device.hasObject(By.text("Compartilhar cartão")),
        )

        clickText("Pular")

        assertTrue("Skip must disappear after the tap", waitGone("Pular"))
        assertTrue(
            "The complete card must appear after Skip",
            waitForText("Compartilhar cartão"),
        )
        assertActionsAreVisibleAndClickable()
        assertAccessibleCardDescription(age = 20, MusicalGeneration.GENERATION_Z)
    }

    @Test
    fun presentationEndsWithoutUserAction() {
        assertTrue("Skip must be visible at the start", waitForText("Pular"))
        assertTrue(
            "The reveal must finish without a tap",
            waitForText("Compartilhar cartão", timeoutMillis = 8_000),
        )
        assertTrue("Skip must be gone at the end", waitGone("Pular"))
        assertActionsAreVisibleAndClickable()
    }

    @Test
    fun reducedMotionDuringProgressLandsOnTheFinalAccessibleCard() {
        assertTrue("Skip must be visible during the reveal", waitForText("Pular"))
        clickText("Reduzir movimento")

        assertTrue(
            "Reduced motion must land directly on the final card",
            waitForText("Compartilhar cartão", timeoutMillis = 3_000),
        )
        assertTrue("The final card must not keep Skip", waitGone("Pular"))
        assertActionsAreVisibleAndClickable()
    }

    @Test
    fun backgroundAndReturnDoNotRepeatTheTrip() {
        assertTrue("Skip must be visible during the reveal", waitForText("Pular"))

        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        compose.mainClock.advanceTimeByFrame()

        assertTrue(
            "Returning from the background must show the finished card",
            waitForText("Compartilhar cartão", timeoutMillis = 3_000),
        )
        assertTrue("Returning must not restart the reveal", waitGone("Pular"))
        compose.mainClock.advanceTimeBy(5_000)
        assertTrue("The reveal must not loop", waitGone("Pular"))
    }

    @Test
    fun unrelatedRecompositionKeepsTheFinishedState() {
        assertTrue(
            "The reveal must finish before recomposition",
            waitForText("Compartilhar cartão", timeoutMillis = 8_000),
        )
        assertTrue("Skip must be gone before recomposition", waitGone("Pular"))

        clickText("Recompor")
        compose.mainClock.advanceTimeBy(80)
        assertFalse(
            "An unrelated recomposition must not restart the reveal",
            waitForText("Pular", timeoutMillis = 500),
        )
        compose.mainClock.advanceTimeBy(5_000)
        assertTrue("The finished card must stay finished", waitGone("Pular"))
        assertActionsAreVisibleAndClickable()
    }

    @Test
    fun onlyTheExplicitReplayStartsANewTrip() {
        assertTrue(
            "The reveal must finish first",
            waitForText("Compartilhar cartão", timeoutMillis = 8_000),
        )

        clickText("Repetir")

        assertTrue("The explicit debug replay must start a new trip", waitForText("Pular"))
        assertFalse(
            "Actions must hide again during the new trip",
            device.hasObject(By.text("Compartilhar cartão")),
        )
    }

    private fun assertActionsAreVisibleAndClickable() {
        // The card is finished, so the normal Compose test tree is idle.
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Baixar XENOWAVE")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
        compose.onNodeWithText("Compartilhar cartão")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
        compose.onNodeWithText("Salvar imagem")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
        compose.onNodeWithText("Copiar link")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    private fun assertAccessibleCardDescription(age: Int, generation: MusicalGeneration) {
        val result = DebugMusicalAgeFixtures.ready(generation, age)
        val description = ptContext().getString(
            R.string.musical_age_card_description,
            result.musicalAge,
            result.generation.label,
            result.dominantWindow.fromYear,
            result.dominantWindow.toYear,
        )
        compose.onNodeWithContentDescription(description).assertExists()
    }

    private fun clickText(text: String) {
        val node = device.wait(Until.findObject(By.text(text)), 5_000)
            ?: error("Could not find '$text'")
        node.click()
        compose.mainClock.advanceTimeByFrame()
    }

    private fun waitForText(text: String, timeoutMillis: Long = 5_000): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (device.hasObject(By.text(text))) return true
            compose.mainClock.advanceTimeBy(32)
            Thread.sleep(10)
        }
        return device.hasObject(By.text(text))
    }

    private fun waitGone(text: String, timeoutMillis: Long = 5_000): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (!device.hasObject(By.text(text))) return true
            compose.mainClock.advanceTimeBy(32)
            Thread.sleep(10)
        }
        return !device.hasObject(By.text(text))
    }

    private fun ptContext(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("pt-BR"))
        }
        return base.createConfigurationContext(configuration)
    }
}
