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

import ai.pipecat.client.result.Future
import ai.pipecat.client.result.RTVIError
import ai.pipecat.client.result.resolvedPromiseErr
import ai.pipecat.client.result.resolvedPromiseOk
import ai.pipecat.client.result.withPromise
import ai.pipecat.client.transport.MsgClientToServer
import ai.pipecat.client.transport.MsgServerToClient
import ai.pipecat.client.transport.Transport
import ai.pipecat.client.transport.TransportContext
import ai.pipecat.client.types.APIRequest
import ai.pipecat.client.types.BotReadyData
import ai.pipecat.client.types.LLMFunctionCallData
import ai.pipecat.client.types.LLMFunctionCallResult
import ai.pipecat.client.types.MediaDeviceId
import ai.pipecat.client.types.MediaDeviceInfo
import ai.pipecat.client.types.Participant
import ai.pipecat.client.types.ParticipantId
import ai.pipecat.client.types.ParticipantTracks
import ai.pipecat.client.types.Tracks
import ai.pipecat.client.types.Transcript
import ai.pipecat.client.types.TransportState
import ai.pipecat.client.types.Value
import ai.pipecat.client.utils.ThreadRef
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.ryoaonetsuki.mira.voice.LiveVoiceDiagnostics
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.json.JSONObject

private val JSON = Json { ignoreUnknownKeys = true }

private val BOT_PARTICIPANT = Participant(
    id = ParticipantId("bot"),
    name = null,
    local = false
)

private val LOCAL_PARTICIPANT = Participant(
    id = ParticipantId("local"),
    name = null,
    local = true
)

/**
 * Forked and enhanced Pipecat Transport implementation for Mira AI (ROUND 19).
 *
 * Implements Pipecat [Transport] parameterized by [MiraGeminiServiceOptions].
 * Wires [MiraGeminiClient] into Pipecat RTVI event callbacks, and exposes typed
 * session callbacks ([SessionListener]) for [GeminiSessionManager].
 */
class MiraGeminiLiveTransport(
    androidContext: Context,
    var sessionListener: SessionListener? = null,
    val echoGuardEnabled: Boolean = true
) : Transport<MiraGeminiServiceOptions>() {

    companion object {
        private const val TAG = "MiraGeminiLiveTransport"
    }

    interface SessionListener {
        fun onWebSocketOpen() {}
        fun onSessionResumptionUpdate(handle: String, resumable: Boolean) {}
        fun onGoAway(timeLeft: String) {}
        fun onUserTranscript(text: String) {}
        fun onModelTranscript(text: String) {}
        fun onSetupComplete() {}
        fun onSessionEnded(reason: String, t: Throwable?) {}
        fun onBotStartedSpeaking() {}
        fun onBotStoppedSpeaking() {}
        fun onUserStartedSpeaking() {}
        fun onUserStoppedSpeaking() {}
        fun onUserAudioLevel(level: Float) {}
        fun onRemoteAudioLevel(level: Float) {}
        fun onFunctionCall(id: String, name: String, args: Value.Array, rawArgs: Map<String, String> = emptyMap()) {}
    }

    object AudioDevices {
        val Speakerphone = MediaDeviceInfo(
            id = MediaDeviceId("speakerphone"),
            name = "Speakerphone"
        )

        val Earpiece = MediaDeviceInfo(
            id = MediaDeviceId("earpiece"),
            name = "Earpiece"
        )
    }

    private var state = TransportState.Disconnected
    private val appContext = androidContext.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private lateinit var transportContext: TransportContext
    private lateinit var thread: ThreadRef

    private var client: MiraGeminiClient? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private val isAudioModeInCommunication = AtomicBoolean(false)

    internal fun acquireCommunicationMode() {
        if (isAudioModeInCommunication.compareAndSet(false, true)) {
            try {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                Log.i(TAG, "Audio mode set to MODE_IN_COMMUNICATION")
                LiveVoiceDiagnostics.updateAudioMode("MODE_IN_COMMUNICATION")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting audio mode to MODE_IN_COMMUNICATION", e)
            }

            try {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener({ focusChange ->
                        Log.i(TAG, "Audio focus changed: $focusChange")
                    }, Handler(Looper.getMainLooper()))
                    .build()
                audioFocusRequest = focusRequest

                val focusResult = audioManager.requestAudioFocus(focusRequest)
                val focusStatus = when (focusResult) {
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> "granted"
                    AudioManager.AUDIOFOCUS_REQUEST_FAILED -> {
                        audioFocusRequest = null
                        Log.w(TAG, "Audio focus request failed (result: $focusResult)")
                        "failed"
                    }
                    else -> {
                        audioFocusRequest = null
                        Log.w(TAG, "Audio focus denied (result: $focusResult)")
                        "denied"
                    }
                }
                Log.i(TAG, "Audio focus requested/$focusStatus")
            } catch (e: Exception) {
                audioFocusRequest = null
                Log.e(TAG, "Error requesting audio focus", e)
            }
        }
    }

    internal fun restoreNormalAudioMode() {
        if (isAudioModeInCommunication.compareAndSet(true, false)) {
            try {
                audioFocusRequest?.let { req ->
                    audioManager.abandonAudioFocusRequest(req)
                    audioFocusRequest = null
                }
                audioManager.mode = AudioManager.MODE_NORMAL
                Log.i(TAG, "Audio mode restored to MODE_NORMAL, focus abandoned")
                LiveVoiceDiagnostics.updateAudioMode("MODE_NORMAL")
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring audio mode / abandoning audio focus", e)
            }
        }
    }

    override fun initialize(ctx: TransportContext) {
        transportContext = ctx
        thread = ctx.thread
    }

    override fun deserializeConnectParams(
        json: String,
        startBotRequest: APIRequest
    ): MiraGeminiServiceOptions {
        val obj = JSONObject(json)
        val apiKey = obj.optString("api_key", "")
        val endpointUrl = obj.optString("endpoint_url", MiraGeminiServiceOptions.DEFAULT_ENDPOINT_URL)
        val authHeaderName = obj.optString("auth_header_name", MiraGeminiServiceOptions.DEFAULT_AUTH_HEADER_NAME)
        val initialUserMessage = if (obj.has("initial_user_message")) obj.getString("initial_user_message") else null
        val voiceName = if (obj.has("voice")) obj.getString("voice") else obj.optString("voice_name", "Aoede")
        val languageCode = if (obj.has("language")) obj.getString("language") else obj.optString("language_code", "bn-BD")
        val model = obj.optString("model", "models/gemini-3.8-live")
        val systemInstruction = if (obj.has("system_instruction")) obj.getString("system_instruction") else obj.optString("system_instruction_text", "")
        val resumptionHandle = obj.optString("resumption_handle", null).takeIf { !it.isNullOrBlank() }
        val echoGuard = obj.optBoolean("echo_guard", echoGuardEnabled)

        return MiraGeminiServiceOptions(
            apiKey = apiKey,
            endpointUrl = endpointUrl,
            authHeaderName = authHeaderName,
            modelConfig = MiraGeminiServiceOptions.buildPipecatModelConfig(
                model = model,
                voiceName = voiceName,
                languageCode = languageCode,
                systemInstructionText = systemInstruction,
                resumptionHandle = resumptionHandle
            ),
            initialUserMessage = initialUserMessage,
            echoGuardEnabled = echoGuard
        )
    }

    override fun initDevices(): Future<Unit, RTVIError> = resolvedPromiseOk(thread, Unit)

    @SuppressLint("MissingPermission")
    override fun connect(transportParams: MiraGeminiServiceOptions): Future<Unit, RTVIError> =
        thread.runOnThreadReturningFuture {

            Log.i(TAG, "connect() to endpoint: ${transportParams.endpointUrl}")

            if (client != null) {
                return@runOnThreadReturningFuture resolvedPromiseErr(
                    thread,
                    RTVIError.OtherError("Connection already active")
                )
            }

            transportContext.callbacks.onInputsUpdated(
                camera = false,
                mic = transportContext.options.enableMic
            )

            setState(TransportState.Connecting)

            val modelName =
                ((transportParams.modelConfig as? Value.Object)
                    ?.value?.get("model") as? Value.Str)?.value ?: "models/gemini-3.8-live"

            acquireCommunicationMode()

            withPromise(thread) { promise ->
                try {
                    client = MiraGeminiClient.connect(
                        MiraGeminiClient.Config(
                            apiKey = transportParams.apiKey,
                            endpointUrl = transportParams.endpointUrl,
                            authHeaderName = transportParams.authHeaderName,
                            headers = transportParams.headers,
                            modelConfig = transportParams.modelConfig,
                            initialMessage = transportParams.initialUserMessage,
                            initialMicEnabled = transportContext.options.enableMic,
                            echoGuardEnabled = transportParams.echoGuardEnabled
                        ),
                        object : MiraGeminiClient.Listener {
                        override fun onConnected() {
                            thread.runOnThread {
                                val cb = transportContext.callbacks
                                setState(TransportState.Connected)
                                cb.onConnected()
                                cb.onParticipantJoined(LOCAL_PARTICIPANT)
                                cb.onParticipantJoined(BOT_PARTICIPANT)
                                setState(TransportState.Ready)
                                cb.onBotReady(BotReadyData(version = modelName))
                                promise.resolveOk(Unit)
                            }
                        }

                        override fun onWebSocketOpen() {
                            thread.runOnThread {
                                sessionListener?.onWebSocketOpen()
                            }
                        }

                        override fun onSetupComplete() {
                            thread.runOnThread {
                                sessionListener?.onSetupComplete()
                            }
                        }

                        override fun onSessionEnded(reason: String, t: Throwable?) {
                            thread.runOnThread {
                                Log.i(TAG, "onSessionEnded: $reason", t)
                                restoreNormalAudioMode()
                                setState(TransportState.Disconnected)
                                transportContext.onConnectionEnd()
                                transportContext.callbacks.onDisconnected()
                                promise.resolveErr(RTVIError.OtherError("Session disconnected: $reason"))
                                sessionListener?.onSessionEnded(reason, t)
                                t?.let {
                                    Log.e(TAG, "Session ended with exception", it)
                                }
                            }
                        }

                        override fun onBotTalking(isTalking: Boolean) {
                            thread.runOnThread {
                                transportContext.callbacks.apply {
                                    if (isTalking) {
                                        onBotStartedSpeaking()
                                    } else {
                                        onBotStoppedSpeaking()
                                    }
                                }
                                sessionListener?.apply {
                                    if (isTalking) {
                                        onBotStartedSpeaking()
                                    } else {
                                        onBotStoppedSpeaking()
                                    }
                                }
                            }
                        }

                        override fun onBotAudioLevel(level: Float) {
                            thread.runOnThread {
                                transportContext.callbacks.onRemoteAudioLevel(
                                    level,
                                    BOT_PARTICIPANT
                                )
                                sessionListener?.onRemoteAudioLevel(level)
                            }
                        }

                        override fun onUserTalking(isTalking: Boolean) {
                            thread.runOnThread {
                                transportContext.callbacks.apply {
                                    if (isTalking) {
                                        onUserStartedSpeaking()
                                    } else {
                                        onUserStoppedSpeaking()
                                    }
                                }
                                sessionListener?.apply {
                                    if (isTalking) {
                                        onUserStartedSpeaking()
                                    } else {
                                        onUserStoppedSpeaking()
                                    }
                                }
                            }
                        }

                        override fun onUserAudioLevel(level: Float) {
                            thread.runOnThread {
                                transportContext.callbacks.onUserAudioLevel(level)
                                sessionListener?.onUserAudioLevel(level)
                            }
                        }

                        override fun onSessionResumptionUpdate(handle: String, resumable: Boolean) {
                            thread.runOnThread {
                                sessionListener?.onSessionResumptionUpdate(handle, resumable)
                                transportContext.callbacks.onServerMessage(
                                    Value.Object(
                                        "sessionResumptionUpdate" to Value.Object(
                                            "handle" to Value.Str(handle),
                                            "resumable" to Value.Bool(resumable)
                                        )
                                    )
                                )
                            }
                        }

                        override fun onGoAway(timeLeft: String) {
                            thread.runOnThread {
                                sessionListener?.onGoAway(timeLeft)
                                transportContext.callbacks.onServerMessage(
                                    Value.Object(
                                        "goAway" to Value.Object(
                                            "timeLeft" to Value.Str(timeLeft)
                                        )
                                    )
                                )
                            }
                        }

                        override fun onUserTranscript(text: String) {
                            thread.runOnThread {
                                transportContext.callbacks.onUserTranscript(
                                    Transcript(
                                        text = text,
                                        `final` = true,
                                        timestamp = System.currentTimeMillis().toString(),
                                        userId = "local"
                                    )
                                )
                                sessionListener?.onUserTranscript(text)
                            }
                        }

                        override fun onModelTranscript(text: String) {
                            thread.runOnThread {
                                transportContext.callbacks.onBotTranscript(text)
                                sessionListener?.onModelTranscript(text)
                            }
                        }

                        override fun onFunctionCall(
                            id: String,
                            name: String,
                            args: Value.Array
                        ) {
                            onFunctionCall(id, name, args, emptyMap())
                        }

                        override fun onFunctionCall(
                            id: String,
                            name: String,
                            args: Value.Array,
                            rawArgs: Map<String, String>
                        ) {
                            val data = LLMFunctionCallData(
                                functionName = name,
                                toolCallID = id,
                                args = JSON.encodeToJsonElement(args)
                            )

                            transportContext.onMessage(
                                MsgServerToClient(
                                    id = null,
                                    label = "rtvi-ai",
                                    type = MsgServerToClient.Type.LlmFunctionCall,
                                    data = JSON.encodeToJsonElement(data)
                                )
                            )

                            thread.runOnThread {
                                sessionListener?.onFunctionCall(id, name, args, rawArgs)
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to connect client", t)
                restoreNormalAudioMode()
                promise.resolveErr(RTVIError.OtherError("Failed to connect client: ${t.message}"))
            }
        }
    }

    override fun disconnect(): Future<Unit, RTVIError> = thread.runOnThreadReturningFuture {
        restoreNormalAudioMode()
        client?.close()
        resolvedPromiseOk(thread, Unit)
    }

    override fun sendMessage(message: MsgClientToServer): Future<Unit, RTVIError> {
        when (message.type) {
            MsgClientToServer.Type.SendText -> {
                val data =
                    JSON.decodeFromJsonElement<MsgClientToServer.Data.SendText>(message.data!!)
                client?.sendUserMessage(role = "user", content = data.content)
                return resolvedPromiseOk(thread, Unit)
            }

            MsgClientToServer.Type.LlmFunctionCallResult -> {
                val messageData = message.data ?: return resolvedPromiseErr(
                    thread,
                    RTVIError.OtherError("Function call result must not be null")
                )

                val data: LLMFunctionCallResult = JSON.decodeFromJsonElement(messageData)
                val result = JSON.decodeFromJsonElement<Value>(data.result) as? Value.Object
                    ?: return resolvedPromiseErr(
                        thread,
                        RTVIError.OtherError("Function call result must be an object")
                    )

                client?.sendFunctionResponse(
                    id = data.toolCallID,
                    name = data.functionName,
                    response = result
                )

                return resolvedPromiseOk(thread, Unit)
            }

            else -> {
                return operationNotSupported()
            }
        }
    }

    fun stopAudioPlayback() {
        client?.stopAudioPlayback()
    }

    override fun state(): TransportState = state

    override fun setState(state: TransportState) {
        Log.i(TAG, "setState($state)")
        thread.assertCurrent()
        this.state = state
        transportContext.callbacks.onTransportStateChanged(state)
    }

    override fun getAllCams(): Future<List<MediaDeviceInfo>, RTVIError> =
        resolvedPromiseOk(thread, emptyList())

    override fun getAllMics(): Future<List<MediaDeviceInfo>, RTVIError> =
        resolvedPromiseOk(thread, listOf(AudioDevices.Earpiece, AudioDevices.Speakerphone))

    override fun updateMic(micId: MediaDeviceId): Future<Unit, RTVIError> {
        audioManager.isSpeakerphoneOn = (micId == AudioDevices.Speakerphone.id)
        return resolvedPromiseOk(thread, Unit)
    }

    override fun updateCam(camId: MediaDeviceId) = operationNotSupported<Unit>()

    override fun selectedMic(): MediaDeviceInfo {
        return when (audioManager.isSpeakerphoneOn) {
            true -> AudioDevices.Speakerphone
            false -> AudioDevices.Earpiece
        }
    }

    override fun selectedCam() = null

    override fun isCamEnabled() = false

    override fun isMicEnabled() = client?.micMuted?.not() ?: false

    override fun enableMic(enable: Boolean): Future<Unit, RTVIError> {
        client?.micMuted = !enable
        thread.runOnThread {
            transportContext.callbacks.onInputsUpdated(camera = false, mic = enable)
        }
        return resolvedPromiseOk(thread, Unit)
    }

    override fun enableCam(enable: Boolean) = operationNotSupported<Unit>()

    override fun tracks() = Tracks(
        local = ParticipantTracks(null, null),
        bot = null,
    )

    override fun release() {
        try {
            thread.assertCurrent()
        } catch (_: Throwable) {}
        try {
            restoreNormalAudioMode()
        } catch (_: Throwable) {}
        try {
            client?.close()
        } catch (_: Throwable) {}
        client = null
    }

    private fun <E> operationNotSupported(): Future<E, RTVIError> =
        resolvedPromiseErr(thread, RTVIError.OtherError("Operation not supported"))
}
