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

/**
 * Service options for Mira's Gemini Live WebSocket transport (ROUND 19).
 *
 * Direct Google Gemini Live API transport (with x-goog-api-key).
 */
data class MiraGeminiServiceOptions(
    val apiKey: String,
    val endpointUrl: String = DEFAULT_ENDPOINT_URL,
    val authHeaderName: String = DEFAULT_AUTH_HEADER_NAME,
    val headers: Map<String, String> = emptyMap(),
    val modelConfig: Value,
    val initialUserMessage: String? = null,
    val echoGuardEnabled: Boolean = true
) {
    companion object {
        const val DEFAULT_ENDPOINT_URL =
            "https://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val DEFAULT_AUTH_HEADER_NAME = "x-goog-api-key"

        /**
         * Default phone tools declared in Gemini Live setup frame (Milestone 1).
         * Declares ONE function: open_app with args {app: string}.
         * Note: google_search grounding is NOT combined with function calling (prohibited by Live API).
         */
        fun defaultPhoneTools(): Value.Array {
            return Value.Array(
                Value.Object(
                    "functionDeclarations" to Value.Array(
                        Value.Object(
                            "name" to Value.Str("open_app"),
                            "description" to Value.Str("Opens an installed application on the user's phone by name or package (e.g. WhatsApp, YouTube, Settings, Chrome)."),
                            "parameters" to Value.Object(
                                "type" to Value.Str("OBJECT"),
                                "properties" to Value.Object(
                                    "app" to Value.Object(
                                        "type" to Value.Str("STRING"),
                                        "description" to Value.Str("The application name or package to open")
                                    )
                                ),
                                "required" to Value.Array(Value.Str("app"))
                            )
                        )
                    )
                )
            )
        }

        /**
         * Builds the modelConfig Value.Object for Gemini Live setup frame
         * EXACTLY per ROUND19_PLAN.md section 1 and voice-fix spec:
         *
         * - model: models/gemini-3.8-live
         * - generationConfig.responseModalities: ["AUDIO"]
         * - speechConfig: prebuiltVoiceConfig.voiceName + languageCode
         * - realtimeInputConfig: automaticActivityDetection (HIGH sensitivity, 800ms silence)
         * - inputAudioTranscription: {}
         * - outputAudioTranscription: {}
         * - sessionResumption: {} (fresh) or { "handle": storedHandle } (resumption)
         * - contextWindowCompression: { "slidingWindow": {} }
         * - systemInstruction: parts (if provided) or Value.Null
         * - tools: function declarations (open_app)
         * - NEVER top-level transparent
         */
        fun buildPipecatModelConfig(
            model: String = "models/gemini-3.8-live",
            voiceName: String = "Aoede",
            languageCode: String = "bn-BD",
            systemInstructionText: String = "",
            resumptionHandle: String? = null,
            tools: Value? = defaultPhoneTools()
        ): Value.Object {
            val speechConfig = Value.Object(
                "voiceConfig" to Value.Object(
                    "prebuiltVoiceConfig" to Value.Object(
                        "voiceName" to Value.Str(voiceName)
                    )
                ),
                "languageCode" to Value.Str(languageCode)
            )

            val generationConfig = Value.Object(
                "responseModalities" to Value.Array(Value.Str("AUDIO")),
                "speechConfig" to speechConfig
            )

            val realtimeInputConfig = Value.Object(
                "automaticActivityDetection" to Value.Object(
                    "endOfSpeechSensitivity" to Value.Str("END_SENSITIVITY_HIGH"),
                    "silenceDurationMs" to Value.Number(800.0)
                )
            )

            val sessionResumption = if (!resumptionHandle.isNullOrBlank()) {
                Value.Object("handle" to Value.Str(resumptionHandle))
            } else {
                Value.Object()
            }

            val contextCompression = Value.Object(
                "slidingWindow" to Value.Object()
            )

            val systemInstruction = if (systemInstructionText.isNotBlank()) {
                Value.Object(
                    "parts" to Value.Array(
                        Value.Object("text" to Value.Str(systemInstructionText))
                    )
                )
            } else {
                Value.Null
            }

            val configMap = mutableMapOf<String, Value>(
                "model" to Value.Str(model),
                "generationConfig" to generationConfig,
                "realtimeInputConfig" to realtimeInputConfig,
                "inputAudioTranscription" to Value.Object(),
                "outputAudioTranscription" to Value.Object(),
                "sessionResumption" to sessionResumption,
                "contextWindowCompression" to contextCompression,
                "systemInstruction" to systemInstruction
            )
            if (tools != null) {
                configMap["tools"] = tools
            }

            return Value.Object(configMap)
        }

        fun forDirect(
            apiKey: String,
            model: String = "models/gemini-3.8-live",
            voiceName: String = "Aoede",
            languageCode: String = "bn-BD",
            systemInstructionText: String = "",
            resumptionHandle: String? = null,
            tools: Value? = defaultPhoneTools(),
            initialUserMessage: String? = null,
            headers: Map<String, String> = emptyMap(),
            echoGuardEnabled: Boolean = true
        ) = MiraGeminiServiceOptions(
            apiKey = apiKey,
            endpointUrl = DEFAULT_ENDPOINT_URL,
            authHeaderName = DEFAULT_AUTH_HEADER_NAME,
            headers = headers,
            modelConfig = buildPipecatModelConfig(
                model = model,
                voiceName = voiceName,
                languageCode = languageCode,
                systemInstructionText = systemInstructionText,
                resumptionHandle = resumptionHandle,
                tools = tools
            ),
            initialUserMessage = initialUserMessage,
            echoGuardEnabled = echoGuardEnabled
        )
    }
}

/**
 * Top-level convenience function matching ROUND19_PLAN.md specification.
 */
fun buildPipecatModelConfig(
    model: String = "models/gemini-3.8-live",
    voiceName: String = "Aoede",
    languageCode: String = "bn-BD",
    systemInstructionText: String = "",
    resumptionHandle: String? = null,
    tools: Value? = MiraGeminiServiceOptions.defaultPhoneTools()
): Value.Object = MiraGeminiServiceOptions.buildPipecatModelConfig(
    model = model,
    voiceName = voiceName,
    languageCode = languageCode,
    systemInstructionText = systemInstructionText,
    resumptionHandle = resumptionHandle,
    tools = tools
)
