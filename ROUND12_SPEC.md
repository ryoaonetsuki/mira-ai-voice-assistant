# Mira AI ROUND-12 SPEC — Native Gemini Live rebuild (primary voice)

Date: 2026-11-06 ~23:35 +06. Supersedes round-11 (server-voice fix, agent stopped).
Direction (Salman's full spec, 23:34): Gemini Native Live is the PRIMARY voice engine.
Groq NOT required. Whisper NOT required. Server Voice Worker NOT required.
Separate Gemini TTS NOT required for the primary voice. No silent routing to
another engine. A missing Groq key must never block Live.

## 0. LIVE-TEST EVIDENCE (proven from this VM 2026-11-06 17:35–17:42 UTC, stored key)

Test scripts: `~/workspace/rea-analyses/mira/live-test-2026-11-06/`
(live_models_test.py, live_ws_raw.py, live_ws_audio.py, live_ws_audio2.py).
Key used: the stored `custom.gemini` credential via surrogate exchange
(entry `access_token`, placement `custom_header x-goog-api-key`).
The key VALUE was never visible. Salman's own in-app key is a DIFFERENT key
and was NEVER live-tested — see §0.5.

1. **Models list**: `GET /v1beta/models` → 61 models. `models/gemini-3.8-live`
   IS listed with `bidiGenerateContent`. The "key lacks Live access"
   hypothesis is WRONG for the stored key.
2. **Official docs example setup is REJECTED by the real server**:
   `responseModalities` at setup top level → server closes 1008:
   `Invalid JSON payload received. Unknown name "responseModalities" at
   'setup': Cannot find field.` It MUST live inside `generationConfig`
   (the current app placement is correct; the docs example is wrong).
3. **Header auth works end-to-end**: `x-goog-api-key` header → WS 101 in
   ~0.7s, `setupComplete` in ~1.1s, text turn → 10 audio chunks (97,920 B
   PCM) in ~2.6s, `turnComplete`; second turn works (7 chunks).
   Output transcript in Bangla: "হ্যালো! কেমন আছেন?" / "দুই যোগ দুই সমান চার।"
4. **`?key=` in the URL could NOT be proven**: the surrogate is not swapped
   into URLs by the egress (Google received the literal `hsurr:` value →
   "API key not valid"). This is a TEST-HARNESS limitation, NOT proof that
   `?key=<real key>` fails. But header auth IS proven, so the rebuild uses
   the header as the primary auth placement.
5. **Audio turn works**: 11s of real Bangla speech (16 kHz PCM, ~100 ms
   chunks) → `inputTranscription` captured the exact sentence, Bangla audio
   reply (34 chunks), `turnComplete`, follow-up text turn works, session
   stays alive. BUT: without `audioStreamEnd` after the audio, NO reply in
   30 s — the app MUST send `audioStreamEnd: true` when the mic stream
   pauses/ends, and MUST keep streaming frames continuously (including
   silence) during a session. (Wire-silence with no frames ≠ user silence.)
6. **All three phone setup variants (exact mirrors of GeminiLiveClient V1/V2/V3)
   get `setupComplete` in 0.6–1.1 s** → the setup payload is NOT the bug.
7. **Google's 101 response carries `Connection: close`** — strict WS
   libraries (Python `websockets`) reject it; OkHttp tolerates it. Keep OkHttp.
8. **Free tier**: `gemini-3.8-live` has a free tier (input+output free within
   limits; paid $0.005/min audio-in, $0.018/min audio-out). Audio-only
   session 15 min without compression; ~10 min per connection; resumption
   handles valid 2 h; context compression extends sessions indefinitely.
   Per-project rate limits are shown only in AI Studio — do NOT claim
   unlimited access.
9. **Best practices** (ai.google.dev/gemini-api/docs/live-api/best-practices):
   20–40 ms audio chunks; on `interrupted: true` discard the client audio
   buffer immediately; resample mic to 16 kHz; enable context compression;
   session resumption; handle GoAway; language instruction
   `RESPOND IN {LANG}. YOU MUST RESPOND UNMISTAKABLY IN {LANG}.`

### 0.5 Root-cause assessment (honest)

- The direct Live client code (setup shape, model, protocol) is CORRECT —
  proven live with the stored key. The phone's silence in rounds 2–8 is
  therefore NOT a setup-shape bug.
- Remaining explanations for the phone: (a) **Salman's own in-app key /
  project may lack Live access** (never tested — his key ≠ stored key);
  (b) the old auth ladder (ephemeral → direct → Supabase relay → Cloudflare
  relay) may never have reached a clean direct attempt; (c) in rounds 9–10
  the phone NEVER tried Live first (both keys present → Server Voice first;
  Live only as fallback after the server failed).
- The rebuild kills (b) and (c): one primary Live path, header auth, no
  ladder, no silent routing. (a) is surfaced by an in-app models-list check
  with HIS key (see §3.7).
- The server-voice failure ("Chat service unavailable") is a separate bug in
  the Worker path; it is bypassed by making Live primary and server voice
  disabled-by-default (§3.6).

## 1. ARCHITECTURE (selected and why)

- **Direct client-to-server WebSocket** (OkHttp), exactly as live-tested.
  No Firebase AI Logic: it would add a dependency and a second auth path
  for no proven benefit, and the direct WS is now proven working.
  No SDK (kotlin-genai) Live support needed: direct WS gives full control
  of setup, VAD, interruption, resumption.
- **Model**: `models/gemini-3.8-live` (GA since 15 Sep 2026), configurable
  in one place. Fallback ONLY to another currently supported Live model
  verified against the live models list at build time — NEVER the retired
  `gemini-2.5-flash-native-audio-preview-12-2025` /
  `gemini-3.1-flash-live-preview` (shut down 17 Nov 2026).
- **Auth**: `x-goog-api-key` header on the WS upgrade (proven). Ephemeral
  token support may be kept as an OPT-IN path only if it does not complicate
  the primary path; the primary path is the user's stored Gemini key from
  Setup. Never log keys/tokens/headers.
- **No STT→LLM→TTS pipeline anywhere in the primary path.** No Groq, no
  Whisper, no Worker, no separate TTS.

## 2. PROTOCOL — exact proven setup (do not deviate without a live test)

Endpoint:
`wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent`

Setup (single message, first, then WAIT for `setupComplete` before audio):
```json
{"setup": {
  "model": "models/gemini-3.8-live",
  "generationConfig": {
    "responseModalities": ["AUDIO"],
    "speechConfig": {"voiceConfig": {"prebuiltVoiceConfig": {"voiceName": "<chosen>"}},
                     "languageCode": "bn-BD"}
  },
  "realtimeInputConfig": {"automaticActivityDetection": {
    "endOfSpeechSensitivity": "END_SENSITIVITY_HIGH", "silenceDurationMs": 800}},
  "inputAudioTranscription": {},
  "outputAudioTranscription": {},
  "sessionResumption": {},
  "contextWindowCompression": {"slidingWindow": {}},
  "systemInstruction": {"parts": [{"text": "<Mira persona + Bangla-only rule + date>"}]}
}}
```
Notes:
- `responseModalities` ONLY inside `generationConfig` — never at setup top
  level (server rejects it, proven §0.2).
- `languageCode` in `speechConfig` (V3 shape, proven).
- No `googleSearch` tool in Live setup.
- System instruction must contain the Bangla-only rule in the best-practice
  form: `RESPOND IN BANGLA (BENGALI). YOU MUST RESPOND UNMISTAKABLY IN
  BANGLA.` plus Mira persona ("I am Mira, your companion"), friendly/warm,
  and the current date/time from the device clock.
- Voice: supported prebuilt voice, configurable in Settings (default Mira
  female; the male persona uses its own voice). Same 30-voice picker.

Audio in: `{"realtimeInput": {"audio": {"data": "<base64 PCM16>",
"mimeType": "audio/pcm;rate=16000"}}}` — PCM16 16 kHz mono LE, 20–40 ms
chunks, streamed continuously including silence frames. On mic pause /
session end: `{"realtimeInput": {"audioStreamEnd": true}}`.
Audio out: `serverContent.modelTurn.parts[].inlineData.data` (base64 PCM16
24 kHz mono LE) → AudioTrack streaming. Transcriptions:
`serverContent.inputTranscription` / `outputTranscription`.
Interruption: `serverContent.interrupted == true` → flush playback queue
immediately. `turnComplete`, `generationComplete`, `goAway` (reconnect with
resumption handle), `sessionResumptionUpdate.newHandle` (persist, never log).

## 3. REQUIRED CHANGES

### 3.1 Rewrite the Live client (voice/GeminiLiveClient.kt or a new class)
Single-path, coroutine-based, StateFlow state machine:
IDLE / REQUESTING_PERMISSION / CONNECTING / CONFIGURING / LISTENING /
PROCESSING / SPEAKING / INTERRUPTED / RECONNECTING / ERROR / STOPPED.
Explicit timeouts (configurable constants): WS connect 20 s, setupComplete
45 s, first audio chunk after user speech 30 s stall → plain-words error +
retry, stalled stream 8 min give-up. Never stay silently stuck: on any
timeout/failure, stop safely, show the exact error category in plain words,
offer retry. Handle goAway (graceful reconnect with resumption handle),
mid-session drops (reconnect + resume), clean termination (close 1000,
release everything). No infinite retry loops (max 2 auto-reconnects, then
ERROR with retry button). Remove the old 4-mode auth ladder and the 3 setup
variants — ONE setup, ONE auth placement (header). Keep the pre-roll ring
and speaker pre-priming only if they do not complicate the new client; the
proven path needs neither to work.

### 3.2 Audio engine audit (voice/AudioRecorder.kt, voice/AudioPlayer.kt)
- AudioRecord: 16 kHz PCM16 mono, correct buffer, stable loop on a
  background thread, no main-thread blocking, ERROR_INVALID_OPERATION /
  ERROR_BAD_VALUE handling, clean release, no duplicate threads, continuous
  frames (silence included). Resample to 16 kHz if the device delivers
  another rate. AEC + noise suppression where available
  (AcousticEchoCanceler / NoiseSuppressor, guarded by availability checks).
- AudioTrack: streaming, 24 kHz PCM16 mono, low-latency where supported,
  managed bounded queue, no corrupted/overlapping chunks, clean release.
  Full-duplex; audio focus + MODE_IN_COMMUNICATION as appropriate.
- Barge-in: on user speech-start while speaking (server `interrupted` OR
  local VAD speech-start) → stop/flush playback immediately, continue.
- Mic amplitude feeds the orb from REAL mic/playback activity only.

### 3.3 Routing (service/MicService.kt) — THE core fix
- Voice button ALWAYS starts Gemini Live first. Groq key presence/absence
  must NEVER change this. Delete the "both keys → ServerVoiceSession"
  branch.
- No silent fallback to any other engine. If Live fails: show the real
  error + retry. The user may explicitly enable an alternative engine in
  Settings (see §3.6); it must be OFF by default and clearly labelled.
- Prevent duplicate sessions from repeated taps. One session at a time.

### 3.4 Diagnostics
- Log tag `MIRA_LIVE_VOICE` for EVERY event: button tapped, permission
  granted/denied, AudioRecord init/start, WS connecting/opened/failure
  (code), setup sent/completed (ms), first mic chunk sent, chunk counts,
  first server response, transcriptions, model response, first audio chunk,
  AudioTrack init/playback start/interrupt, turn complete, WS closed,
  API error (code + message, no keys), timeout, reconnect start/complete.
  Never log keys, tokens, headers, transcripts' full text is fine (not
  sensitive) but never audio bytes.
- Diagnostics screen (Settings → Voice diagnostics, or a debug card):
  last session stage reached, WS code, setup ms, chunks sent/received,
  last error in plain words, key Live-capability result (§3.7). This must
  let Salman (via screenshot) show exactly where a session fails.

### 3.5 UI
Preserve existing UI. Voice button states: connecting / listening /
speaking / error (plain words) / end. Orb animation driven by REAL mic
amplitude / playback state only. Do not break text chat.

### 3.6 Isolate the old pipeline (do not delete files unnecessarily)
- `ServerVoiceSession.kt`, `ServerVoiceClient.kt`, `FallbackVoiceSession.kt`:
  retained but unreachable by default. Gate behind an explicit Settings
  toggle "Alternative voice (Server Voice)" default OFF, labelled
  experimental; when OFF, no code path may start them. Remove any
  auto-selection. Groq key field stays in Setup/Settings (optional for the
  alternative engine; it must NOT block entering the app or starting Live).
  The Deepgram optional field stays as-is.
- The Cloudflare server-voice Worker is NOT touched in this build.

### 3.7 In-app Live-capability check (new, decisive)
At Setup (after Gemini key entry) and in Settings → Voice, run
`GET https://generativelanguage.googleapis.com/v1beta/models?key=…`
(actually via the `x-goog-api-key` header like the rest of the app) and
report plainly: "Live voice available on this key" if
`models/gemini-3.8-live` with `bidiGenerateContent` is listed, else
"This key cannot use Live voice — create a new key in Google AI Studio".
This is the exact test that was never run with his key.

### 3.8 Home name bug (from round-11)
Do NOT render the user's name anywhere on Home — remove it from Home
entirely. Name stays in Settings only. Wherever the name is shown, it must
ellipsize/fit and never overflow/clip a layout.

### 3.9 Build error (reported)
Check `ChatScreen.kt:241:42 Modifier.background(...)` Kotlin compilation
error — fix it if it still exists/blocks the build.

## 4. TESTING
- Build: compile, resolve Kotlin/Gradle errors, verify Manifest
  permissions, referenced classes/methods, APK generation.
- Protocol unit tests (new, in-app tests where the project has them):
  setup JSON shape (assert NO top-level `responseModalities`; assert
  `generationConfig.responseModalities == ["AUDIO"]`), PCM chunk format
  (16-bit LE, 16 kHz), audio base64 round-trip, state-machine transitions,
  timeout behaviour. Mocks/test doubles where live access is unavailable.
- Live API test from this VM (already done by the coordinator, §0 — the
  worker does NOT need to redo it, but must not contradict it): header
  auth + phone V1/V2/V3 setups + text turns + audio turn all proven.
- Physical device: NO device is available — do NOT claim device verification.

## 5. CONSTRAINTS (keep from rounds 1–10)
Fast Setup validation (12 s cap; transient 503/429 = save + enter with
amber notice; only real 400/401/403 blocks). Gemini key REQUIRED to enter
the app; Groq/Deepgram OPTIONAL. Chat toggles Web Search / Deep Thinking
inbuilt ON/OFF. File upload via Gemini Files API. Date/time from device
clock. Supabase backend licence OFF. Bangla default + one-language-only.
Chat logo avatars. File Management permission REQUIRED in setup. Home =
exactly ONE status line under the orb, "Tap the voice button to start"
when idle, 3 cards (Weather/Date/Mood). Voice search acknowledgement spoken
aloud "এক মিনিট, আমি খুঁজে বলছি" when search is needed. Barge-in. No wake
word. Material icons. Keys never logged. No full UI polish pass.

## 6. DELIVERY
Report: root cause; research findings + references used; architecture +
why; files created/modified/removed; exact changes; model + auth method;
build results; automated test results; live API test results (cite §0);
physical device test results (NONE — say so); remaining limitations;
exact setup instructions (only a Gemini key is required); APK
path/size/md5 + run number. Verify the APK with aapt2 before reporting.
Do NOT claim voice works until Salman phone-tests it.
