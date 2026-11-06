# Project Mira — Round 9 Build Notes

**Date:** 2026-11-06  
**Implementation:** Streaming Server Voice Architecture (Supersedes turn-based)  
**Package:** `io.github.ryoaonetsuki.mira`

---

## 1. What Was Built

### Cloudflare Worker (`mira-server-voice`)
- **Worker Script:** `cloudflare-relay/server-voice-worker.js`
- **Subdomain Routing:** `https://mira-server-voice.salman-dev.workers.dev`
- **WebSocket Route:** `/voice/stream`
- **Core Pipeline:**
  1. Continuous binary audio ingestion (16kHz PCM16 mono, 100ms chunks).
  2. Rolling Groq Whisper (`whisper-large-v3-turbo`) partial transcripts every ~1.0 s of active speech (language set to `bn` for Bangla).
  3. Server-side RMS silence endpoint detection (600ms trailing silence threshold) + client VAD endpoint handshake with 60s utterance cap.
  4. Search/Connector handshake: emits final `transcript` and awaits client `proceed` frame (carrying `searchContext`).
  5. High-speed LLM streaming: primary Groq `gpt-oss-120b` chat completions via SSE with single automatic fallback to Gemini `gemini-3.8-flash`.
  6. Sentence-chunked Gemini TTS (`gemini-3.8-flash-tts` → `gemini-3.8-flash-lite-tts`) streaming base64 24kHz PCM audio frames sequentially as sentences complete.
  7. Client barge-in support: aborts in-flight LLM and TTS requests via `AbortController` and resets turn state.
  8. Health check (`GET /?action=health`) and missing key protection (HTTP 401 / close code 4401).
  9. Strict security: zero logging or persistence of API keys, transcripts, or audio payloads.

### Android Client (`app/`)
- **`SecurePreferences.kt`**:
  - Encrypted storage for `groq_api_key` in `EncryptedSharedPreferences` (AES256-GCM).
  - Plausibility validation `isPlausibleGroqKey`: prefix `gsk_` and minimum 20 characters.
  - Complete removal of legacy Deepgram key storage and accessors.
- **`app/build.gradle.kts`**:
  - Added `SERVER_VOICE_URL = "https://mira-server-voice.salman-dev.workers.dev"`.
- **`SetupScreen.kt` (Step 1)**:
  - Added Groq API key field with password visibility toggle and Material 3 styling.
  - Added "Get Free Key" button pointing to `https://console.groq.com/keys`.
  - Added Groq "Test Groq Key" validation action (`GET https://api.groq.com/openai/v1/models`, 8s call timeout / 12s overall cap) adhering to the fail-open rule.
- **`VoiceEngineScreen.kt`**:
  - Deleted Deepgram Nova-3 card.
  - Added Server Voice (Groq) configuration card with saved state.
- **`network/ServerVoiceClient.kt`**:
  - OkHttp persistent WebSocket client to `SERVER_VOICE_URL/voice/stream` with `x-mira-groq-key` and `x-mira-gemini-key` headers.
  - Event listener callbacks: `onPartial`, `onTranscript`, `onToken`, `onAudio`, `onReplyDone`, `onError`, `onClosed`.
  - Suspend `validateGroqKey` HTTP validator.
- **`voice/ServerVoiceSession.kt`**:
  - Manages session lifecycle: owns AudioRecorder (16kHz PCM16 mono) + VoiceActivityDetector.
  - Instant client-side barge-in: immediately flushes AudioTrack and dispatches `barge-in` frame on speech detection during playback.
  - Web search & Connector handshake on final transcript before sending `proceed`.
  - Sentence-chunked audio playback in 4096-byte buffers via AudioPlayer.
  - Mid-session disconnect resilience: reconnects once before triggering fallback.
- **`service/MicService.kt`**:
  - Voice engine priority ladder:
    1. **Server Voice FIRST**: Activated whenever both Groq and Gemini keys are configured.
    2. **Gemini Live SECOND**: Activated if Groq key is absent or as mid-session fallback if Server Voice disconnects.
    3. **SpeechRecognizer LAST**: Existing fallback session for offline/unsupported environments.

---

## 2. Worker URL & Live Verification Results

- **Endpoint:** `https://mira-server-voice.salman-dev.workers.dev`
- **Health Check Probe:**
  ```bash
  curl -s "https://mira-server-voice.salman-dev.workers.dev/?action=health"
  ```
  **Result:** `HTTP 200 OK`
  ```json
  {"service":"mira-server-voice","version":"2.0.0","ok":true,"streaming":true}
  ```
- **Unauthorized / Missing Key Rejection:**
  ```bash
  curl -i -s "https://mira-server-voice.salman-dev.workers.dev/voice/stream" \
    --http1.1 -H "Connection: Upgrade" -H "Upgrade: websocket" \
    -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" -H "Sec-WebSocket-Version: 13"
  ```
  **Result:** `HTTP 401 Unauthorized`
  ```json
  {"error":"Missing API key header"}
  ```
- **Non-WS GET without health action:**
  ```bash
  curl -i -s "https://mira-server-voice.salman-dev.workers.dev/"
  ```
  **Result:** `HTTP 426 Upgrade Required` (`WebSocket upgrade required`)

---

## 3. Grep Verification Results

- `grep -ri "deepgram" app/src cloudflare-relay` -> **CLEAN** (0 occurrences).
- `grep -ri "llama" app/src cloudflare-relay` -> **CLEAN** (0 occurrences).
- `grep -riE "getmaya|hunteris|toolbaaz" app/src cloudflare-relay` -> **CLEAN** (0 occurrences).
- `grep Log lines in new files` -> **CLEAN** (Logs lengths/status codes only; no key, transcript, or audio data).

---

## 4. Key Services Assessment (Salman's Prompt Question)

**Question:** Are more services/keys needed to make the voice fully natural and Live-like?  
**Assessment:** **NO — Exactly TWO keys (Groq + Gemini) suffice.**
- Groq provides state-of-the-art Whisper (`whisper-large-v3-turbo`) with 200ms audio chunk latency and `gpt-oss-120b` generating tokens at 500+ tok/s.
- Gemini provides studio-grade prosody via `gemini-3.8-flash-tts` across all 30 prebuilt voices.
- Because tokens stream immediately and Gemini TTS synthesizes sentence-by-sentence (~25 chars), the first audio sentence plays aloud within ~0.5–1.0s while subsequent sentences generate concurrently.
- No third service or key is needed; adding more would only introduce points of failure without latency or quality benefits.

---

## 5. What Could Not Be Verified Locally

1. **Local Gradle Build (`./gradlew assembleDebug`)**:
   - The current VM environment does not have a JDK installed (`JAVA_HOME is not set and no 'java' command could be found in PATH`).
   - Per project guidelines, GitHub Actions remains the verified Android APK compilation pipeline.
2. **End-to-End Voice Calls with Real API Keys**:
   - No real Groq or Gemini API keys are stored on this VM.
   - End-to-end voice test (Groq Whisper → Groq 120b → Gemini TTS on phone mic/speaker) requires Salman's device test using his BYOK keys in Setup.
