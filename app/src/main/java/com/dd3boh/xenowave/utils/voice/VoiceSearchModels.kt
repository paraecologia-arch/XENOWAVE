/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.utils.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

enum class VoiceSearchError {
    PERMISSION_DENIED,
    BUSY,
    NO_SPEECH,
    NETWORK,
    SERVICE_UNAVAILABLE,
    LANGUAGE_NOT_SUPPORTED,
    TIMEOUT,
    AUDIO,
    UNKNOWN,
}

sealed interface VoiceSearchState {
    data object Idle : VoiceSearchState

    data class Listening(
        val partialText: String = "",
        val level: Float = 0f,
    ) : VoiceSearchState

    data class Processing(val text: String = "") : VoiceSearchState

    data class Finished(val text: String) : VoiceSearchState

    data class Failed(val error: VoiceSearchError) : VoiceSearchState
}

fun interface VoiceSearchTimer {
    fun cancel()
}

fun interface VoiceSearchTimerFactory {
    fun schedule(delayMillis: Long, action: () -> Unit): VoiceSearchTimer
}

/**
 * Small abstraction over [android.speech.SpeechRecognizer] so the session rules can be
 * exercised by unit tests without touching the platform recognizer.
 */
interface VoiceRecognitionEngine {
    fun start(languageTag: String, preferOnDevice: Boolean)
    fun stop()
    fun cancel()
    fun destroy()
}

interface VoiceRecognitionCallbacks {
    fun onReady()
    fun onRmsChanged(rmsDb: Float)
    fun onEndOfSpeech()
    fun onPartial(text: String)
    fun onFinal(text: String)
    fun onError(error: VoiceSearchError)
}

/**
 * Owns one recognition session at a time.
 *
 * Rules enforced here (and covered by tests):
 * - exactly one non-empty final result per session is delivered;
 * - partial results never finish the session;
 * - callbacks from a previous/cancelled session are ignored;
 * - a session always ends through result, error, timeout, cancel or release,
 *   so the microphone cannot stay open indefinitely.
 */
class VoiceSearchController(
    private val engineFactory: (VoiceRecognitionCallbacks) -> VoiceRecognitionEngine,
    private val timerFactory: VoiceSearchTimerFactory,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    private val mutableState = MutableStateFlow<VoiceSearchState>(VoiceSearchState.Idle)
    val state: StateFlow<VoiceSearchState> = mutableState.asStateFlow()

    private var sessionId = 0L
    private var engine: VoiceRecognitionEngine? = null
    private var timer: VoiceSearchTimer? = null

    fun start(languageTag: String, preferOnDevice: Boolean) {
        endSession()
        val id = ++sessionId
        mutableState.value = VoiceSearchState.Listening()
        timer = timerFactory.schedule(timeoutMillis) { onTimeout(id) }
        val newEngine = engineFactory(callbacksFor(id))
        engine = newEngine
        newEngine.start(languageTag, preferOnDevice)
    }

    fun retry(languageTag: String, preferOnDevice: Boolean) = start(languageTag, preferOnDevice)

    fun cancel() {
        endSession()
        mutableState.value = VoiceSearchState.Idle
    }

    fun release() {
        endSession()
        mutableState.value = VoiceSearchState.Idle
    }

    private fun callbacksFor(id: Long) = object : VoiceRecognitionCallbacks {
        override fun onReady() {
            if (!isCurrent(id)) return
            val current = mutableState.value
            if (current is VoiceSearchState.Listening) {
                mutableState.value = current.copy(level = 0f)
            }
        }

        override fun onRmsChanged(rmsDb: Float) {
            if (!isCurrent(id)) return
            val current = mutableState.value as? VoiceSearchState.Listening ?: return
            val level = normalizeRms(rmsDb)
            // SpeechRecognizer emits RMS very often; skip imperceptible changes.
            if (abs(level - current.level) < RMS_UPDATE_THRESHOLD) return
            mutableState.value = current.copy(level = level)
        }

        override fun onEndOfSpeech() {
            if (!isCurrent(id)) return
            val current = mutableState.value
            if (current is VoiceSearchState.Listening) {
                mutableState.value = VoiceSearchState.Processing(current.partialText)
            }
        }

        override fun onPartial(text: String) {
            if (!isCurrent(id)) return
            val current = mutableState.value as? VoiceSearchState.Listening ?: return
            val normalized = normalizeQuery(text)
            if (normalized.isNotEmpty()) {
                mutableState.value = current.copy(partialText = normalized)
            }
        }

        override fun onFinal(text: String) {
            if (!isCurrent(id)) return
            val normalized = normalizeQuery(text)
            if (normalized.isEmpty()) {
                fail(id, VoiceSearchError.NO_SPEECH)
                return
            }
            endSession()
            mutableState.value = VoiceSearchState.Finished(normalized)
        }

        override fun onError(error: VoiceSearchError) {
            fail(id, error)
        }
    }

    private fun onTimeout(id: Long) {
        fail(id, VoiceSearchError.TIMEOUT)
    }

    private fun fail(id: Long, error: VoiceSearchError) {
        if (!isCurrent(id)) return
        endSession()
        mutableState.value = VoiceSearchState.Failed(error)
    }

    private fun isCurrent(id: Long): Boolean = id == sessionId

    private fun endSession() {
        // Invalidate the session before touching the engine so late callbacks are ignored.
        sessionId++
        timer?.cancel()
        timer = null
        engine?.let { active ->
            runCatching { active.cancel() }
            runCatching { active.destroy() }
        }
        engine = null
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 15_000L
        private const val RMS_UPDATE_THRESHOLD = 0.02f

        fun normalizeQuery(text: String): String =
            text.trim().replace(Regex("\\s+"), " ")

        /**
         * SpeechRecognizer reports RMS in dB, usually between -2 and 12.
         * Map it to a stable 0..1 value for the visual pulse.
         */
        fun normalizeRms(rmsDb: Float): Float =
            ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
    }
}
