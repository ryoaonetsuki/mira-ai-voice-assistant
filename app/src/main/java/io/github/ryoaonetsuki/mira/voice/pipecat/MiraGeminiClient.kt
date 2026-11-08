/*
 * Copyright (c) 2024, Daily
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.github.ryoaonetsuki.mira.voice.pipecat

import ai.pipecat.client.types.Value
import android.annotation.SuppressLint
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

private const val TAG = "MiraGeminiClient"

private val JSON = Json { ignoreUnknownKeys = true }

/**
 * Recursively converts a Pipecat [Value] to a kotlinx.serialization [JsonElement].
 *
 * Replacement for internal [ai.pipecat.client.types.ValueSerializer] in pipecat-client 1.2.0.
 */
fun Value.toJsonElement(): JsonElement = when (this) {
    is Value.Object -> JsonObject(value.mapValues { it.value.toJsonElement() })
    is Value.Array -> JsonArray(value.map { it.toJsonElement() })
    is Value.Str -> JsonPrimitive(value)
    is Value.Number -> {
        val d = value.toDouble()
        if (!d.isNaN() && !d.isInfinite() && d % 1.0 == 0.0 && d >= Long.MIN_VALUE.toDouble() && d <= Long.MAX_VALUE.toDouble()) {
            JsonPrimitive(value.toLong())
        } else {
            JsonPrimitive(value)
        }
    }
    is Value.Bool -> JsonPrimitive(value)
    is Value.Null -> JsonNull
    else -> JsonNull
}

/**
 * Serializes a Pipecat [Value] to an identical JSON string representation.
 */
fun Value.toJsonString(): String =
    JSON.encodeToString(JsonElement.serializer(), this.toJsonElement())

/**
 * Serializes a Pipecat [Value] using the provided [Json] instance.
 */
fun Value.toJsonString(json: Json): String =
    json.encodeToString(JsonElement.serializer(), this.toJsonElement())

private fun <E> AtomicReference<E?>.take(): E? = getAndSet(null)

private data class AppendedMessage(
    val role: String,
    val content: String
)

private data class ParsedTurnPart(
    val text: String? = null,
    val pcmBytes: ByteArray? = null
)

private data class ParsedFunctionCall(
    val id: String,
    val name: String,
    val args: Map<String, String>
)

/**
 * Forked and enhanced OkHttp WebSocket client for Gemini Live (ROUND 19).
 *
 * Features:
 * - Surfaces sessionResumptionUpdate (handle, resumable) rather than discarding.
 * - Surfaces goAway (timeLeft) rather than only logging.
 * - Surfaces setupComplete.
 * - Surfaces inputAudioTranscription and outputAudioTranscription.
 * - Configurable endpointUrl and headers map (Direct Google API vs Cloudflare Relay).
 * - BYOK security: NEVER logs API keys or raw session resumption handles to logcat.
 */
class MiraGeminiClient private constructor(
    private val onSendUserMessage: (AppendedMessage) -> Unit,
    private val onSendFunctionResponse: (String, String, Value.Object) -> Unit,
    private val onClose: () -> Unit,
    private val setMicMuted: (Boolean) -> Unit,
    private val isMicMuted: () -> Boolean,
) {
    data class Config(
        val apiKey: String,
        val endpointUrl: String = MiraGeminiServiceOptions.DEFAULT_ENDPOINT_URL,
        val authHeaderName: String = MiraGeminiServiceOptions.DEFAULT_AUTH_HEADER_NAME,
        val headers: Map<String, String> = emptyMap(),
        val modelConfig: Value,
        val initialMessage: String? = null,
        val initialMicEnabled: Boolean = true,
    )

    interface Listener {
        fun onConnected()
        fun onWebSocketOpen() {}
        fun onSetupComplete() {}
        fun onSessionEnded(reason: String, t: Throwable?)
        fun onBotTalking(isTalking: Boolean)
        fun onUserTalking(isTalking: Boolean)
        fun onUserAudioLevel(level: Float)
        fun onBotAudioLevel(level: Float)
        fun onFunctionCall(id: String, name: String, args: Value.Array) {}
        fun onFunctionCall(id: String, name: String, args: Value.Array, rawArgs: Map<String, String>) {
            onFunctionCall(id, name, args)
        }
        fun onSessionResumptionUpdate(handle: String, resumable: Boolean)
        fun onGoAway(timeLeft: String)
        fun onUserTranscript(text: String)
        fun onModelTranscript(text: String)
    }

    private sealed interface ClientThreadEvent {
        class SendAudioData(val buf: ByteArray) : ClientThreadEvent
        class SendUserMessage(val msg: AppendedMessage) : ClientThreadEvent
        class SendFunctionResponse(val id: String, val name: String, val response: Value.Object) : ClientThreadEvent
        data object Stop : ClientThreadEvent
        data object WebsocketClosed : ClientThreadEvent
        class WebsocketFailed(val t: Throwable) : ClientThreadEvent
        data object SetupCompleteReceived : ClientThreadEvent
        class SessionResumptionUpdateReceived(val handle: String, val resumable: Boolean) : ClientThreadEvent
        class GoAwayReceived(val timeLeft: String) : ClientThreadEvent
        class UserTranscriptReceived(val text: String) : ClientThreadEvent
        class ModelTranscriptReceived(val text: String) : ClientThreadEvent
        class ToolCallReceived(val functionCalls: List<ParsedFunctionCall>) : ClientThreadEvent
        class ServerContentReceived(
            val modelParts: List<ParsedTurnPart>,
            val interrupted: Boolean,
            val turnComplete: Boolean,
            val userInputTranscript: String?,
            val modelTranscript: String?
        ) : ClientThreadEvent
        class SetMicMute(val muted: Boolean) : ClientThreadEvent
    }

    companion object {

        @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
        fun connect(config: Config, listener: Listener): MiraGeminiClient {

            val events = LinkedBlockingQueue<ClientThreadEvent>()
            val isMicMutedRef = AtomicReference<(() -> Boolean)?>(null)

            fun postEvent(event: ClientThreadEvent) = events.put(event)

            thread(name = "MiraGeminiClient") {
                val okHttpClient: OkHttpClient = OkHttpClient.Builder().build()

                val inputSampleRate = 16000
                val audioInCallback = AtomicReference<((ByteArray) -> Unit)?>(null)

                val audioIn = AudioIn(
                    sampleRateHz = inputSampleRate,
                    onAudioCaptured = { audioInCallback.get()?.invoke(it) },
                    onAudioLevelUpdate = listener::onUserAudioLevel,
                    onError = { Log.e(TAG, "AudioIn error: ${it.message}") },
                    onStopped = {},
                    initialMicEnabled = config.initialMicEnabled
                )
                val audioOut = AudioOut(
                    sampleRateHz = 24000,
                    onAudioLevelUpdate = listener::onBotAudioLevel
                )

                var isBotTalking = false
                var isUserTalking = false

                isMicMutedRef.set { audioIn.muted }
                val listenerRef = AtomicReference(listener)

                val requestBuilder = Request.Builder()
                    .url(config.endpointUrl)

                if (config.apiKey.isNotBlank() && config.authHeaderName.isNotBlank()) {
                    requestBuilder.header(config.authHeaderName, config.apiKey)
                }

                for ((headerKey, headerVal) in config.headers) {
                    requestBuilder.header(headerKey, headerVal)
                }

                val request: Request = requestBuilder.build()

                val ws = okHttpClient.newWebSocket(request, object : WebSocketListener() {

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        Log.i(TAG, "Websocket onClosed($code, $reason)")
                        postEvent(ClientThreadEvent.WebsocketClosed)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        Log.i(TAG, "Websocket onClosing($code, $reason)")
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.e(TAG, "Websocket onFailure: ${t.message}")
                        postEvent(ClientThreadEvent.WebsocketFailed(t))
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        parseAndPostMessage(text)
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        parseAndPostMessage(bytes.utf8())
                    }

                    @SuppressLint("MissingPermission")
                    fun parseAndPostMessage(msg: String) {
                        try {
                            val root = JSONObject(msg)

                            if (root.has("setupComplete")) {
                                postEvent(ClientThreadEvent.SetupCompleteReceived)
                            }

                            if (root.has("sessionResumptionUpdate")) {
                                val sru = root.getJSONObject("sessionResumptionUpdate")
                                val newHandle = sru.optString("newHandle", "")
                                val resumable = sru.optBoolean("resumable", false)
                                postEvent(ClientThreadEvent.SessionResumptionUpdateReceived(newHandle, resumable))
                            }

                            if (root.has("goAway")) {
                                val ga = root.getJSONObject("goAway")
                                val timeLeft = ga.optString("timeLeft", "")
                                postEvent(ClientThreadEvent.GoAwayReceived(timeLeft))
                            }

                            if (root.has("inputAudioTranscription")) {
                                val text = root.getJSONObject("inputAudioTranscription").optString("text")
                                if (text.isNotBlank()) postEvent(ClientThreadEvent.UserTranscriptReceived(text))
                            }

                            if (root.has("outputAudioTranscription")) {
                                val text = root.getJSONObject("outputAudioTranscription").optString("text")
                                if (text.isNotBlank()) postEvent(ClientThreadEvent.ModelTranscriptReceived(text))
                            }

                            if (root.has("serverContent")) {
                                val sc = root.getJSONObject("serverContent")

                                val inputTrans = sc.optJSONObject("inputAudioTranscription")
                                    ?: sc.optJSONObject("inputTranscription")
                                val inputTransText = inputTrans?.optString("text")?.takeIf { it.isNotBlank() }

                                val outputTrans = sc.optJSONObject("outputAudioTranscription")
                                    ?: sc.optJSONObject("outputTranscription")
                                val outputTransText = outputTrans?.optString("text")?.takeIf { it.isNotBlank() }

                                val interrupted = sc.optBoolean("interrupted", false)
                                val turnComplete = sc.optBoolean("turnComplete", false)

                                val modelTurnParts = mutableListOf<ParsedTurnPart>()
                                val mt = sc.optJSONObject("modelTurn")
                                if (mt != null) {
                                    val parts = mt.optJSONArray("parts")
                                    if (parts != null) {
                                        for (i in 0 until parts.length()) {
                                            val part = parts.getJSONObject(i)
                                            val text = part.optString("text", "").takeIf { it.isNotEmpty() }
                                            val inlineData = part.optJSONObject("inlineData")
                                            val pcmBytes = if (inlineData != null) {
                                                val dataStr = inlineData.optString("data", "")
                                                if (dataStr.isNotEmpty()) Base64.decode(dataStr, Base64.DEFAULT) else null
                                            } else null
                                            modelTurnParts.add(ParsedTurnPart(text = text, pcmBytes = pcmBytes))
                                        }
                                    }
                                }

                                postEvent(
                                    ClientThreadEvent.ServerContentReceived(
                                        modelParts = modelTurnParts,
                                        interrupted = interrupted,
                                        turnComplete = turnComplete,
                                        userInputTranscript = inputTransText,
                                        modelTranscript = outputTransText
                                    )
                                )
                            }

                            if (root.has("toolCall")) {
                                val tc = root.getJSONObject("toolCall")
                                val functionCalls = tc.optJSONArray("functionCalls")
                                if (functionCalls != null) {
                                    val parsedCalls = mutableListOf<ParsedFunctionCall>()
                                    for (i in 0 until functionCalls.length()) {
                                        val fc = functionCalls.getJSONObject(i)
                                        val id = fc.optString("id", "")
                                        val name = fc.optString("name", "")
                                        val argsObj = fc.optJSONObject("args")
                                        val argsMap = mutableMapOf<String, String>()
                                        if (argsObj != null) {
                                            val keys = argsObj.keys()
                                            while (keys.hasNext()) {
                                                val k = keys.next()
                                                argsMap[k] = argsObj.optString(k, "")
                                            }
                                        }
                                        parsedCalls.add(ParsedFunctionCall(id = id, name = name, args = argsMap))
                                    }
                                    postEvent(ClientThreadEvent.ToolCallReceived(parsedCalls))
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse incoming WebSocket message: ${e.message}")
                        }
                    }

                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        Log.i(TAG, "Websocket onOpen, sending setup frame")
                        val modelConfigJson = config.modelConfig.toJsonString()
                        val setupPayload = JSONObject().apply {
                            put("setup", JSONObject(modelConfigJson))
                        }.toString()
                        webSocket.send(setupPayload)
                        listenerRef.get()?.onWebSocketOpen()
                    }
                })

                fun doSendUserMessage(msg: AppendedMessage) {
                    val clientContentJson = JSONObject().apply {
                        put("clientContent", JSONObject().apply {
                            put("turns", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("role", msg.role)
                                    put("parts", JSONArray().apply {
                                        put(JSONObject().apply {
                                            put("text", msg.content)
                                        })
                                    })
                                })
                            })
                            put("turnComplete", true)
                        })
                    }.toString()
                    ws.send(clientContentJson)
                }

                fun doSendFunctionResponse(id: String, name: String, response: Value.Object) {
                    val respJsonStr = response.toJsonString()
                    val toolResponseJson = JSONObject().apply {
                        put("toolResponse", JSONObject().apply {
                            put("functionResponses", JSONArray().apply {
                                put(JSONObject().apply {
                                    if (id.isNotBlank()) put("id", id)
                                    put("name", name)
                                    put("response", JSONObject(respJsonStr))
                                })
                            })
                        })
                    }.toString()
                    ws.send(toolResponseJson)
                }

                fun stopAudio() {
                    audioIn.stop()
                    audioOut.interrupt()
                    audioOut.stop()
                }

                while (true) {
                    when (val event = events.take()) {
                        is ClientThreadEvent.SendAudioData -> {
                            val audioInputJson = JSONObject().apply {
                                put("realtimeInput", JSONObject().apply {
                                    put("audio", JSONObject().apply {
                                        put("mimeType", "audio/pcm;rate=$inputSampleRate")
                                        put("data", Base64.encodeToString(event.buf, Base64.NO_WRAP))
                                    })
                                })
                            }.toString()
                            ws.send(audioInputJson)
                        }

                        is ClientThreadEvent.SendUserMessage -> {
                            doSendUserMessage(event.msg)
                        }

                        is ClientThreadEvent.SendFunctionResponse -> {
                            doSendFunctionResponse(event.id, event.name, event.response)
                        }

                        ClientThreadEvent.Stop -> {
                            stopAudio()
                            ws.close(1000, "user requested close")
                        }

                        ClientThreadEvent.WebsocketClosed -> {
                            stopAudio()
                            listenerRef.take()?.onSessionEnded("websocket closed", null)
                            return@thread
                        }

                        is ClientThreadEvent.WebsocketFailed -> {
                            stopAudio()
                            listenerRef.take()?.onSessionEnded("websocket failed", event.t)
                            return@thread
                        }

                        ClientThreadEvent.SetupCompleteReceived -> {
                            Log.i(TAG, "Setup complete received")
                            if (config.initialMessage != null) {
                                doSendUserMessage(
                                    AppendedMessage(
                                        content = config.initialMessage,
                                        role = "user"
                                    )
                                )
                            }
                            listenerRef.get()?.onSetupComplete()
                            listenerRef.get()?.onConnected()

                            audioInCallback.set { buf ->
                                postEvent(ClientThreadEvent.SendAudioData(buf))
                            }
                        }

                        is ClientThreadEvent.SessionResumptionUpdateReceived -> {
                            // Security: Log only handle length, never the raw handle
                            Log.i(
                                TAG,
                                "Session resumption update received (resumable: ${event.resumable}, handleLength: ${event.handle.length})"
                            )
                            listenerRef.get()?.onSessionResumptionUpdate(
                                handle = event.handle,
                                resumable = event.resumable
                            )
                        }

                        is ClientThreadEvent.GoAwayReceived -> {
                            Log.w(TAG, "Server GoAway received (timeLeft: ${event.timeLeft})")
                            listenerRef.get()?.onGoAway(event.timeLeft)
                        }

                        is ClientThreadEvent.UserTranscriptReceived -> {
                            Log.i(TAG, "User transcript: ${event.text}")
                            listenerRef.get()?.onUserTranscript(event.text)
                        }

                        is ClientThreadEvent.ModelTranscriptReceived -> {
                            Log.i(TAG, "Model transcript: ${event.text}")
                            listenerRef.get()?.onModelTranscript(event.text)
                        }

                        is ClientThreadEvent.ServerContentReceived -> {
                            if (event.userInputTranscript != null) {
                                listenerRef.get()?.onUserTranscript(event.userInputTranscript)
                            }
                            if (event.modelTranscript != null) {
                                listenerRef.get()?.onModelTranscript(event.modelTranscript)
                            }

                            if (event.modelParts.isNotEmpty()) {
                                if (!isBotTalking) {
                                    isBotTalking = true
                                    listenerRef.get()?.onBotTalking(true)
                                }
                                if (isUserTalking) {
                                    isUserTalking = false
                                    listenerRef.get()?.onUserTalking(false)
                                }

                                for (part in event.modelParts) {
                                    part.text?.let { text ->
                                        Log.d(TAG, "Model turn text part: $text")
                                        listenerRef.get()?.onModelTranscript(text)
                                    }
                                    part.pcmBytes?.let { pcm ->
                                        audioOut.write(pcm)
                                    }
                                }
                            }

                            if (event.interrupted) {
                                Log.i(TAG, "User interrupted model output")
                                isBotTalking = false
                                isUserTalking = true
                                listenerRef.get()?.onUserTalking(true)
                                listenerRef.get()?.onBotTalking(false)
                                audioOut.interrupt()
                            }

                            if (event.turnComplete) {
                                Log.d(TAG, "Model turn complete")
                                if (isBotTalking) {
                                    isBotTalking = false
                                    listenerRef.get()?.onBotTalking(false)
                                }
                                if (!isUserTalking) {
                                    isUserTalking = true
                                    listenerRef.get()?.onUserTalking(true)
                                }
                            }
                        }

                        is ClientThreadEvent.ToolCallReceived -> {
                            for (call in event.functionCalls) {
                                Log.i(TAG, "Function call received: ${call.name} (id: ${call.id})")
                                listenerRef.get()?.onFunctionCall(
                                    id = call.id,
                                    name = call.name,
                                    args = Value.Array(call.args.values.map { Value.Str(it) }),
                                    rawArgs = call.args
                                )
                            }
                        }

                        is ClientThreadEvent.SetMicMute -> {
                            audioIn.muted = event.muted
                        }
                    }
                }
            }

            return MiraGeminiClient(
                onSendUserMessage = { text ->
                    postEvent(ClientThreadEvent.SendUserMessage(text))
                },
                onSendFunctionResponse = { id, name, response ->
                    postEvent(ClientThreadEvent.SendFunctionResponse(id, name, response))
                },
                onClose = {
                    postEvent(ClientThreadEvent.Stop)
                },
                isMicMuted = {
                    isMicMutedRef.get()?.invoke() ?: false
                },
                setMicMuted = {
                    postEvent(ClientThreadEvent.SetMicMute(it))
                }
            )
        }
    }

    var micMuted: Boolean
        get() = isMicMuted()
        set(muted) {
            setMicMuted(muted)
        }

    fun sendUserMessage(role: String, content: String) =
        onSendUserMessage(AppendedMessage(role = role, content = content))

    fun sendFunctionResponse(id: String, name: String, response: Value.Object) =
        onSendFunctionResponse(id, name, response)

    fun close() = onClose()
}
