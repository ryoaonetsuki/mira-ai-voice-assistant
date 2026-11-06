package io.github.ryoaonetsuki.mira.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fallback voice session using Android's SpeechRecognizer when Gemini Live
 * fails to connect. Wraps SpeechRecognizer on the main thread with auto re-listening,
 * language mapping, partial transcript updates, and clean lifecycle release.
 */
class FallbackVoiceSession(
    private val context: Context,
    private val languageCode: String = "bn-BD",
    private val onFinalText: (String) -> Unit,
    private val onPartialText: (String) -> Unit,
    private val onListeningChanged: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val onAmplitude: (Float) -> Unit = {},
    private val onSpeechStart: () -> Unit = {}
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private val isActive = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private var recreatedAfterBusy = false
    private var lastErrorCode: Int? = null
    private var consecutiveErrorCount: Int = 0

    private val restartRunnable = Runnable {
        if (isActive.get() && !isPaused.get()) {
            startListeningInternal()
        }
    }

    fun start() {
        mainHandler.post {
            if (isActive.get()) {
                Log.w(TAG, "FallbackVoiceSession already started")
                return@post
            }

            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.e(TAG, "SpeechRecognizer is NOT available on this device")
                onError("Voice recognition isn't available on this device — please use text chat")
                return@post
            }

            isActive.set(true)
            isPaused.set(false)
            recreatedAfterBusy = false
            lastErrorCode = null
            consecutiveErrorCount = 0
            initRecognizer()
            startListeningInternal()
        }
    }

    private fun initRecognizer() {
        try {
            destroyRecognizer()
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(createListener())
            }
            Log.i(TAG, "SpeechRecognizer initialized successfully for language $languageCode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SpeechRecognizer: ${e.message}", e)
            onError("Failed to initialize voice recognition: ${e.message}")
        }
    }

    private fun startListeningInternal() {
        if (!isActive.get() || isPaused.get()) return
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                val lang = languageCode.ifBlank { "bn-BD" }
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            recognizer?.startListening(intent)
            Log.d(TAG, "SpeechRecognizer startListening called ($languageCode)")
        } catch (e: Exception) {
            Log.e(TAG, "Error in startListeningInternal: ${e.message}", e)
            scheduleRestart(500L)
        }
    }

    fun pauseListening() {
        mainHandler.post {
            isPaused.set(true)
            mainHandler.removeCallbacks(restartRunnable)
            try {
                recognizer?.stopListening()
            } catch (_: Exception) {}
            onListeningChanged(false)
            onAmplitude(0f)
        }
    }

    fun resumeListening() {
        mainHandler.post {
            if (!isActive.get()) return@post
            isPaused.set(false)
            scheduleRestart(300L)
        }
    }

    fun stop() {
        mainHandler.post {
            isActive.set(false)
            isPaused.set(false)
            lastErrorCode = null
            consecutiveErrorCount = 0
            mainHandler.removeCallbacks(restartRunnable)
            destroyRecognizer()
            onListeningChanged(false)
            onAmplitude(0f)
            Log.i(TAG, "FallbackVoiceSession stopped and released")
        }
    }

    private fun destroyRecognizer() {
        try {
            recognizer?.stopListening()
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying recognizer: ${e.message}")
        } finally {
            recognizer = null
        }
    }

    private fun scheduleRestart(delayMs: Long = 300L) {
        mainHandler.removeCallbacks(restartRunnable)
        if (isActive.get() && !isPaused.get()) {
            mainHandler.postDelayed(restartRunnable, delayMs)
        }
    }

    private fun createListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "SpeechRecognizer onReadyForSpeech")
                recreatedAfterBusy = false
                lastErrorCode = null
                consecutiveErrorCount = 0
                onListeningChanged(true)
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "SpeechRecognizer onBeginningOfSpeech")
                recreatedAfterBusy = false
                onSpeechStart()
            }

            override fun onRmsChanged(rmsdB: Float) {
                // rmsdB range is roughly -2..10; clamp to 0..1
                val normalized = ((rmsdB - (-2f)) / (10f - (-2f))).coerceIn(0f, 1f)
                onAmplitude(normalized)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "SpeechRecognizer onEndOfSpeech")
                onListeningChanged(false)
                onAmplitude(0f)
                // If no results arrive within 300ms, auto re-listen
                if (isActive.get() && !isPaused.get()) {
                    scheduleRestart(300L)
                }
            }

            override fun onError(error: Int) {
                val errorName = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
                    SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
                    SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
                    SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
                    SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
                    else -> "UNKNOWN ($error)"
                }
                Log.w(TAG, "SpeechRecognizer onError: $errorName ($error)")

                onListeningChanged(false)
                onAmplitude(0f)

                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    isActive.set(false)
                    destroyRecognizer()
                    onError("Microphone permission required for voice recognition")
                    return
                }

                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && !recreatedAfterBusy) {
                    recreatedAfterBusy = true
                    Log.w(TAG, "Recognizer busy — recreating recognizer once...")
                    destroyRecognizer()
                    initRecognizer()
                    scheduleRestart(300L)
                    return
                }

                // Count consecutive SpeechRecognizer errors of the same kind
                if (lastErrorCode == error) {
                    consecutiveErrorCount++
                } else {
                    lastErrorCode = error
                    consecutiveErrorCount = 1
                }

                if (consecutiveErrorCount >= 3) {
                    Log.e(TAG, "SpeechRecognizer hit 3 consecutive errors of $errorName — stopping restart loop")
                    isActive.set(false)
                    destroyRecognizer()
                    onError("Speech recognition stopped: $errorName repeated 3 times")
                    return
                }

                // Auto re-listen loop after error NO_MATCH or speech timeout if under 3 consecutive errors
                if (isActive.get() && !isPaused.get()) {
                    scheduleRestart(300L)
                }
            }

            override fun onResults(results: Bundle?) {
                lastErrorCode = null
                consecutiveErrorCount = 0
                onAmplitude(0f)
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.trim()
                if (!text.isNullOrBlank()) {
                    Log.i(TAG, "SpeechRecognizer onResults: $text")
                    // Pause listening while turn is processed and audio is played
                    isPaused.set(true)
                    mainHandler.removeCallbacks(restartRunnable)
                    onFinalText(text)
                } else if (isActive.get() && !isPaused.get()) {
                    scheduleRestart(300L)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.trim()
                if (!text.isNullOrBlank()) {
                    onPartialText(text)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    companion object {
        private const val TAG = "MiraFallbackVoice"
    }
}
