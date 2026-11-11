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
         * Default phone tools declared in Gemini Live setup frame (Milestones 1, 2, 3, 4).
         * Declares phone action tools: open_app, click_text, tap_number, show_numbers, hide_numbers, type_text, scroll, press_back, press_home, wait, get_screen,
         * web_search, run_connector_command, and run_skill.
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
                        ),
                        Value.Object(
                            "name" to Value.Str("click_text"),
                            "description" to Value.Str("Clicks or taps an on-screen element matching the given text label, with optional fallback screen coordinates (x, y)."),
                            "parameters" to Value.Object(
                                "type" to Value.Str("OBJECT"),
                                "properties" to Value.Object(
                                    "text" to Value.Object(
                                        "type" to Value.Str("STRING"),
                                        "description" to Value.Str("Visible text or label of the UI element to click")
                                    ),
                                    "x" to Value.Object(
                                        "type" to Value.Str("NUMBER"),
                                        "description" to Value.Str("Optional fallback X coordinate on screen if label is not found")
                                    ),
                                    "y" to Value.Object(
                                        "type" to Value.Str("NUMBER"),
                                        "description" to Value.Str("Optional fallback Y coordinate on screen if label is not found")
                                    )
                                ),
                                "required" to Value.Array(Value.Str("text"))
                            )
                        ),
                        Value.Object(
                            "name" to Value.Str("click_at"),
                            "description" to Value.Str("Taps the screen at the given (x, y) coordinates. Use when an element has no text label or when coordinates are known."),
                            "parameters" to Value.Object(
                                "type" to Value.Str("OBJECT"),
                                "properties" to Value.Object(
                                    "x" to Value.Object(
                                        "type" to Value.Str("NUMBER"),
                                        "description" to Value.Str("X coordinate in screen pixels or 0-1000 scale")
                                    ),
                                    "y" to Value.Object(
                                        "type" to Value.Str("NUMBER"),
                                        "description" to Value.Str("Y coordinate in screen pixels or 0-1000 scale")
                                    )
                                ),
                                "required" to Value.Array(Value.Str("x"), Value.Str("y"))
                            )
                        ),
                        Value.Object(
                            "name" to Value.Str("tap_number"),
                            "description" to Value.Str("Taps the on-screen element with the given number from the tap-number overlay (see show_numbers). Numbers are 1-based and refer only to tappable elements."),
                            "parameters" to Value.Object(
                                "type" to Value.Str("OBJECT"),
                                "properties" to Value.Object(
                                    "number" to Value.Object(
                                        "type" to Value.Str("NUMBER"),
                                        "description" to Value.Str("The 1-based number shown on the tappable element")
                                    )
                                ),
                                "required" to Value.Array(Value.Str("number"))
                            )
                        ),
                        Value.Object(
                            "name" to Value.Str("show_numbers"),
                            "description" to Value.Str("Shows numbered badges on all tappable elements on the current screen and returns the numbered list (number, label, center coordinates) as text. Call this first when the user asks to tap a number and numbers are not already shown."),
                            "parameters" to Value.Object(
                                "type" to Value.Str("OBJECT"),
                                "properties" to Value.Object()
                            )
                        ),
                        Value.Object(
                            "name" to Value.Str("hide_numbers"),
                            "description" to Value.Str("Hides the tap-number badges from the screen."),
                            "parameters" to Value.Object(
                                "type" to Value.Str("OBJECT"),
                                "properties" to Value.Object()
                            )
                        ),
                        Value.Object(
                            "name" to Value.Str("type_text"),
                            "description" to Value.Str("Types text into the currently focused input field on screen."),
                            "parameters" to Value.Object(
                                "type" to Value.Str("OBJECT"),
                                "properties" to Value.Object(
                                    "text" to Value.Object(
                                        "type" to Value.Str("STRING"),
                                        "description" to Value.Str("Text to type into the focused field")
                                    )
                                ),
                                "required" to Value.Array(Value.Str("text"))
                            )
                        ),
                        Value.Object(
                            "name" to Value.Str("scroll"),
                            "description" to Value.Str("Scrolls the current screen in a specified direction (up, down, left, right)."),
                            "parameters" to Value.Object(
                                "type" to Value.Str("OBJECT"),
                                "properties" to Value.Object(
                                    "direction" to Value.Object(
                                        "type" to Value.Str("STRING"),
                                        "description" to Value.Str("Direction to scroll: 'up', 'down', 'left', or 'right' (default 'down')")
                                    )
                                )
                            )
                        ),
                        Value.Object(
                            "name" to Value.Str("press_back"),
                            "description" to Value.Str("Presses the system Back button."),
                            "parameters" to Value.Object(
                                "type" to Value.Str("OBJECT"),
                                "properties" to Value.Object()
                            )
                        ),
                        Value.Object(
                            "name" to Value.Str("press_home"),
                            "description" to Value.Str("Presses the system Home button to return to the home screen launcher."),
                            "parameters" to Value.Object(
                                "type" to Value.Str("OBJECT"),
                                "properties" to Value.Object()
                            )
                        ),
                        Value.Object(
                            "name" to Value.Str("wait"),
                            "description" to Value.Str("Waits for a specified duration in milliseconds (default 1000, 100..10000) for UI elements or apps to load."),
                            "parameters" to Value.Object(
                                "type" to Value.Str("OBJECT"),
                                "properties" to Value.Object(
                                    "ms" to Value.Object(
                                        "type" to Value.Str("NUMBER"),
                                        "description" to Value.Str("Milliseconds to wait (default 1000, clamp 100..10000)")
                                    )
                                )
                            )
                        ),
                        Value.Object(
                            "name" to Value.Str("get_screen"),
                            "description" to Value.Str("Inspects the current screen state and returns fresh UI element descriptions and screen text."),
                            "parameters" to Value.Object(
                                "type" to Value.Str("OBJECT"),
                                "properties" to Value.Object()
                            )
                        ),
                        Value.Object(
                            "name" to Value.Str("web_search"),
                            "description" to Value.Str("Searches the web for up-to-date information, news, or answers using search engine."),
                            "parameters" to Value.Object(
                                "type" to Value.Str("OBJECT"),
                                "properties" to Value.Object(
                                    "query" to Value.Object(
                                        "type" to Value.Str("STRING"),
                                        "description" to Value.Str("The search query string")
                                    )
                                ),
                                "required" to Value.Array(Value.Str("query"))
                            )
                        ),
                        Value.Object(
                            "name" to Value.Str("run_connector_command"),
                            "description" to Value.Str("Executes a connector command (e.g. smart home, music, calendar, notes, device actions)."),
                            "parameters" to Value.Object(
                                "type" to Value.Str("OBJECT"),
                                "properties" to Value.Object(
                                    "command" to Value.Object(
                                        "type" to Value.Str("STRING"),
                                        "description" to Value.Str("Connector command or intent to execute")
                                    )
                                ),
                                "required" to Value.Array(Value.Str("command"))
                            )
                        ),
                        Value.Object(
                            "name" to Value.Str("run_skill"),
                            "description" to Value.Str("Replays a saved skill macro in one tool call for a given user goal or skill name."),
                            "parameters" to Value.Object(
                                "type" to Value.Str("OBJECT"),
                                "properties" to Value.Object(
                                    "goal" to Value.Object(
                                        "type" to Value.Str("STRING"),
                                        "description" to Value.Str("The user's goal or intent to match and replay a saved skill for")
                                    ),
                                    "skill" to Value.Object(
                                        "type" to Value.Str("STRING"),
                                        "description" to Value.Str("Optional specific skill name or ID to replay directly")
                                    )
                                ),
                                "required" to Value.Array(Value.Str("goal"))
                            )
                        ),
                        Value.Object(
                            "name" to Value.Str("remember_fact"),
                            "description" to Value.Str("Saves a durable fact about the user (preference, name, likes, routine) to on-device Memory, silently. Use when the user tells you something worth remembering."),
                            "parameters" to Value.Object(
                                "type" to Value.Str("OBJECT"),
                                "properties" to Value.Object(
                                    "category" to Value.Object(
                                        "type" to Value.Str("STRING"),
                                        "description" to Value.Str("Category of the fact (e.g. preference, name, likes, routine)")
                                    ),
                                    "fact" to Value.Object(
                                        "type" to Value.Str("STRING"),
                                        "description" to Value.Str("The fact to save about the user")
                                    )
                                ),
                                "required" to Value.Array(Value.Str("fact"))
                            )
                        ),
                        Value.Object(
                            "name" to Value.Str("recall_memory"),
                            "description" to Value.Str("Recalls saved on-device Memory facts matching a query, using local keyword search."),
                            "parameters" to Value.Object(
                                "type" to Value.Str("OBJECT"),
                                "properties" to Value.Object(
                                    "query" to Value.Object(
                                        "type" to Value.Str("STRING"),
                                        "description" to Value.Str("Search query or keywords to find matching memories")
                                    )
                                ),
                                "required" to Value.Array(Value.Str("query"))
                            )
                        ),
                        Value.Object(
                            "name" to Value.Str("forget_memory"),
                            "description" to Value.Str("Deletes saved on-device Memory facts containing the given text."),
                            "parameters" to Value.Object(
                                "type" to Value.Str("OBJECT"),
                                "properties" to Value.Object(
                                    "fact" to Value.Object(
                                        "type" to Value.Str("STRING"),
                                        "description" to Value.Str("Fact text to delete from memory")
                                    )
                                ),
                                "required" to Value.Array(Value.Str("fact"))
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
