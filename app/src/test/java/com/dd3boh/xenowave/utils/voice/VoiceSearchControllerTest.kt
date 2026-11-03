package com.dd3boh.xenowave.utils.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSearchControllerTest {

    private class FakeEngine(
        val callbacks: VoiceRecognitionCallbacks,
    ) : VoiceRecognitionEngine {
        var started = false
        var cancelled = false
        var destroyed = false
        var languageTag: String? = null
        var preferOnDevice: Boolean? = null

        override fun start(languageTag: String, preferOnDevice: Boolean) {
            started = true
            this.languageTag = languageTag
            this.preferOnDevice = preferOnDevice
        }

        override fun stop() = Unit

        override fun cancel() {
            cancelled = true
        }

        override fun destroy() {
            destroyed = true
        }
    }

    private class FakeEngineFactory : (VoiceRecognitionCallbacks) -> VoiceRecognitionEngine {
        val engines = mutableListOf<FakeEngine>()

        override fun invoke(callbacks: VoiceRecognitionCallbacks): VoiceRecognitionEngine =
            FakeEngine(callbacks).also { engines.add(it) }

        val latest: FakeEngine
            get() = engines.last()
    }

    private class FakeTimer(private val action: () -> Unit) : VoiceSearchTimer {
        var cancelled = false

        fun fire() {
            action()
        }

        override fun cancel() {
            cancelled = true
        }
    }

    private class FakeTimerFactory : VoiceSearchTimerFactory {
        val timers = mutableListOf<FakeTimer>()
        var lastDelayMillis: Long = -1L

        override fun schedule(delayMillis: Long, action: () -> Unit): VoiceSearchTimer =
            FakeTimer(action).also {
                lastDelayMillis = delayMillis
                timers.add(it)
            }

        val latest: FakeTimer
            get() = timers.last()
    }

    private fun controller(
        engines: FakeEngineFactory = FakeEngineFactory(),
        timers: FakeTimerFactory = FakeTimerFactory(),
    ): Triple<VoiceSearchController, FakeEngineFactory, FakeTimerFactory> =
        Triple(
            VoiceSearchController(
                engineFactory = engines,
                timerFactory = timers,
                timeoutMillis = 1_000L,
            ),
            engines,
            timers,
        )

    @Test
    fun `final result is trimmed and delivered exactly once`() {
        val (controller, engines, timers) = controller()

        controller.start("pt-BR", preferOnDevice = true)
        val engine = engines.latest
        assertEquals("pt-BR", engine.languageTag)
        assertTrue(engine.preferOnDevice == true)
        assertEquals(VoiceSearchState.Listening(), controller.state.value)
        assertEquals(1_000L, timers.lastDelayMillis)

        engine.callbacks.onFinal("  Ciranda   Cirandinha ")

        assertEquals(
            VoiceSearchState.Finished("Ciranda Cirandinha"),
            controller.state.value
        )
        assertTrue(timers.latest.cancelled)
        assertTrue(engine.cancelled)
        assertTrue(engine.destroyed)

        // A late second result from the same session must be ignored.
        engine.callbacks.onFinal("Outra música")
        assertEquals(
            VoiceSearchState.Finished("Ciranda Cirandinha"),
            controller.state.value
        )
        assertEquals(1, engines.engines.size)
    }

    @Test
    fun `partial results update the dialog without finishing the session`() {
        val (controller, engines, timers) = controller()
        controller.start("pt-BR", preferOnDevice = false)
        val callbacks = engines.latest.callbacks

        callbacks.onPartial("  Peixe  ")
        assertTrue(controller.state.value is VoiceSearchState.Listening)
        assertFalse(timers.latest.cancelled)

        callbacks.onPartial("Peixe vivo")
        assertEquals(
            VoiceSearchState.Listening(partialText = "Peixe vivo", level = 0f),
            controller.state.value
        )
    }

    @Test
    fun `rms level is normalised and keeps partial text`() {
        val (controller, engines, _) = controller()
        controller.start("pt-BR", preferOnDevice = false)
        val callbacks = engines.latest.callbacks

        callbacks.onPartial("Marcha soldado")
        callbacks.onRmsChanged(10f)
        val state = controller.state.value as VoiceSearchState.Listening
        assertEquals("Marcha soldado", state.partialText)
        assertEquals(1f, state.level, 0.001f)

        callbacks.onRmsChanged(-10f)
        assertEquals(0f, (controller.state.value as VoiceSearchState.Listening).level, 0.001f)
    }

    @Test
    fun `end of speech moves to processing and keeps the partial text`() {
        val (controller, engines, _) = controller()
        controller.start("pt-BR", preferOnDevice = false)
        val callbacks = engines.latest.callbacks

        callbacks.onPartial("Borboletinha")
        callbacks.onEndOfSpeech()

        assertEquals(VoiceSearchState.Processing("Borboletinha"), controller.state.value)
    }

    @Test
    fun `cancel ignores callbacks from the finished session`() {
        val (controller, engines, timers) = controller()
        controller.start("pt-BR", preferOnDevice = false)
        val engine = engines.latest

        controller.cancel()

        assertEquals(VoiceSearchState.Idle, controller.state.value)
        assertTrue(timers.latest.cancelled)
        assertTrue(engine.cancelled)
        assertTrue(engine.destroyed)

        engine.callbacks.onFinal("Não deve pesquisar")
        engine.callbacks.onError(VoiceSearchError.UNKNOWN)
        assertEquals(VoiceSearchState.Idle, controller.state.value)
    }

    @Test
    fun `permission denial surfaces as an error and ignores late results`() {
        val (controller, engines, _) = controller()
        controller.start("pt-BR", preferOnDevice = false)
        val engine = engines.latest

        engine.callbacks.onError(VoiceSearchError.PERMISSION_DENIED)
        assertEquals(
            VoiceSearchState.Failed(VoiceSearchError.PERMISSION_DENIED),
            controller.state.value
        )
        assertTrue(engine.destroyed)

        engine.callbacks.onFinal("Nada")
        assertEquals(
            VoiceSearchState.Failed(VoiceSearchError.PERMISSION_DENIED),
            controller.state.value
        )
    }

    @Test
    fun `timeout ends the session and releases the microphone`() {
        val (controller, engines, timers) = controller()
        controller.start("pt-BR", preferOnDevice = false)
        val engine = engines.latest

        timers.latest.fire()

        assertEquals(
            VoiceSearchState.Failed(VoiceSearchError.TIMEOUT),
            controller.state.value
        )
        assertTrue(engine.destroyed)

        engine.callbacks.onFinal("Atrasado")
        assertEquals(
            VoiceSearchState.Failed(VoiceSearchError.TIMEOUT),
            controller.state.value
        )
    }

    @Test
    fun `retry creates a new session and discards the previous callbacks`() {
        val (controller, engines, timers) = controller()
        controller.start("pt-BR", preferOnDevice = false)
        val first = engines.latest
        val firstTimer = timers.latest

        controller.retry("pt-BR", preferOnDevice = false)
        val second = engines.latest

        assertEquals(2, engines.engines.size)
        assertTrue(firstTimer.cancelled)
        assertTrue(first.destroyed)
        assertFalse(second.destroyed)

        first.callbacks.onFinal("Antiga")
        assertEquals(VoiceSearchState.Listening(), controller.state.value)

        second.callbacks.onFinal("Nova")
        assertEquals(VoiceSearchState.Finished("Nova"), controller.state.value)
    }

    @Test
    fun `empty final result is reported as no speech`() {
        val (controller, engines, _) = controller()
        controller.start("pt-BR", preferOnDevice = false)

        engines.latest.callbacks.onFinal("   ")

        assertEquals(
            VoiceSearchState.Failed(VoiceSearchError.NO_SPEECH),
            controller.state.value
        )
    }

    @Test
    fun `release ends the session and destroys the engine`() {
        val (controller, engines, timers) = controller()
        controller.start("pt-BR", preferOnDevice = false)
        val engine = engines.latest

        controller.release()

        assertEquals(VoiceSearchState.Idle, controller.state.value)
        assertTrue(timers.latest.cancelled)
        assertTrue(engine.cancelled)
        assertTrue(engine.destroyed)
    }

    @Test
    fun `rms normalisation clamps values to zero and one`() {
        assertEquals(0f, VoiceSearchController.normalizeRms(-40f), 0.001f)
        assertEquals(1f, VoiceSearchController.normalizeRms(40f), 0.001f)
        assertEquals(0.5f, VoiceSearchController.normalizeRms(4f), 0.001f)
    }
}
