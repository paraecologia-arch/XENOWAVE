/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.utils.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/**
 * [VoiceRecognitionEngine] backed by the platform [SpeechRecognizer].
 *
 * The on-device recognizer is preferred when the system reports it as available.
 * If it cannot handle the configured language, the engine transparently falls back
 * once to the device's default recognition service. Voice input is never written
 * to a file and the platform service may use the network.
 */
class AndroidVoiceEngine(
    context: Context,
    private val callbacks: VoiceRecognitionCallbacks,
) : VoiceRecognitionEngine {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listener = PlatformRecognitionListener()

    private var recognizer: SpeechRecognizer? = null
    private var languageTag: String? = null
    private var preferOnDevice = false
    private var usingOnDevice = false
    private var triedDefaultFallback = false
    private var destroyed = false

    override fun start(languageTag: String, preferOnDevice: Boolean) {
        this.languageTag = languageTag
        this.preferOnDevice = preferOnDevice
        runOnMain { startInternal() }
    }

    override fun stop() {
        runOnMain {
            runCatching { recognizer?.stopListening() }
        }
    }

    override fun cancel() {
        runOnMain {
            runCatching { recognizer?.cancel() }
        }
    }

    override fun destroy() {
        runOnMain {
            destroyed = true
            releaseRecognizer()
        }
    }

    private fun startInternal() {
        if (destroyed) return
        if (ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            callbacks.onError(VoiceSearchError.PERMISSION_DENIED)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            callbacks.onError(VoiceSearchError.SERVICE_UNAVAILABLE)
            return
        }

        val active = recognizer ?: createRecognizer()
        if (active == null) {
            callbacks.onError(VoiceSearchError.SERVICE_UNAVAILABLE)
            return
        }
        recognizer = active

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, usingOnDevice)
            languageTag?.let {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, it)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, it)
            }
        }

        try {
            active.startListening(intent)
        } catch (t: Throwable) {
            releaseRecognizer()
            callbacks.onError(VoiceSearchError.UNKNOWN)
        }
    }

    private fun createRecognizer(): SpeechRecognizer? {
        val wantsOnDevice = preferOnDevice &&
            !triedDefaultFallback &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)

        if (wantsOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                usingOnDevice = true
                return SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
                    .also { it.setRecognitionListener(listener) }
            } catch (t: Throwable) {
                usingOnDevice = false
            }
        }

        usingOnDevice = false
        return try {
            SpeechRecognizer.createSpeechRecognizer(appContext)
                .also { it.setRecognitionListener(listener) }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * The on-device service may not support the requested language. In that case we
     * recreate the recognizer once with the device default service instead of showing
     * a dead end to the user.
     */
    private fun fallBackToDefaultRecognizer(): Boolean {
        if (!usingOnDevice || triedDefaultFallback) return false
        triedDefaultFallback = true
        releaseRecognizer()
        startInternal()
        return true
    }

    private fun releaseRecognizer() {
        recognizer?.let { active ->
            runCatching { active.cancel() }
            runCatching { active.setRecognitionListener(null) }
            runCatching { active.destroy() }
        }
        recognizer = null
        usingOnDevice = false
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private inner class PlatformRecognitionListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = callbacks.onReady()

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = callbacks.onRmsChanged(rmsdB)

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = callbacks.onEndOfSpeech()

        override fun onError(error: Int) {
            val fallbackWorthy = error == SpeechRecognizer.ERROR_CLIENT ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                        error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE))
            if (fallbackWorthy && fallBackToDefaultRecognizer()) return
            callbacks.onError(mapError(error))
        }

        override fun onResults(results: Bundle?) {
            callbacks.onFinal(firstResult(results))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = firstResult(partialResults)
            if (text.isNotBlank()) {
                callbacks.onPartial(text)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        private fun firstResult(bundle: Bundle?): String =
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull { !it.isNullOrBlank() }
                .orEmpty()
    }

    private fun mapError(error: Int): VoiceSearchError = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> VoiceSearchError.AUDIO
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceSearchError.PERMISSION_DENIED
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> VoiceSearchError.NETWORK

        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceSearchError.NO_SPEECH

        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> VoiceSearchError.BUSY

        else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
        ) {
            VoiceSearchError.LANGUAGE_NOT_SUPPORTED
        } else {
            VoiceSearchError.UNKNOWN
        }
    }
}

class AndroidVoiceTimerFactory : VoiceSearchTimerFactory {
    override fun schedule(delayMillis: Long, action: () -> Unit): VoiceSearchTimer {
        val handler = Handler(Looper.getMainLooper())
        val runnable = Runnable { action() }
        handler.postDelayed(runnable, delayMillis)
        return VoiceSearchTimer { handler.removeCallbacks(runnable) }
    }
}
