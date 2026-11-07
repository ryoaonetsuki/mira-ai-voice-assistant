package io.github.ryoaonetsuki.mira.network

import android.util.Base64
import android.util.Log
import io.github.ryoaonetsuki.mira.BuildConfig
import io.github.ryoaonetsuki.mira.data.preferences.SecurePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Client for the mira-server-voice Cloudflare Worker.
 * Handles WebSocket streaming for real-time audio/voice turns,
 * and Groq API key validation via OpenAI-compatible models endpoint.
 *
 * NEVER logs keys, transcripts, or audio payload content.
 */
class ServerVoiceClient(
    private val customBaseUrl: String? = null
) {
    interface ServerVoiceListener {
        fun onConnected() {}
        fun onPartial(text: String)
        fun onTranscript(text: String)
        fun onToken(text: String)
        fun onAudio(pcm24kBytes: ByteArray, sentence: Int)
        fun onReplyDone()
        fun onError(message: String)
        fun onClosed(code: Int, reason: String)
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Indefinite read for persistent WebSocket
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val validationHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    val serverVoiceUrl: String
        get() {
            if (!customBaseUrl.isNullOrBlank()) return customBaseUrl
            return try {
                BuildConfig.SERVER_VOICE_URL
            } catch (_: Throwable) {
                DEFAULT_SERVER_VOICE_URL
            }
        }

    /**
     * Connects to the streaming server voice WebSocket.
     */
    fun connect(
        groqKey: String,
        geminiKey: String,
        listener: ServerVoiceListener
    ): WebSocket {
        val base = serverVoiceUrl.trimEnd('/')
        val wsUrl = if (base.startsWith("https://")) {
            base.replaceFirst("https://", "wss://") + "/voice/stream"
        } else if (base.startsWith("http://")) {
            base.replaceFirst("http://", "ws://") + "/voice/stream"
        } else {
            "wss://$base/voice/stream"
        }

        Log.i(TAG, "Connecting to streaming voice server (groqLen=${groqKey.length}, geminiLen=${geminiKey.length})")

        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("x-mira-groq-key", groqKey.trim())
            .addHeader("x-mira-gemini-key", geminiKey.trim())
            .build()

        return httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Server voice WebSocket opened (code=${response.code})")
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "partial" -> {
                            val part = json.optString("text", "")
                            listener.onPartial(part)
                        }
                        "transcript" -> {
                            val finalTxt = json.optString("text", "")
                            listener.onTranscript(finalTxt)
                        }
                        "token" -> {
                            val tok = json.optString("text", "")
                            listener.onToken(tok)
                        }
                        "audio" -> {
                            val base64Data = json.optString("data", "")
                            val sentence = json.optInt("sentence", 0)
                            if (base64Data.isNotBlank()) {
                                val pcm = Base64.decode(base64Data, Base64.DEFAULT)
                                listener.onAudio(pcm, sentence)
                            }
                        }
                        "reply-done" -> {
                            listener.onReplyDone()
                        }
                        "error" -> {
                            val msg = json.optString("message", "Server voice error")
                            val detail = json.optString("detail", "")
                            val finalMsg = if (detail.isNotBlank() && !msg.contains(detail)) {
                                "$msg [$detail]"
                            } else {
                                msg
                            }
                            Log.w(TAG, "Server voice error frame: $finalMsg")
                            listener.onError(finalMsg)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse server frame: ${e.message}")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Server currently sends JSON text frames and base64 audio; handle raw binary if sent
                listener.onAudio(bytes.toByteArray(), 0)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Server voice WebSocket closing: $code / $reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Server voice WebSocket closed: $code / $reason")
                listener.onClosed(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: -1
                val msg = t.message ?: "WebSocket failure"
                Log.w(TAG, "Server voice WebSocket failure (code=$code): $msg")
                if (code == 401 || code == 4401) {
                    listener.onError("API key problem — check your keys in Settings")
                } else {
                    listener.onError("Connection failure: $msg")
                }
                listener.onClosed(code, msg)
            }
        })
    }

    /**
     * Validates a Groq API key via GET https://api.groq.com/openai/v1/models.
     * Enforces an 8s call timeout and 12s overall timeout cap.
     * Fail-open: plausible key + transient/network error = saved with TransientServerBusy.
     */
    suspend fun validateGroqKey(rawKey: String): KeyValidationOutcome = withContext(Dispatchers.IO) {
        val trimmed = rawKey.trim()
        if (trimmed.isBlank()) {
            return@withContext KeyValidationOutcome.InvalidKey("API key cannot be blank")
        }

        if (!SecurePreferences.isPlausibleGroqKey(trimmed)) {
            return@withContext KeyValidationOutcome.InvalidKey("Groq keys start with 'gsk_' and must be at least 20 characters")
        }

        val outcome = withTimeoutOrNull(12_000L) {
            try {
                val req = Request.Builder()
                    .url("https://api.groq.com/openai/v1/models")
                    .addHeader("Authorization", "Bearer $trimmed")
                    .get()
                    .build()

                validationHttpClient.newCall(req).execute().use { resp ->
                    val code = resp.code
                    when {
                        resp.isSuccessful -> KeyValidationOutcome.Valid
                        code == 401 || code == 403 -> {
                            KeyValidationOutcome.InvalidKey("Groq rejected this key (HTTP $code)", code)
                        }
                        code == 429 || code >= 500 -> {
                            KeyValidationOutcome.TransientServerBusy(
                                "Groq servers are busy right now (HTTP $code) — key saved, try again in a bit",
                                code
                            )
                        }
                        else -> {
                            KeyValidationOutcome.InvalidKey("Groq returned HTTP $code", code)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Groq key validation network exception: ${e.message}")
                if (SecurePreferences.isPlausibleGroqKey(trimmed)) {
                    KeyValidationOutcome.TransientServerBusy(
                        "Groq servers are temporarily unreachable — key saved, will try during voice",
                        null
                    )
                } else {
                    KeyValidationOutcome.InvalidKey("Network error validating Groq key: ${e.message}")
                }
            }
        }

        outcome ?: KeyValidationOutcome.TransientServerBusy(
            "Groq key validation timed out — key saved, will try during voice",
            null
        )
    }

    companion object {
        private const val TAG = "MiraServerVoice"
        const val DEFAULT_SERVER_VOICE_URL = "https://mira-server-voice.salman-dev.workers.dev"
    }
}
