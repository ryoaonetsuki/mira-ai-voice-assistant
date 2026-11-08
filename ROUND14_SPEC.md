# Mira AI ROUND-14 SPEC — FINAL ROOT CAUSE DEBUGGING & COMPLETE VOICE REPAIR

Date: 2026-11-07. Supersedes round-13 (stopped, superseded by this spec).
Repo: ryoaonetsuki/mira-ai-voice-assistant, package io.github.ryoaonetsuki.mira.
Work dir: ~/workspace/mira-final/. Build ONLY with agy (Gemini 3.8 Flash High does
plan + implementation; Claude Opus/Sonnet are quota-blocked ~120h). Push with
tools/push.py, build via GitHub Actions, APK to ~/workspace/user/files/mira-ai-debug.apk.

## 0. ROOT CAUSES — PROVEN BEFORE ANY CODE WAS WRITTEN (2026-11-07, this VM)

### 0.1 ROOT CAUSE #1 — capability check can NEVER find the Live model (DEFINITIVE, live-proven)
`GeminiRestClient.checkLiveCapability` calls `GET /v1beta/models` with NO `pageSize`
and NO pagination, then reports "This key cannot use Live voice — create a new key
in Google AI Studio" when `models/gemini-3.8-live` is absent from the response.

Live experiment with the stored key (surrogate exchange, key never visible), 2026-11-07:
- Exact app call (`GET /v1beta/models`, no pageSize): **50 models returned,
  `nextPageToken` present, ZERO models with `bidiGenerateContent`,
  `models/gemini-3.8-live` ABSENT.**
- Page 2 (same call + `pageToken`): 11 models, `models/gemini-3.8-live` PRESENT
  with `bidiGenerateContent` (alongside 3.5-transcribe-live, 2.5-flash-native-audio
  variants, 3.1-flash-live-preview, 3.8-live-extended-thinking, robotics-er-2,
  3.5-live-translate-preview).
- `GET /v1beta/models?pageSize=100`: 61 models, `models/gemini-3.8-live` present,
  no nextPageToken.

Google's default pageSize for models.list is 50 and the Live models sort onto page 2.
The check therefore returns "Not Supported" for EVERY key, including a brand-new key.
**The key was never the cause. Do NOT ask Salman for another key. Do NOT treat the
models list as proof of Live access either way — see §1.**

### 0.2 ROOT CAUSE #2 — force-close on the timeout/error path (code-proven, prime suspect)
`MicService` listener callbacks `onError` (~line 493) and `onStalled` (~line 483) call
`Toast.makeText(applicationContext, ...).show()`. These callbacks are invoked from
`GeminiLiveClient` on OkHttp WebSocket callback threads and on its
`Dispatchers.Default` watchdog coroutines (connect 20s / setup 45s timeout jobs) —
threads with NO Looper. `Toast.makeText` creates a `Handler` on the calling thread;
with no Looper it throws `RuntimeException: Can't create handler inside thread that
has not called Looper.prepare()` → process dies. That is exactly the observed
sequence: "Connecting..." for a long time → timeout fires → force-close. The error
state never gets a chance to display because the process dies inside the error callback.
(The `Toast` in the alternative-voice branch ~line 216 runs on `serviceScope`
(Dispatchers.Main) and is safe; the HomeScreen `LaunchedEffect` Toast runs on Main
and is safe.)

### 0.3 ROOT CAUSE #3 — Home cards clipped under the top bar (code-proven)
`NavGraph.kt` Home branch: the `Scaffold { padding -> ... }` content lambda IGNORES
`padding` — `HomeScreen(...)` is called with no `Modifier.padding(padding)`.
`HomeScreen`'s own Column only applies `.statusBarsPadding()`, so its content (the
Weather/Date/Mood cards row at the top of the scrollable column) draws UNDERNEATH
the `AppTopBar` — only the cards' bottom lines are visible. The round-12 name
removal exposed this (the name/greeting block previously pushed the cards down
below the bar); the padding bug itself predates it.

### 0.4 ROOT CAUSE #4 — Server Voice "Chat service unavailable" hides the real error (code-proven)
`cloudflare-relay/server-voice-worker.js:476`: whenever BOTH the Groq streaming chat
AND the Gemini streaming ladder (`gemini-3.8-flash -> 3.6-flash -> 3.5-flash-lite`,
`:streamGenerateContent?alt=sse&key=`) fail with a NON-auth error, the worker sends
`{ type: "error", code: "gemini_failed", message: "Chat service unavailable — please
try again" }` and DISCARDS both underlying errors. The real downstream cause (Groq
vs Gemini, status, message) is unknowable from the app side. Worker live logs were
NOT accessible from this VM (needs `wrangler tail` / Cloudflare dashboard) — do not
claim the downstream cause is fixed without those logs.

### 0.5 What is NOT the cause (reconciled evidence)
- The stored key WORKS with Live end-to-end (round-12 VM test: header auth
  `x-goog-api-key` → WS 101 ~0.7s → setupComplete ~1.1s → Bangla audio reply ~2.6s;
  scripts in ~/workspace/rea-analyses/mira/live-test-2026-11-06/). The shipped
  `GeminiLiveClient` already uses header auth (verified in source) and the proven
  setup shape (`responseModalities` ONLY inside `generationConfig`, never top-level).
- Chat works with Salman's key, so the key is valid for the Gemini API generally.
- `ApiKeyResolver.resolveKey()` correctly prefers the user's own key
  (`SecurePreferences.getGeminiApiKey()`); no key mismatch between the capability
  check and voice was found in source. The capability check reads the same stored key.
- Free tier: `gemini-3.8-live` input+output are FREE OF CHARGE on the free tier
  (paid: $0.005/min audio-in, $0.018/min audio-out); GA since 15 Sep 2026; free-tier
  rate limits are shown only in AI Studio. No payment is required. No paid
  endpoint may be silently used.

## 1. FIX THE CAPABILITY CHECK (first priority)
File: `network/GeminiRestClient.kt` (`checkLiveCapability`), callers
`ui/screens/settings/VoiceEngineScreen.kt` (+ `SetupScreen.kt` if it calls it).
- `GET https://generativelanguage.googleapis.com/v1beta/models?pageSize=100`
  with the `x-goog-api-key` header; FOLLOW `nextPageToken` until the model is found
  or pages are exhausted (cap ~5 pages). Match the configured Live model
  (`models/gemini-3.8-live` by default; respect the user's selected Live model) AND
  require `bidiGenerateContent` in `supportedGenerationMethods`.
- Classify and report distinctly; NEVER blanket "Not Supported":
  400/401/403 → auth/key problem (name the HTTP status, e.g. "Google rejected the
  key (HTTP 401) — check the key in Settings"); 429/503/500/502/504 → transient
  ("Google's servers are busy — try again"); network/IO failure → network message;
  malformed/unexpected response → "check failed, try again" (never "Not Supported").
  ONLY when the full paginated list was fetched successfully AND the model is
  genuinely absent → "models/gemini-3.8-live is not listed for this key/project".
- Label the result honestly: the list proves the model is LISTED for the key, not
  that Live audio works. The authoritative proof is the Live diagnostic (§2).
- Re-check on every explicit "Check Key Capability" tap and on screen entry; never
  persist/cache a negative result across key changes. Keep the 8s validation-client
  timeout per page call; bound total check time (~30s max).
- Add unit tests for the pagination logic (page 1 without the model + page 2 with it
  → capable; model absent after all pages → not capable; 401 → auth class;
  503 → transient class). Tests must be executable in CI (see §9).

## 2. MINIMAL ISOLATED GEMINI LIVE DIAGNOSTIC TEST (build it in the app)
- New standalone diagnostic runner (e.g. `voice/LiveDiagnosticRunner.kt`) using
  ONLY the configured Gemini key + the existing proven Live client path. Bypasses
  Server Voice, Groq, Whisper, Deepgram, mic, fallbacks.
- Sequence, each stage recorded with timestamp + result, shown stage-by-stage in the
  app (Settings → Voice → Voice diagnostics, new "Run Live diagnostic" button):
  1. Key present (never display/log the key) → 2. WS connect to the official
  BidiGenerateContent endpoint (report HTTP code / failure reason) → 3. Setup sent
  → 4. `setupComplete` received (report ms) → 5. Send a controlled TEXT turn
  (clientContent: user asks in Bangla for a one-word reply) → 6. First server
  response received → 7. First native AUDIO chunk received (bytes) + played if
  possible → 8. `turnComplete`. On failure, stop at the exact failing stage and
  show the exact error (HTTP code / WS close code / server error code+message).
- NEVER report success merely because the WebSocket opened. A run that fails at
  setup must say so. The diagnostic must be runnable repeatedly and must not start
  the mic or disturb an active session.
- Keep `LiveVoiceDiagnostics` as the store; the diagnostic writes stage-by-stage
  results there (add fields as needed: `diagnosticStages: List<StageResult>`,
  `diagnosticLastRunAt`, `diagnosticOutcome`).

## 3. VERIFY THE LIVE PROTOCOL AGAINST OFFICIAL DOCS
Check ai.google.dev/gemini-api/docs/live (and /live-api/get-started-websocket,
/api/live) and confirm in the shipped `GeminiLiveClient.kt`: WS URL, header auth,
model resource name `models/gemini-3.8-live`, setup JSON (responseModalities ONLY
inside generationConfig), generationConfig/speechConfig/languageCode, realtimeInput
audio MIME `audio/pcm;rate=16000`, server message parsing (setupComplete,
serverContent.modelTurn inlineData, input/output transcription field names,
interrupted, turnComplete, goAway, sessionResumptionUpdate), setupComplete gating.
- FIX: do NOT send mic audio frames before `setupComplete` (today `sendAudioChunk`
  also accepts CONFIGURING state — gate it to LISTENING/PROCESSING/SPEAKING only;
  keep the pre-roll flush on setupComplete).
- Keep: first message is setup; wait for setupComplete before realtime input;
  continuous streaming including silence frames; `audioStreamEnd: true` on VAD
  speech endpoint / session end (without it the server never replies — proven).
- No outdated examples, no unverified model IDs. Never call relay.getmaya.online,
  api.getmaya.cloud, or any Maya/Hunter host.

## 4. FIX THE CONNECTING-vs-IDLE STATE BUG + THE TIMEOUT CRASH
Files: `service/MicService.kt`, `voice/GeminiLiveClient.kt`, `ui/screens/HomeScreen.kt`.
- Remove EVERY `Toast.makeText(...).show()` from all non-main-thread paths in
  MicService/GeminiLiveClient listener callbacks (onError, onStalled, and any other
  callback reachable from OkHttp threads or Default-dispatcher watchdogs). User-visible
  errors go through `_errorMessage` StateFlow only — HomeScreen already shows them
  with a main-thread Toast in `LaunchedEffect(liveState)`. Any Toast that remains
  must run on the Main thread (serviceScope is Main; or post via
  `Handler(Looper.getMainLooper())`). Grep-verify: no Toast call outside Main.
- Harden every listener callback: wrap callback bodies so an exception inside a
  callback can NEVER kill the process or leave state half-updated (client already
  guards `onStateChanged`; extend the same guard to every callback path and to
  `handleError`/`onFailure`/`onClosed`).
- Guarantee terminal state on every failure path: `_liveState = ERROR`,
  `_voiceStage = ERROR`, recorder stopped, player flushed, client ended, mic
  amplitude 0 — then Home returns to idle ("Tap the voice button to start") with
  the error shown and retry available. The app must NEVER crash on a voice failure.
- Add a MicService-level watchdog: if `_liveState` stays CONNECTING with no client
  stage progress for 45s, force ERROR ("Live voice: Connection timed out — tap to
  retry"), stop cleanly, return Home to idle. No indefinite "Connecting...".
- Keep: duplicate-tap guard, single session, Live first, no silent fallback,
  Server Voice only behind the OFF-by-default toggle.

## 5. ONLY AFTER CONNECTION WORKS — REPAIR AUDIO
Files: `voice/AudioRecorder.kt`, `voice/AudioPlayer.kt`.
- Verify with actual byte buffers, not declared config: input PCM16 mono 16kHz LE
  (20–40ms chunks, continuous incl. silence), output PCM16 mono 24kHz LE.
  AudioRecord init/start/error handling + clean release; AudioTrack streaming,
  bounded queue, clean release; VAD + barge-in (server `interrupted` OR local VAD
  speech-start → flush playback immediately); audio focus; proper cleanup on every
  exit path. Keep the existing pre-roll ring + speaker pre-priming only if they do
  not complicate the new flow. Add/extend unit tests for buffer formats where
  testable without a device (chunk size math, base64 round-trip — already partly
  covered). Do NOT claim physical-device audio verification (no device here).

## 6. INVESTIGATE SERVER VOICE SEPARATELY
Files: `cloudflare-relay/server-voice-worker.js`, `voice/ServerVoiceSession.kt`.
- Worker: when Groq AND Gemini chat both fail, the error frame MUST include the
  underlying detail (provider that failed last, HTTP status, short error message —
  never keys) instead of only "Chat service unavailable — please try again".
  Surface that detail in the app's error display. Log the underlying error in the
  worker (console.error) for `wrangler tail` diagnosis.
- Do NOT invent a downstream cause: the live Worker logs were not accessible from
  this VM. State in the report exactly what is still needed (wrangler tail output
  or Cloudflare dashboard logs for mira-server-voice) to name the Groq-vs-Gemini
  failure. Server Voice stays OPTIONAL, OFF by default, never auto-selected.

## 7. HOME CARDS BUG
Files: `ui/navigation/NavGraph.kt`, `ui/screens/HomeScreen.kt`.
- ROOT CAUSE (§0.3): apply the Scaffold `padding` to the Home content
  (`Modifier.padding(padding)` on the HomeScreen host) so the cards sit below the
  AppTopBar. Keep exactly the v1.0.0 layout: 3 cards fully visible (Weather/Date/
  Mood), orb large and centred, exactly ONE status line under the orb, "Tap the
  voice button to start" when idle, chips just above the bottom nav.
- Verify with a long user name set in Settings that nothing overflows or clips.
  User name is NEVER rendered on Home (confirm no name Text remains on Home).

## 8. FREE-ONLY (keep)
Gemini Native Live PRIMARY; Groq NOT required (optional, blank fine); Whisper /
Server Voice NOT required; no separate Gemini TTS for primary voice; native
speech-to-speech only; fast Setup validation (12s cap, transient 503/429 = save +
enter with amber notice, only real 400/401/403 blocks); chat toggles Web Search /
Deep Thinking inbuilt ON/OFF; file upload; date injection from device clock;
Supabase backend licence OFF; Bangla default + one-language-only; chat logo
avatars; File Management permission REQUIRED in setup; voice search acknowledgement
spoken aloud "এক মিনিট, আমি খুঁজে বলছি"; barge-in; no wake word; Material icons;
keys never logged anywhere (never in logs, files, chat, or reports). No full UI
polish pass in this build. No paid APIs, no mandatory billing, no silent switch
to a paid endpoint.

## 9. TESTING — DO NOT SKIP
- Gradle build + Kotlin compilation via GitHub Actions.
- Unit tests: ADD tests for §1 pagination/classification and §2 diagnostic stage
  recording (mock the HTTP/WS layer, never a real key in tests). The 7 round-12
  tests were NEVER EXECUTED (no JVM/Java on this VM; CI only runs assembleDebug) —
  FIX THE CI: add `./gradlew testDebugUnitTest` to `.github/workflows/build-apk.yml`
  BEFORE assembleDebug so tests actually run in CI, and confirm the CI run shows
  them passing. If any test cannot run in CI, state plainly which and why.
- Live-API test from this VM with the stored key (surrogate exchange, never print
  the key): re-run the models-list check with the FIXED logic (pageSize=100 +
  pagination) and a WS setup test against the real Live endpoint to confirm the
  fixed code path matches the proven wire behaviour. (The subagent driving agy runs
  these VM live tests; agy must not need the key.)
- Distinguish in the report: code-implemented / build-passed / automated-tests-
  passed / live-API-tested / physical-device-tested. No physical device is
  available here — do NOT claim physical-device verification. Do NOT claim voice
  works until Salman phone-tests it.

## 10. DELIVERABLES
- All fixes in the repo, pushed via tools/push.py, GitHub Actions build GREEN,
  APK downloaded to ~/workspace/user/files/mira-ai-debug.apk and verified with
  aapt2 (package, version, size, md5) before reporting.
- Final report: exact original root causes (§0, with the live evidence numbers);
  why the new key did not solve it; whether the capability checker was incorrect
  (yes — §0.1); WS handshake + setup results from the VM live test; files
  modified; build + test results (Actions run number, APK path/size/md5); whether
  real native voice conversation was verified (VM live test only — phone test
  still owed); free-tier availability/limits; remaining external requirement
  (Worker logs for §6; Salman's phone test).
