package com.dd3boh.xenowave.visual

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.debug.DebugMusicalAgeCardHostActivity
import com.dd3boh.xenowave.debug.DebugMusicalAgeFixtures
import com.dd3boh.xenowave.insights.musicalage.MusicalGeneration
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-level proof of the production reveal state machine.
 *
 * The host forces animation on (the CI emulator boots with animations
 * disabled, which otherwise sets the production reduced-motion path), and the
 * Compose test clock is used for deterministic frames. The debug host also
 * exposes the same controls a real debug preview uses: reduced-motion override,
 * an explicit replay and an unrelated recomposition. None of these are
 * packaged in release.
 */
@RunWith(AndroidJUnit4::class)
class MusicalAgeRevealDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<DebugMusicalAgeCardHostActivity>()

    @After
    fun restoreClock() {
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun skipLeadsToTheCompleteCardWithAccessibleActions() {
        compose.mainClock.autoAdvance = false
        restartReveal()
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithText("Pular").assertIsDisplayed()
        compose.onNodeWithText("Compartilhar cartão").assertDoesNotExist()

        compose.onNodeWithText("Pular").performClick()
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithText("Pular").assertDoesNotExist()

        assertActionsAreVisibleAndClickable()
        assertAccessibleCardDescription(age = 20, MusicalGeneration.GENERATION_Z)
    }

    @Test
    fun presentationEndsWithoutUserAction() {
        compose.mainClock.autoAdvance = false
        restartReveal()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Pular").assertIsDisplayed()

        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithText("Pular").assertDoesNotExist()
        assertActionsAreVisibleAndClickable()
    }

    @Test
    fun reducedMotionDuringProgressLandsOnTheFinalAccessibleCard() {
        compose.mainClock.autoAdvance = false
        restartReveal()
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithText("Pular").assertIsDisplayed()

        compose.onNodeWithText("Reduzir movimento").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(80)

        compose.onNodeWithText("Pular").assertDoesNotExist()
        assertActionsAreVisibleAndClickable()
    }

    @Test
    fun backgroundAndReturnDoNotRepeatTheTrip() {
        compose.mainClock.autoAdvance = false
        restartReveal()
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithText("Pular").assertIsDisplayed()

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithText("Pular").assertDoesNotExist()
        assertActionsAreVisibleAndClickable()
        compose.mainClock.advanceTimeBy(5_000)
        compose.onNodeWithText("Pular").assertDoesNotExist()
    }

    @Test
    fun unrelatedRecompositionKeepsTheFinishedState() {
        compose.mainClock.autoAdvance = false
        restartReveal()
        compose.mainClock.advanceTimeBy(4_000)
        assertActionsAreVisibleAndClickable()

        compose.onNodeWithText("Recompor").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(16)
        compose.onNodeWithText("Pular").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(5_000)
        compose.onNodeWithText("Pular").assertDoesNotExist()
        compose.onNodeWithText("Baixar XENOWAVE").assertExists()
    }

    @Test
    fun onlyTheExplicitReplayStartsANewTrip() {
        compose.mainClock.autoAdvance = false
        restartReveal()
        compose.mainClock.advanceTimeBy(4_000)
        compose.onNodeWithText("Pular").assertDoesNotExist()

        compose.onNodeWithText("Repetir").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithText("Pular").assertIsDisplayed()
        compose.onNodeWithText("Compartilhar cartão").assertDoesNotExist()
    }

    private fun restartReveal() {
        compose.onNodeWithText("Repetir").performScrollTo().performClick()
        compose.mainClock.advanceTimeByFrame()
    }

    private fun assertActionsAreVisibleAndClickable() {
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
        val description = compose.activity.getString(
            R.string.musical_age_card_description,
            result.musicalAge,
            result.generation.label,
            result.dominantWindow.fromYear,
            result.dominantWindow.toYear,
        )
        compose.onNodeWithContentDescription(description).assertExists()
    }
}
