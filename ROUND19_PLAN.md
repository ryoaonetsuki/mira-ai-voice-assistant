# Round 19 (Pipecat Voice Fix) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a production-grade, transparent auto-reconnect voice system for Mira AI using Pipecat client architecture (`ai.pipecat:client:1.2.0`), Gemini Live WebSocket session resumption, context window compression, GoAway proactive reconnection, and Stage-4 silence watchdog.

**Architecture:** A layered architecture where `MicService` and the UI interact exclusively with `GeminiSessionManager`. `GeminiSessionManager` manages the session lifecycle and auto-reconnection state machine, wrapping a BSD-attributed forked Pipecat transport (`MiraGeminiLiveTransport` + `MiraGeminiClient`) running on the official `ai.pipecat:client:1.2.0` core. Stored resumption handles, GoAway events, Stage-4 timeouts (15s), and audio streams flow cleanly through typed callbacks, preserving Cloudflare Relay and Direct Google API paths.

**Tech Stack:** Native Kotlin, Jetpack Compose, Coroutines/StateFlow, `ai.pipecat:client:1.2.0`, `com.squareup.okhttp3:okhttp:4.12.0`, `kotlinx-serialization-json`, Android AudioRecord (16kHz PCM mono) & AudioTrack (24kHz PCM mono).

**Spec:** `~/workspace/mira-aider/voice-fix-prompt.md` and `SPEC.md`.

---

## Global Constraints

- **Scope:** Voice ONLY. Do NOT modify chat, image generation, themes, navigation, or database schema.
- **Model:** ALWAYS explicit `"models/gemini-3.8-live"`. Never rely on Pipecat's deprecated default (`models/gemini-3.1-flash-live-preview`).
- **Modalities:** `generationConfig.responseModalities = ["AUDIO"]` ONLY. Never TEXT + AUDIO together. Never top-level.
- **Resumption Config:** `sessionResumption = { "handle": null }` (or omitted) for new sessions; `{ "handle": storedHandle }` on resumption.
- **Compression Config:** `contextWindowCompression = { "slidingWindow": {} }` on every setup to enable indefinite session length.
- **Transparent Mode:** NEVER include `transparent = true` (Vertex AI only; rejected by Gemini API / AI Studio).
- **Security:** BYOK Gemini key is encrypted on-device; pass decrypted key in-memory only. Resumption handle strings and API keys must NEVER be logged to logcat.
- **Free Tier Safety:** Free tier allows max 3 concurrent sessions. Old WebSockets MUST be explicitly closed with code 1000 before/during reconnect.
- **Audio Continuity:** Drop audio silently during the 1–2s reconnection gap; do NOT buffer or replay. Do NOT send `realtimeInput` while `RECONNECTING`.
- **Licensing:** Sokuji is AGPL-3.0 — DO NOT COPY Sokuji code. Pipecat is BSD-2-Clause — forking transport files with attribution header is fully compliant.

---

## Review Focus

1. **Stage-4 silence hang:** Server accepts WebSocket upgrade (HTTP 101) but never sends `setupComplete`. Without a watchdog, Pipecat's `connect()` promise never resolves. The plan must enforce an explicit 15s setup timeout that triggers exponential backoff reconnection.
2. **Handle discarding in stock transport:** Stock Pipecat 1.2.2 parses `sessionResumptionUpdate` and explicitly discards `newHandle`, and only logs `goAway`. Stock binary CANNOT auto-reconnect. Transport layer must be forked to intercept both messages.
3. **Handle invalidation & race conditions:** Storing handles during active model output or tool execution causes resume failures. Handle must only be stored when `resumable == true`. Each handle is single-use and valid for max 2 hours.
4. **Free-tier session leakage:** Rapid network reconnections without closing previous WebSockets exhaust Google's 3-session quota. Old WebSockets must be closed immediately upon reconnect trigger.
5. **Route compatibility:** Stock Pipecat hardcodes `https://generativelanguage.googleapis.com/...` with `?key=...`. Mira requires supporting both Cloudflare Relay (`wss://mira-gemini-live-relay.salman-dev.workers.dev/live` with `x-mira-gemini-key`) and Direct Google API (`x-goog-api-key`).

---

## Architecture & System Design

```
+-----------------------------------------------------------------------------------+
|                                 UI Layer                                          |
|  HomeScreen (Orb Animation)  |  VoiceEngineScreen (Voices + GoAway Sim)           |
+-----------------------------------------------------------------------------------+
                                       ▲
                                       │ StateFlow (liveState, micAmplitude, transcripts)
+-----------------------------------------------------------------------------------+
|                               MicService                                          |
|  Foreground Service Lifecycle  |  Audio Focus  |  Notification Controls           |
+-----------------------------------------------------------------------------------+
                                       ▲
                                       │ startSession() / stopSession() / Listener
+-----------------------------------------------------------------------------------+
|                          GeminiSessionManager                                     |
|  - Reconnection State Machine (IDLE -> CONNECTING -> CONNECTED -> ... )          |
|  - Resumption Handle Gating (resumable == true, 2h TTL, single-use)              |
|  - Exponential Backoff Scheduler (1s, 2s, 3s, 4s, 5s max)                        |
|  - Stage-4 Silence Watchdog (15s setupComplete timeout)                           |
|  - GoAway Proactive Handler & Dev Simulator                                       |
|  - Concurrent Session Guard (closes old WS before reconnect)                     |
+-----------------------------------------------------------------------------------+
                                       ▲
                                       │ PipecatClient<MiraGeminiLiveTransport>
+-----------------------------------------------------------------------------------+
|                   Pipecat Core (ai.pipecat:client:1.2.0)                         |
|  TransportContext, ThreadRef, PipecatEventCallbacks, Value types                |
+-----------------------------------------------------------------------------------+
                                       ▲
                                       │ Transport delegate
+-----------------------------------------------------------------------------------+
|         Forked Transport Layer (BSD-2-Clause, io.github.ryoaonetsuki.mira.voice.pipecat)
|  - MiraGeminiLiveTransport: Maps RTVI events, routes audio levels & transcripts   |
|  - MiraGeminiClient: OkHttp WS, intercepts sessionResumptionUpdate & goAway      |
|  - Endpoint Routing: Direct Google API (x-goog-api-key) vs Cloudflare Relay       |
|  - AudioIn: 16kHz PCM AudioRecord with RMS volume calculation                     |
|  - AudioOut: 24kHz PCM AudioTrack with RMS volume calculation                     |
+-----------------------------------------------------------------------------------+
```

---

## Resolution of Open Questions

### 1. Custom Setup `modelConfig` with Session Resumption & Compression
`GeminiServiceOptions` in Pipecat is a data class with a `public` constructor accepting `modelConfig: Value`. We do not rely on `withDefaults()` (which hardcodes a deprecated model and lacks resumption/compression). Instead, we construct `modelConfig` using Pipecat's `Value.Object` hierarchy:

```kotlin
fun buildPipecatModelConfig(
    model: String = "models/gemini-3.8-live",
    voiceName: String = "Aoede",
    languageCode: String = "bn-BD",
    systemInstructionText: String = "",
    resumptionHandle: String? = null
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

    return Value.Object(
        "model" to Value.Str(model),
        "generationConfig" to generationConfig,
        "realtimeInputConfig" to realtimeInputConfig,
        "inputAudioTranscription" to Value.Object(),
        "outputAudioTranscription" to Value.Object(),
        "sessionResumption" to sessionResumption,
        "contextWindowCompression" to contextCompression,
        "systemInstruction" to systemInstruction
    )
}
```

### 2. Message Interception: Resumption, GoAway, SetupComplete, & Transcripts
**Finding from source code:** In stock `ai.pipecat:gemini-live-websocket-transport:1.2.2`, `GeminiClient.kt` lines 484–495:
- `sessionResumptionUpdate` is parsed and the handle is discarded (`Log.d(TAG, "Session resumption update (resumable: ...)")`).
- `goAway` is parsed and only logged (`Log.w(TAG, "Server will terminate the connection soon...")`).
- `inputAudioTranscription` and `outputAudioTranscription` are not parsed into any callbacks.
- The WebSocket URL is a hardcoded constant `private const val API_URL = "https://generativelanguage.googleapis.com/..."` with no relay support.

**Solution:** Fork the 5 transport files under BSD-2-Clause attribution into `io.github.ryoaonetsuki.mira.voice.pipecat`:
- `MiraGeminiClient.kt` exposes:
  - `onSessionResumptionUpdate(handle: String, resumable: Boolean)`
  - `onGoAway(timeLeft: String)`
  - `onUserTranscript(text: String)`
  - `onModelTranscript(text: String)`
- `MiraGeminiLiveTransport.kt` forwards these events directly to `GeminiSessionManager`.
- `MiraGeminiClient.kt` accepts a configurable `endpointUrl` and `headers` map, supporting both Direct Google API and Cloudflare Relay.

### 3. UI Wiring: Audio Levels, Transcripts, Speaking State, Voice Picker, & Personas
- **Audio Levels (Orb Animation):**
  `AudioIn` and `AudioOut` compute RMS normalized to `[0.0f, 1.0f]`. `MiraGeminiLiveTransport` delivers them via `onUserAudioLevel(level)` and `onRemoteAudioLevel(level, botParticipant)`. `GeminiSessionManager` forwards the active level to `MicService._micAmplitude`, which directly drives the ChatGPT-style orb pulse on `HomeScreen`.
- **Speaking State:**
  Server `modelTurn` and `interrupted` events trigger `onBotStartedSpeaking()` and `onBotStoppedSpeaking()`, mapping to `LiveState.SPEAKING` and `LiveState.LISTENING` in `MicService`.
- **Transcripts:**
  `onUserTranscript(text)` updates `MicService._userTranscript` and triggers user command handlers. `onModelTranscript(text)` updates `MicService._modelTranscript`.
- **30-Voice Picker & Personas:**
  `VoiceEngineScreen` and `MayaSettingsScreen` save the chosen voice to `UserPreferences.voiceNameFlow`. When `GeminiSessionManager` connects, it reads this voice and injects it into `speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName`.

### 4. Gradle Dependencies & Version Compatibility
- Dependencies to add in `app/build.gradle.kts`:
  ```kotlin
  // Pipecat Core RTVI Client (ROUND 19)
  implementation("ai.pipecat:client:1.2.0")
  // KotlinX Serialization JSON for RTVI Value structures
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
  ```
- **Conflict Analysis:**
  - `ai.pipecat:client:1.2.0` uses `com.squareup.okhttp3:okhttp:4.12.0` — EXACT match with Mira's existing OkHttp version.
  - Pipecat `minSdk = 24`, Mira `minSdk = 26` — Fully compatible.
  - Pipecat `compileSdk = 34`, Mira `compileSdk = 36` — Fully compatible.
  - Kotlin 2.2.20 / Compose BOM 2024.10.01 — Fully compatible; no duplicate class or binary incompatibilities.

### 5. Stage-4 Silence Detection & Watchdog Placement
- **Location:** Managed inside `GeminiSessionManager` with a 15-second `setupWatchdogJob`.
- **Mechanism:**
  1. When `connect()` is called and the WebSocket opens, `setupWatchdogJob` starts with `delay(15_000L)`.
  2. If `setupComplete` arrives before 15s, `setupWatchdogJob` is cancelled.
  3. If 15s elapses while state is still `CONFIGURING`:
     - Old WebSocket is immediately cancelled/closed.
     - Logs `MIRA_LIVE_VOICE: Stage-4 silence detected (setup sent, no setupComplete within 15s)`.
     - Reconnection logic is triggered with exponential backoff (attempt 1..5).
     - If all 5 attempts fail, transitions to `ERROR` with message `"Live voice: Setup timed out after 15s — tap to retry"`.

### 6. Dev-Only GoAway Simulator
- In `VoiceEngineScreen.kt`, within the Live Capability card or Diagnostics section, a debug-only button `"Simulate GoAway (Auto-Reconnect)"` is added.
- Tapping it calls `MicService.simulateGoAwayForTesting()`, which invokes `GeminiSessionManager.simulateGoAway()`.
- The manager simulates receiving `{"goAway": {"timeLeft": "60s"}}`, transitions to `GOAWAY_RECEIVED` -> `RECONNECTING`, and seamlessly resumes the session using the stored handle while the user watches the "Reconnecting..." indicator update on screen.

---

## State Machine Specification (`GeminiSessionManager`)

### State Transition Diagram

```
                 +-------------------+
                 |       IDLE        |
                 +-------------------+
                           │ startSession()
                           ▼
                 +-------------------+
                 |    CONNECTING     | <------------------------------------+
                 +-------------------+                                      |
                           │ WS Open (HTTP 101)                             |
                           ▼                                                |
                 +-------------------+                                      |
                 |    CONFIGURING    |                                      |
                 +-------------------+                                      |
                    │             │                                         |
     setupComplete  │             │ Stage-4 Timeout (15s)                   |
     received       │             ▼                                         |
                    │    +-------------------+                              |
                    │    |   RECONNECTING    |                              |
                    │    +-------------------+                              |
                    │              ▲                                        |
                    ▼              │ GoAway (resumable) / Close 1006        |
         +--------------------+    │                                        |
         |     CONNECTED      | ───┤                                        |
         | (Audio Streaming)  |    │                                        |
         +--------------------+    │                                        |
            │              │       │                                        |
            │ GoAway       │ Error │                                        |
            ▼              │       │                                        |
   +------------------+    │       │                                        |
   | GOAWAY_RECEIVED  | ───┘       │                                        |
   +------------------+            │                                        |
                                   │ Backoff Delay (1s..5s)                 |
                                   +────────────────────────────────────────+
                                   │ Max Retries Exceeded (>5) / Fatal
                                   ▼
                         +-------------------+
                         |       ERROR       |
                         +-------------------+
```

### Exact Setup Payloads

#### 1. Initial Connection Setup Frame (`handle = null`)
```json
{
  "setup": {
    "model": "models/gemini-3.8-live",
    "generationConfig": {
      "responseModalities": [
        "AUDIO"
      ],
      "speechConfig": {
        "voiceConfig": {
          "prebuiltVoiceConfig": {
            "voiceName": "Aoede"
          }
        },
        "languageCode": "bn-BD"
      }
    },
    "realtimeInputConfig": {
      "automaticActivityDetection": {
        "endOfSpeechSensitivity": "END_SENSITIVITY_HIGH",
        "silenceDurationMs": 800
      }
    },
    "inputAudioTranscription": {},
    "outputAudioTranscription": {},
    "sessionResumption": {},
    "contextWindowCompression": {
      "slidingWindow": {}
    },
    "systemInstruction": {
      "parts": [
        {
          "text": "You are Mira, a friendly AI companion."
        }
      ]
    }
  }
}
```

#### 2. Resumption Setup Frame (`handle = storedHandle`)
```json
{
  "setup": {
    "model": "models/gemini-3.8-live",
    "generationConfig": {
      "responseModalities": [
        "AUDIO"
      ],
      "speechConfig": {
        "voiceConfig": {
          "prebuiltVoiceConfig": {
            "voiceName": "Aoede"
          }
        },
        "languageCode": "bn-BD"
      }
    },
    "realtimeInputConfig": {
      "automaticActivityDetection": {
        "endOfSpeechSensitivity": "END_SENSITIVITY_HIGH",
        "silenceDurationMs": 800
      }
    },
    "inputAudioTranscription": {},
    "outputAudioTranscription": {},
    "sessionResumption": {
      "handle": "c2Vzc2lvbl9oYW5kbGVfZXhhbXBsZV8xMjM0NQ"
    },
    "contextWindowCompression": {
      "slidingWindow": {}
    },
    "systemInstruction": {
      "parts": [
        {
          "text": "You are Mira, a friendly AI companion."
        }
      ]
    }
  }
}
```

---

## File-by-File Change List

| Status | File Path | Responsibility |
|---|---|---|
| **Modify** | `app/build.gradle.kts` | Add `ai.pipecat:client:1.2.0` and `kotlinx-serialization-json`. |
| **Create** | `app/src/main/java/io/github/ryoaonetsuki/mira/voice/pipecat/MiraGeminiServiceOptions.kt` | Options data class holding apiKey, endpointUrl, authHeaderName, modelConfig, initialUserMessage. |
| **Create** | `app/src/main/java/io/github/ryoaonetsuki/mira/voice/pipecat/AudioIn.kt` | 16kHz PCM audio capturer with RMS audio level calculation. Attributed BSD-2-Clause. |
| **Create** | `app/src/main/java/io/github/ryoaonetsuki/mira/voice/pipecat/AudioOut.kt` | 24kHz PCM audio player with RMS audio level calculation. Attributed BSD-2-Clause. |
| **Create** | `app/src/main/java/io/github/ryoaonetsuki/mira/voice/pipecat/MiraGeminiClient.kt` | OkHttp WebSocket client intercepting `sessionResumptionUpdate`, `goAway`, setupComplete, and transcripts. Attributed BSD-2-Clause. |
| **Create** | `app/src/main/java/io/github/ryoaonetsuki/mira/voice/pipecat/MiraGeminiLiveTransport.kt` | RTVI Transport implementation bridging `MiraGeminiClient` to `PipecatClient`. Attributed BSD-2-Clause. |
| **Create** | `app/src/main/java/io/github/ryoaonetsuki/mira/voice/pipecat/GeminiSessionManager.kt` | Auto-reconnect state machine, handle persistence/expiry, backoff, Stage-4 watchdog, GoAway simulator. |
| **Modify** | `app/src/main/java/io/github/ryoaonetsuki/mira/voice/SessionResumptionStore.kt` | Add 2-hour TTL expiration check and single-use `consumeHandle()`. |
| **Modify** | `app/src/main/java/io/github/ryoaonetsuki/mira/service/MicService.kt` | Delegate Live sessions to `GeminiSessionManager`, connect audio amplitude, transcripts, and route status. |
| **Modify** | `app/src/main/java/io/github/ryoaonetsuki/mira/voice/LiveVoiceDiagnostics.kt` | Add reconnection counters and GoAway diagnostic fields. |
| **Modify** | `app/src/main/java/io/github/ryoaonetsuki/mira/ui/screens/settings/VoiceEngineScreen.kt` | Add GoAway simulation button for debug testing. |
| **Create** | `app/src/test/java/io/github/ryoaonetsuki/mira/voice/pipecat/GeminiSessionManagerTest.kt` | Unit tests for reconnection state machine, handle storage, backoff, and Stage-4 timeout. |
| **Preserve** | `app/src/main/java/io/github/ryoaonetsuki/mira/voice/GeminiLiveClient.kt` | Keep intact as fallback reference. |
| **Preserve** | `app/src/main/java/io/github/ryoaonetsuki/mira/voice/RelayLiveClient.kt` | Keep intact for relay endpoint configuration. |

---

## Tasks & Turn-by-Turn Implementation Plan

### Task 1: Add Dependencies & Resumption Store Hardening (Turn 2)

**Files:**
- Modify: `app/build.gradle.kts:96-100`
- Modify: `app/src/main/java/io/github/ryoaonetsuki/mira/voice/SessionResumptionStore.kt:1-45`
- Create: `app/src/test/java/io/github/ryoaonetsuki/mira/voice/SessionResumptionStoreTest.kt`

**Interfaces:**
- Consumes: Android `SharedPreferences`.
- Produces: `SessionResumptionStore.saveHandle(handle: String, timestampMs: Long = System.currentTimeMillis())`, `SessionResumptionStore.consumeHandle(): String?` (returns valid handle if age < 2 hours, then clears it).

- [ ] **Step 1: Write failing test for 2-hour TTL and single-use handle**
  In `SessionResumptionStoreTest.kt`, verify:
  1. A saved handle is returned within 2 hours.
  2. `consumeHandle()` returns the handle and immediately clears it so a subsequent call returns `null`.
  3. A handle older than 2 hours (`7_200_000 ms`) is rejected and returns `null`.
- [ ] **Step 2: Add Pipecat dependency in `app/build.gradle.kts`**
  Add `implementation("ai.pipecat:client:1.2.0")` and `implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")`.
- [ ] **Step 3: Update `SessionResumptionStore.kt`**
  Add `KEY_HANDLE_TIMESTAMP`, enforce 2-hour TTL, and implement atomic `consumeHandle()`.
- [ ] **Step 4: Run unit test to verify pass**
  Run: `./gradlew testDebugUnitTest --tests "*.SessionResumptionStoreTest"`

---

### Task 2: Fork and Enhance Pipecat Transport Layer (Turn 2)

**Files:**
- Create: `app/src/main/java/io/github/ryoaonetsuki/mira/voice/pipecat/MiraGeminiServiceOptions.kt`
- Create: `app/src/main/java/io/github/ryoaonetsuki/mira/voice/pipecat/AudioIn.kt`
- Create: `app/src/main/java/io/github/ryoaonetsuki/mira/voice/pipecat/AudioOut.kt`
- Create: `app/src/main/java/io/github/ryoaonetsuki/mira/voice/pipecat/MiraGeminiClient.kt`
- Create: `app/src/main/java/io/github/ryoaonetsuki/mira/voice/pipecat/MiraGeminiLiveTransport.kt`
- Create: `app/src/test/java/io/github/ryoaonetsuki/mira/voice/pipecat/MiraGeminiSetupPayloadTest.kt`

**Interfaces:**
- Consumes: `ai.pipecat.client.transport.Transport`, `ai.pipecat.client.types.Value`.
- Produces: `MiraGeminiLiveTransport(context: Context, options: MiraGeminiServiceOptions)`, `MiraGeminiClient.Listener` with `onSessionResumptionUpdate`, `onGoAway`, `onUserTranscript`, `onModelTranscript`, `onBotTalking`, `onUserTalking`, `onUserAudioLevel`, `onBotAudioLevel`.

- [ ] **Step 1: Write failing test for custom setup payload serialization**
  In `MiraGeminiSetupPayloadTest.kt`, assert:
  1. `model` is `"models/gemini-3.8-live"`.
  2. `generationConfig.responseModalities` is `["AUDIO"]`.
  3. `sessionResumption.handle` is present only when provided.
  4. `contextWindowCompression.slidingWindow` is present.
  5. Top-level `transparent` is NOT present.
- [ ] **Step 2: Implement `MiraGeminiServiceOptions.kt`**
  Include `apiKey`, `endpointUrl` (defaulting to Google API, configurable to Cloudflare Relay), `authHeaderName`, `modelConfig`, and `initialUserMessage`.
- [ ] **Step 3: Implement `AudioIn.kt` and `AudioOut.kt`**
  Forked from Pipecat with BSD-2-Clause header, managing 16kHz AudioRecord and 24kHz AudioTrack with RMS audio calculation.
- [ ] **Step 4: Implement `MiraGeminiClient.kt`**
  Include message handling for `sessionResumptionUpdate`, `goAway`, `inputAudioTranscription`, `outputAudioTranscription`, and headers map for Relay support.
- [ ] **Step 5: Implement `MiraGeminiLiveTransport.kt`**
  Implement `Transport<MiraGeminiServiceOptions>` wiring `MiraGeminiClient` into RTVI event callbacks.
- [ ] **Step 6: Run tests to verify pass**
  Run: `./gradlew testDebugUnitTest --tests "*.MiraGeminiSetupPayloadTest"`

---

### Task 3: Build `GeminiSessionManager` Core State Machine (Turn 2)

**Files:**
- Create: `app/src/main/java/io/github/ryoaonetsuki/mira/voice/pipecat/GeminiSessionManager.kt`
- Create: `app/src/test/java/io/github/ryoaonetsuki/mira/voice/pipecat/GeminiSessionManagerTest.kt`

**Interfaces:**
- Consumes: `MiraGeminiLiveTransport`, `SessionResumptionStore`, `PipecatClient`.
- Produces:
  ```kotlin
  class GeminiSessionManager(
      val context: Context,
      val apiKey: String,
      val voiceName: String,
      val language: String,
      val systemPrompt: String,
      val useRelay: Boolean,
      val resumptionStore: SessionResumptionStore,
      val listener: SessionManagerListener
  ) {
      val state: StateFlow<SessionManagerState>
      val audioLevel: StateFlow<Float>
      fun startSession()
      fun stopSession()
      fun simulateGoAwayForTesting()
  }
  ```

- [ ] **Step 1: Write unit tests for state machine transitions**
  In `GeminiSessionManagerTest.kt`, test:
  1. `IDLE` -> `CONNECTING` -> `CONFIGURING` -> `CONNECTED`.
  2. `sessionResumptionUpdate` with `resumable = true` saves handle; `resumable = false` ignores handle.
  3. `goAway` with valid handle transitions to `GOAWAY_RECEIVED` -> `RECONNECTING` -> `CONNECTED`.
  4. Stage-4 silence: no `setupComplete` within 15s triggers reconnect with backoff.
  5. Backoff schedule matches 1s, 2s, 3s, 4s, 5s (max 5 retries), then transitions to `ERROR`.
  6. Explicit `stopSession()` cancels reconnection and transitions to `STOPPED`.
  7. Concurrent session guard: old WebSocket is closed before reconnecting.
- [ ] **Step 2: Implement `GeminiSessionManager.kt`**
  Build state machine, coroutine-based timers, handle storage rules, exponential backoff, and Stage-4 watchdog.
- [ ] **Step 3: Run unit tests to verify pass**
  Run: `./gradlew testDebugUnitTest --tests "*.GeminiSessionManagerTest"`

---

### Task 4: UI & Service Wiring (Turn 3)

**Files:**
- Modify: `app/src/main/java/io/github/ryoaonetsuki/mira/service/MicService.kt:410-620`
- Modify: `app/src/main/java/io/github/ryoaonetsuki/mira/voice/LiveVoiceDiagnostics.kt:20-60`
- Modify: `app/src/main/java/io/github/ryoaonetsuki/mira/ui/screens/settings/VoiceEngineScreen.kt:320-370`

**Interfaces:**
- Consumes: `GeminiSessionManager`.
- Produces: Updated `MicService.liveState`, `MicService.micAmplitude`, `VoiceEngineScreen` debug simulator.

- [ ] **Step 1: Wire `GeminiSessionManager` in `MicService.kt`**
  Replace direct `GeminiLiveClient` creation with `GeminiSessionManager`:
  - Map `SessionManagerState.CONNECTED` -> `LiveState.LISTENING` / `LiveState.SPEAKING`.
  - Map `SessionManagerState.RECONNECTING` -> `LiveState.CONNECTING` (UI displays "Reconnecting...").
  - Connect `audioLevel` directly to `_micAmplitude`.
  - Connect transcripts to `_userTranscript` and `_modelTranscript`.
- [ ] **Step 2: Add GoAway Simulator in `VoiceEngineScreen.kt`**
  Add a debug button in `VoiceEngineScreen` calling `MicService.simulateGoAwayForTesting()`.
- [ ] **Step 3: Add Reconnection Diagnostics in `LiveVoiceDiagnostics.kt`**
  Record reconnect attempts, resumption handle events, and GoAway timestamps for display in logs and diagnostics.

---

### Task 5: End-to-End Verification & Regression Suite (Turn 4)

**Files:**
- Modify: `app/src/test/java/io/github/ryoaonetsuki/mira/voice/LiveDiagnosticRunnerTest.kt`
- Create: `app/src/test/java/io/github/ryoaonetsuki/mira/voice/pipecat/VoiceIntegrationRegressionTest.kt`

- [ ] **Step 1: Add regression tests for direct and relay routes**
  Verify that both Direct Google API and Cloudflare Relay configs generate expected requests and headers.
- [ ] **Step 2: Run full unit test suite**
  Run: `./gradlew testDebugUnitTest`
  Confirm 100% green across all existing and new tests.
- [ ] **Step 3: Self-review and lint verification**
  Ensure zero regressions in chat, notifications, or settings.

---

## Risks & Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| **Resumption on GA gemini-3.8-live behavior unverified** | Resumption handle might be rejected if server changed proto schema | Fallback cleanly to fresh session setup if resume handshake returns error code 400/1008; clear stale handle. |
| **Stage-4 silence root cause on cellular network** | Client may stall if carrier blocks bidirectional WS frames | 15s setupComplete watchdog terminates hung connection immediately and retries via Cloudflare Relay. |
| **Free-tier 3 concurrent session quota exhaustion** | Google rejects subsequent connections with 429 Resource Exhausted | Proactively call `ws.close(1000)` / `cancel()` on previous socket before launching reconnect socket. |
| **Audio glitch / burst after 1–2s gap** | Speaker emits distorted sound on reconnect | Silently drop all mic and speaker frames during `RECONNECTING` state; clear audio queues on reconnect. |
| **RTVI refactor complexity** | Potential regressions in mic or speaker playback | Fork minimal transport files (5 files, ~1000 lines total), keeping existing `AudioPlayer` and `AudioRecorder` logic as fallback. |

---

## Execution Handoff

Plan complete and saved to `ROUND19_PLAN.md`.
Please review the plan. Which execution approach would you prefer for implementing Round 19?
- **Subagent-driven** (fresh subagent per task with independent review gates)
- **Native** (inline execution task-by-task in this harness)

I recommend **Subagent-driven** because the transport fork, session manager state machine, and service wiring have well-defined independent interfaces and rigorous verification gates.
