# Mira AI — ROUND 10 SPEC (implement exactly this; nothing else)

Work in this repo only: `~/workspace/mira-final/` (repo `ryoaonetsuki/mira-ai-voice-assistant`, package `io.github.ryoaonetsuki.mira`). Kotlin + Jetpack Compose, Material icons only. Do NOT write code outside this repo. Do NOT touch any file unrelated to the three fixes below. Keep everything from rounds 1–9 intact (fast Setup validation with 12s cap and transient-503 fail-open, chat Web Search ON/OFF toggle, file upload, date injection from device clock, Supabase backend with licence OFF, Bangla default + one-language-only replies, chat logo avatars, File Management permission REQUIRED in setup, no wake word, Gemini Live + Cloudflare relays kept as secondary paths, API keys NEVER logged anywhere).

NEVER call `relay.getmaya.online`, `api.getmaya.cloud`, or any Maya/Hunter host. Do NOT change model names. Do NOT change audio formats (AudioRecorder 16kHz mono 3200-byte chunks; AudioPlayer 24kHz mono pre-primed).

## CONTEXT — the bug (already diagnosed from the code; do not re-diagnose the keys)

Round 9 delivered streaming server voice (Cloudflare Worker `mira-server-voice.salman-dev.workers.dev`, Groq Whisper + Groq gpt-oss-120b + Gemini TTS). Salman's phone test: Home shows LISTENING... with a red stop button — it LISTENS but NEVER ANSWERS. His Groq key tested "Connected" in the app and chat works, so the keys are fine. The bug is in the voice path itself:

`AudioRecorder` (`voice/AudioRecorder.kt`) delivers mic chunks to its `onAudioChunk` callback ONLY when `isLiveForwarding` is true. That flag defaults false and is set true ONLY by `flushPreRoll()` / `enableLiveForwarding(true)`. The ONLY `flushPreRoll()` call in the whole app is in `service/MicService.kt:614` — the Gemini Live path's onSetupComplete. `voice/ServerVoiceSession.kt` NEVER calls `flushPreRoll()` or `enableLiveForwarding(true)`: its `initVadAndRecorder()` just calls `audioRecorder.startRecording()`. Consequence: ZERO mic audio ever reaches the Worker WebSocket, the client VAD never processes a frame (`vad.processFrame` lives inside the dead `onAudioChunk` callback), no endpoint hints are ever sent, the Worker never sees speech, never finalizes an utterance, never replies — UI stuck on LISTENING forever while mic amplitude (fired unconditionally) animates the orb. That is exactly Salman's symptom.

## ROOT-CAUSE FIX (do this first, verify it by reading the code after)

In `ServerVoiceSession.kt`, enable live forwarding when the server session starts: call `audioRecorder.enableLiveForwarding(true)` right after `startRecording()` succeeds in `initVadAndRecorder()` (the server path has no setupComplete/pre-roll concept, so plain `enableLiveForwarding(true)` is the correct call here — do NOT wire it to the Live path's `flushPreRoll()`). Also make sure forwarding is disabled again on stop (the existing `stopRecording()` already resets the flag — verify, do not duplicate). After the change, re-read `ServerVoiceSession.kt` and `AudioRecorder.kt` and confirm in your final summary that the server path now sets the flag.

## FIX 1 — VOICE NO-REPLY HARDENING (surface the truth on screen, never silent Listening)

1. Show which voice path is active (Server / Live / SpeechRecognizer) and the real stage/error in plain words when a stage fails — e.g. "Groq key missing — add it in Settings", "Server did not answer", "Could not hear you clearly". Use the existing short-Toast mechanism (`MicService.errorMessage` → `HomeScreen` Toast on `LiveState.ERROR`, and `MicService` Toasts). NEVER add a red error banner on Home, and NEVER add a permanent diagnostics card/text on Home (round 6 removed it on Salman's order — keep it removed).
2. Add a watchdog in `MicService` (server path): if the session is LISTENING but zero audio chunks were sent (`_audioChunksSent` stays 0 — the `onAudioChunkSent` callback already exists and increments it), OR no partial/transcript arrives within a few seconds after speech is detected (VAD speech start / amplitude clearly above silence), surface a plain-words reason via the existing Toast/error channel instead of sitting on Listening forever. The watchdog must reset per turn and must NOT fire while audio is actually flowing or while a reply is playing. Keep it simple and robust — a small coroutine/job in `MicService`, cancelled on stop.
3. Never silently fall back to Live: the existing `onRequestLiveFallback` Toast ("Server voice unavailable — switching to Live voice") stays, and any Live failure after that must also surface visibly (Toast + `LiveState.ERROR`) instead of landing in Live's known server silence with no message. If server voice errors, say so plainly first.

## FIX 2 — HOME UI (follow literally; touch ONLY these two files for Home)

- Under the orb there must be exactly ONE status line — the small one `ui/screens/HomeScreen.kt` already renders (~lines 271–280: "Listening..." / "Speaking..." / "Connecting..."). DELETE the duplication inside `ui/components/AnimatedOrb.kt` (~lines 274–299): the "AI ASSISTANT" label `Text` and the big state `Text` (`stateLabel`: "LISTENING…"/"SPEAKING…"/etc.). The orb itself (the drawn sphere/Canvas) stays untouched — delete only those two `Text` blocks and their spacers, nothing else in the file.
- In `HomeScreen.kt`, change the pre-session idle line from "Tap the mic to talk" to "Tap the voice button to start". KEEP the three cards (Weather / Date / Mood). Remove any other extra Home options/text you find that duplicates status. Do NOT touch anything else on Home. No full UI polish pass in this build.

## FIX 3 — SETUP KEYS (three fields; app not enterable without BOTH required keys)

Setup page step 1 (`ui/screens/SetupScreen.kt`) must have THREE API fields:
1. Gemini API key — REQUIRED.
2. Groq API key — REQUIRED (already exists; keep its "Test Groq Key" button and `gsk_` plausibility check).
3. Deepgram API key — OPTIONAL (may be left blank; addable later from Settings).

Rules:
- The app must NOT be enterable without BOTH required keys validly entered. Gate the step-1 "Test & Save" / continue flow on BOTH keys: Gemini validation as today (one quick check, ~12s cap, transient 503/429 = save + enter with amber notice; only real 400/401/403 hard-blocks) AND Groq validation (same fail-open rule already used by `validateGroqKey` — transient = save + enter, only real invalid blocks). If either required key is missing/invalid, stay on step 1 with a plain-words message naming which key. Do NOT weaken the Gemini 503 fail-open rule.
- Deepgram: add a `SecurePreferences` accessor pair exactly like the Groq one (`getDeepgramApiKey` / `setDeepgramApiKey` / `clearDeepgramApiKey`, key `deepgram_api_key`, encrypted on-device, never logged). The Setup field is a plain optional password-style field with a "Get Free Key" link to the Deepgram console signup (https://console.deepgram.com/signup) and a short "(optional)" label — NO validation call is required for it in Setup; if filled, save it encrypted; if blank, save nothing and proceed. Also add an optional Deepgram card in Settings → Voice Engine (`ui/screens/settings/VoiceEngineScreen.kt`, next to the existing Server Voice (Groq) card) showing saved/not-saved state with save/clear.
- The Deepgram key is NOT used by the voice path in this build. Do NOT wire it into `ServerVoiceSession`, `ServerVoiceClient`, the Worker, or any STT/TTS call.

## VOICE BEHAVIOUR (unchanged — keep as specified)

Fast first; spoken "এক মিনিট, আমি খুঁজে বলছি" acknowledgement when web search is needed, then the final spoken answer; barge-in; Bangla default, one language only. Chat web search stays INBUILT with the ON/OFF toggle above the chat input — unchanged.

## VERIFY BEFORE YOU FINISH

1. `grep` the tree for `getmaya` — must be zero matches.
2. Re-read the changed hunks: `ServerVoiceSession.kt` (forwarding enabled on start), `AnimatedOrb.kt` (no "AI ASSISTANT", no big state Text), `HomeScreen.kt` (idle text "Tap the voice button to start", one status line only), `SetupScreen.kt` + `SecurePreferences.kt` + `VoiceEngineScreen.kt` (three fields, Deepgram optional + stored encrypted + Settings card, gate on both required keys).
3. Compile-check: run `./gradlew :app:compileDebugKotlin` if the toolchain is present on this machine; if it is not available, say so plainly and do NOT claim a compile pass. Do NOT push, do NOT build a release, do NOT deploy the Worker (no Worker change is expected for this round — if you believe one is needed for FIX 1, say exactly what and why instead of changing it).
4. Final summary must list, per fix (ROOT CAUSE, FIX 1, FIX 2, FIX 3): every file changed + what changed in it, in one line each. End with what still needs Salman's phone test. Do NOT claim voice works — only he can verify that on his phone.
