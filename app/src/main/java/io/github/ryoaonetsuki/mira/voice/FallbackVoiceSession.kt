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
    private val onError: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private val isActive = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private var recreatedAfterBusy = false

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
            mainHandler.removeCallbacks(restartRunnable)
            destroyRecognizer()
            onListeningChanged(false)
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
                onListeningChanged(true)
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "SpeechRecognizer onBeginningOfSpeech")
                recreatedAfterBusy = false
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "SpeechRecognizer onEndOfSpeech")
                onListeningChanged(false)
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

                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && !recreatedAfterBusy) {
                    recreatedAfterBusy = true
                    Log.w(TAG, "Recognizer busy — recreating recognizer once...")
                    destroyRecognizer()
                    initRecognizer()
                    scheduleRestart(300L)
                    return
                }

                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    onError("Microphone permission required for voice recognition")
                    return
                }

                // Auto re-listen loop after error NO_MATCH or speech timeout
                if (isActive.get() && !isPaused.get()) {
                    scheduleRestart(300L)
                }
            }

            override fun onResults(results: Bundle?) {
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
