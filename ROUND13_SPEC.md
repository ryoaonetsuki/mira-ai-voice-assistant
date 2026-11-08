# Mira AI ROUND-13 SPEC — Fix round-12 regressions + voice timeout crash

Date: 2026-11-07 ~06:00 +06. Repo: `ryoaonetsuki/mira-ai-voice-assistant`,
package `io.github.ryoaonetsuki.mira`, work dir `~/workspace/mira-final/`.
Round-12: run 42270223646 GREEN, APK ~43 MB, md5 93bf22877314b7637629f4b72731701c.

## 0. DECISIVE EVIDENCE (phone test 2026-11-07 ~05:53 + diagnostics ~05:56)

Salman's phone shows THREE bugs (see §1–§3). His Voice Diagnostics screenshots add
the decisive fact:

- "Live Voice Capability: Not Supported — This key cannot use Live voice —
  create a new key in Google AI Studio"
- Diagnostics: Last Session Stage IDLE, WebSocket Not connected, Setup Duration
  N/A, Chunks Sent 0, Chunks Received 0, Last Error None,
  Key Live Capability: "This key cannot use Live voice",
  Model models/gemini-3.8-live (Direct Native WebSocket).
- Alternative voice (Server Voice) toggle OFF; Server Voice (Groq) card says
  disabled; Deepgram Not Saved.

So the app BELIEVES his key cannot use Live, yet tapping voice still tries and
hangs on "Connecting..." until timeout, then the app force-closes. BUT the
capability verdict itself is suspect — see §0.1, PROVEN false negative.

### 0.1 PROVEN: the capability check is a FALSE NEGATIVE (VM test 2026-11-07 ~06:00)

Tested the app's EXACT check logic against the STORED key (`custom.gemini`),
which is PROVEN Live-capable by the VM WS live test 2026-11-06 (header auth,
setupComplete ~1.1 s, Bangla audio reply ~2.6 s):

- App's exact call `GET /v1beta/models` with NO parameters: first page = **50
  models, `nextPageToken` present, `gemini-3.8-live` NOT on page 1** → app
  logic says "Not Supported". WRONG.
- Same key, `GET /v1beta/models?pageSize=100`: **61 models, no next page,
  `models/gemini-3.8-live` IS listed with `bidiGenerateContent`** → capable.
- Full pagination: 61 models total; 9 models list `bidiGenerateContent`,
  including `models/gemini-3.8-live` and `models/gemini-3.8-live-extended-thinking`.

Root cause: `GeminiRestClient.checkLiveCapability()` calls the models endpoint
with no `pageSize` and reads ONLY the first page (default page = 50 models),
never following `nextPageToken`. `gemini-3.8-live` sorts beyond the first 50,
so the check wrongly reports "cannot use Live" for a key that CAN.
Salman's "Not Supported" is therefore UNRELIABLE — his key may be perfectly
fine. Do NOT tell him to create a new key until the FIXED check has run
against HIS key on his phone.

## 1. BUG 0 — FIX THE CAPABILITY CHECK (do this first)

File: `app/src/main/java/io/github/ryoaonetsuki/mira/network/GeminiRestClient.kt`
(`checkLiveCapability`, ~line 605).

1. Request `GET https://generativelanguage.googleapis.com/v1beta/models?pageSize=100`
   with the `x-goog-api-key` header, and follow `nextPageToken` until exhausted
   (cap at ~5 pages for safety). Only after the FULL list is fetched may the
   check conclude the model is absent.
2. Match: model `name` == `models/gemini-3.8-live` (also accept bare
   `gemini-3.8-live`) AND `supportedGenerationMethods` contains
   `bidiGenerateContent`.
3. THREE outcomes, never two: (a) Available — "Live voice available on this
   key"; (b) Not supported — ONLY after a successful full-list fetch that
   genuinely lacks the model — "This key cannot use Live voice — create a new
   key in Google AI Studio"; (c) Unknown — transient failure (HTTP 429/503,
   network error, timeout, any exception): "Could not check Live capability —
   check internet and tap to retry". Outcome (c) must NEVER be worded as
   "cannot use Live".
4. The same three-state logic applies in SetupScreen (key validation) and in
   VoiceEngineScreen (auto-run on open + "Check Key Capability" button).
   Keep the result in `LiveVoiceDiagnostics.liveCapabilityResult` so the
   diagnostics card always shows the latest verdict.
5. `LiveCapabilityOutcome` needs an `UNKNOWN` state (or equivalent) — add it;
   update every caller so "unknown" is not treated as "not supported".

## 2. BUG 2 — VOICE MUST NEVER HANG ON "CONNECTING..." (fast-fail + real reason)

Files: `MicService.kt` (`startLiveVoiceSession`, `startPrimaryGeminiLiveSession`),
`GeminiLiveClient.kt`, `VoiceEngineScreen.kt`, `SetupScreen.kt`.

1. Gate the voice button on the (fixed) capability check: before starting a
   Live session, run `checkLiveCapability` with the saved key (reuse a cached
   result if it is < 10 minutes old and says Available; otherwise run fresh,
   bounded so the button never waits more than ~10 s).
   - Definitively Not Supported → do NOT start the WS at all. Immediately show
     a plain message: "This key cannot use Live voice — create a new key in
     Google AI Studio", stay on Home idle, and show in-app guidance with exact
     steps: AI Studio → Get API key → Create API key in a NEW project → paste
     it in Mira (Settings → Personal Profile, or Setup) → open Settings →
     Voice → Check again. Provide a button that opens the key entry / Voice
     settings. NO 45 s "Connecting...", NO timeout, NO crash.
   - Unknown → do NOT block: proceed to the Live attempt, but keep the stage
     accurate (see §2.3).
   - Available → proceed normally.
2. Chat keeps working with the old key — only Live needs the new key. Do not
   touch chat, do not invalidate the saved key, do not force re-setup.
3. If the Live attempt itself fails, the error must name the STAGE reached,
   never a bare "timeout":
   - WS connect timeout (20 s): "Could not reach Google's voice server —
     check your internet and tap to retry".
   - Setup timeout (45 s, WS open but no setupComplete): "Google accepted the
     connection but never confirmed setup — your key may not have Live access.
     Check Settings → Voice → Live Voice Capability, or create a new key in
     Google AI Studio".
   - First-audio stall (30 s): keep the existing "Server did not answer" text.
   - Server error frame / WS close with code: keep the existing plain texts,
     including the HTTP/WS code in the diagnostics (not necessarily in the toast).
4. Diagnostics (`LiveVoiceDiagnostics`, shown at Settings → Voice → Voice
   diagnostics) must stay accurate on EVERY attempt: stage reached, WS code,
   setup ms, chunks sent/received, last error. After a failed attempt the card
   must show the real stage/error — never "Stage IDLE / Last Error None" after
   a failure. (Note: `reset()` in `startSession` wipes the previous error —
   that is fine only if the new attempt's outcome is then recorded; make sure
   `handleError` always records stage ERROR + last error + WS code.)
5. SECONDARY BUG (fix in this round): `MicService.startLiveVoiceSession`
   passes `resolver.anonKey` (the SUPABASE anon JWT) as the Gemini API key
   when no user key is saved (`KeySource.ServerKey` branch). A Supabase JWT
   can never work as a Gemini key — that path can only fail. Remove it: with
   no user key, show "Gemini key missing — add it in Settings" and stay idle.
   BYOK only (server-side key storage NOT wanted).
6. Stale resumption handle: if setup fails (error frame 400/403 or setup
   timeout), clear the stored resumption handle so a stale handle cannot
   poison the next attempt. Keep reusing it on success as today.

## 3. BUG 1 — HOME CARDS CLIPPED INTO THE HEADER (regression from round-12 name removal)

Files: `ui/navigation/NavGraph.kt`, `ui/screens/HomeScreen.kt`.
Phone screenshot: the three cards (Weather / Date / Mood) are pushed up under
the "Mira AI" header — only their bottom lines ("Partly Cloudy", "Today",
"All good") are visible; icons and top values (20°C, Fri 6 Nov, Upbeat) are
cut off/hidden.

Root cause (found in code): in `NavGraph.kt` the Home branch builds a
`Scaffold(topBar = { AppTopBar(...) }) { padding -> HomeScreen(...) }` and
IGNORES the Scaffold `padding` (comment even says "HomeScreen fills the full
scaffold area including padding"). `HomeScreen` only applies
`.statusBarsPadding()`, which covers the status bar but NOT the TopAppBar
height, so the cards start underneath the top bar and the bar paints over
them. Removing the user-name row in round-12 removed the spacing that used to
push the cards down — hence the regression.

Fix:
1. Pass the Scaffold content padding into `HomeScreen` (add a `Modifier`
   parameter; NavGraph passes `Modifier.padding(padding)`). Remove the double
   counting: `HomeScreen` must NOT add `statusBarsPadding()` on top of the
   Scaffold padding.
2. The three cards must be FULLY visible below the header with correct
   spacing, exactly as in v1.0.0. Removing the name must not shift/clip
   anything. The scrollable column must start below the header, never under it.
3. Verify the layout with a LONG user name set in Settings — nothing may
   overflow or clip. The user name is NEVER rendered on Home (keep that rule);
   the check is that a long name stored in Settings cannot break Home layout
   through any path (drawer/header included).
4. No other Home changes: exactly ONE status line under the orb, "Tap the
   voice button to start" when idle, 3 cards kept, chips above bottom nav.

## 4. BUG 3 — APP FORCE-CLOSES AFTER THE TIMEOUT (most serious)

Files: `service/MicService.kt`, `voice/GeminiLiveClient.kt`,
`voice/AudioRecorder.kt`, `voice/AudioPlayer.kt`.

Symptom: after the voice timeout the app closes by itself.

Prime suspect (found in code — verify and fix): `MicService`'s
`LiveEventsListener.onError` (line ~493) and `onStalled` (line ~483) call
`Toast.makeText(applicationContext, ...).show()`. These callbacks are invoked
from `GeminiLiveClient` threads that have NO Looper: the OkHttp WebSocket
dispatcher threads (`onFailure` / `onClosed`) and the `clientScope`
(`Dispatchers.Default`) watchdog coroutines (WS-connect 20 s timeout,
setupComplete 45 s timeout, 30 s stall watchdog). Creating a `Toast` on a
thread without a `Looper` throws
`RuntimeException: Can't create handler inside thread that has not called
Looper.prepare()`. `handleError()` calls `listener.onError(message)` with NO
try/catch (unlike `transitionTo`, which wraps `onStateChanged`), so the
exception escapes into the OkHttp callback / timeout coroutine as an uncaught
exception and kills the app process (the foreground service runs in the app
process). That is exactly "timeout, then force-close": the success path never
touches this Toast, the error path always does.

Fix (all of it):
1. EVERY Toast in `MicService` (and anywhere else reachable from a voice
   callback) must run on the main thread — via
   `Handler(Looper.getMainLooper()).post { ... }` or
   `serviceScope.launch(Dispatchers.Main) { ... }`. Add one private
   `showToastOnMainThread(msg)` helper in `MicService` and use it everywhere.
   Also fix the `Toast` in the alternative-voice branch (already on
   `Dispatchers.Main` scope — confirm, do not regress).
2. Defence in depth in `GeminiLiveClient`: wrap EVERY `listener.*` invocation
   (`onError`, `onClosed`, `onAudioData`, `onTranscript`, `onStateChanged`
   already wrapped, `onStalled`, `onSetupComplete`, `onSessionResumptionUpdate`)
   in try/catch that logs and swallows, so a listener exception can NEVER
   propagate into an OkHttp callback or a watchdog coroutine and kill the app.
3. Audit EVERY timeout / reconnect / error path for exceptions and for UI
   updates after teardown: WS connect 20 s, setupComplete 45 s, first-audio
   stall 30 s, 8-minute give-up, max 2 auto-reconnects, `onFailure`,
   `onClosed`, `goAway`, server error frame. Each path must end in a clean
   state, never throw, never touch a dead session/coroutine scope.
4. On terminal error the session must stop CLEANLY: cancel all watchdogs,
   close the WS, stop the recorder, flush/stop the player, null out
   `liveClient` (today `onError` leaves a dead client referenced), clear
   `_micAmplitude`, stop the foreground notification (never leave a "Mira AI
   is listening" notification after a failure), return Home to idle
   ("Tap the voice button to start", orb idle), show the error ONCE, and leave
   the voice button tappable for retry. A retry tap after an error must work
   (today it is allowed from ERROR — keep that, and make sure
   `stopLiveVoiceSessionInternal()` on a dead/errored client cannot throw).
5. Fix the stall-watchdog double-report: today `startStallWatchdog` calls
   `handleError(stallMsg)` (which fires `onError`) and THEN
   `listener.onStalled()` (which fires a second error path + second Toast).
   Make the stall path report exactly once.
6. Make `onError` idempotent: a second error arriving while already in ERROR
   / after teardown must be a no-op, not a second teardown + second Toast.
7. Keep `AudioRecorder.stopRecording()` / `AudioPlayer.release()` guards —
   they are already try/catch-wrapped; do not remove the guards. Verify they
   are safe when called from foreign (OkHttp / watchdog) threads.

## 5. SERVER VOICE FALLBACK (manual only — must actually work when enabled)

Keep the "Alternative voice (Server Voice)" toggle OFF by default, never
auto-selected, never silently routed to. BUT a user whose key cannot use Live
must be able to enable it manually as a working fallback — it is currently
the only voice path that could work for him if his key lacks Live.

1. Audit `voice/ServerVoiceSession.kt` end to end for the same crash patterns
   (§4): no Toast off the main thread (its scope is `Dispatchers.Main` —
   verify), no uncaught exceptions on its error/timeout paths, clean teardown.
2. Verify the mic-forwarding path: `ServerVoiceSession` line ~121 sets
   `audioRecorder?.enableLiveForwarding(true)` — confirm the recorder is
   actually created/started in the server-voice path (in round-10 the
   live-forwarding flag was only set on the Live path) and that mic chunks
   really reach the Worker. Check the Worker URL it uses is the live one
   (`mira-server-voice.salman-dev.workers.dev` — the worker answers WS
   upgrade requests; a plain GET returns "WebSocket upgrade required", which
   is expected). Fix any gap found; do not invent a new server.
3. When alternative voice is manually enabled and its session fails, show a
   plain server-voice error and return Home to idle — do NOT silently switch
   back to Live (the user enabled server voice precisely because Live does
   not work for them; auto-switching would loop back into the same failure).
   Review `onRequestLiveFallback` in `MicService` with this rule in mind.

## 6. CONSTRAINTS (keep from rounds 1–12)

- Gemini Native Live is PRIMARY; Groq NOT required (optional, blank fine);
  Whisper / Server Voice NOT required; server voice only behind the
  OFF-by-default experimental toggle, never auto-selected, never silently
  routed; no separate Gemini TTS for primary voice; native speech-to-speech
  only; fast Setup validation (12 s cap, transient 503/429 = save + enter with
  amber notice, only real 400/401/403 blocks); chat toggles Web Search / Deep
  Thinking inbuilt ON/OFF; file upload; date injection from device clock;
  Supabase backend licence OFF; Bangla default + one-language-only; chat logo
  avatars; File Management permission REQUIRED in setup; Home = exactly ONE
  status line under the orb, "Tap the voice button to start" when idle, 3
  cards kept and fully visible; voice search acknowledgement spoken aloud
  "এক মিনিট, আমি খুঁজে বলছি"; barge-in; no wake word; Material icons; user
  name NEVER rendered on Home; keys never logged anywhere (no key material in
  logs, toasts, diagnostics or error strings); NEVER call relay.getmaya.online,
  api.getmaya.cloud, or any Maya/Hunter host.
- No full UI polish pass — only the bugs in §1–§5.
- Bump `versionCode` (1 → 2) and `versionName` ("1.0.0" → "1.0.1") in
  `app/build.gradle.kts` so this APK is distinguishable from round-12.
- Do NOT push, do NOT run GitHub Actions, do NOT download the APK — the
  orchestrator does that after you finish. Only edit code in
  `~/workspace/mira-final/`.

## 7. DONE WHEN

- §1: capability check paginates fully, three outcomes, unknown ≠ not supported.
- §2: unsupported key → instant plain message + guidance + key-entry button,
  no Connecting hang; stage-named errors; diagnostics accurate after failure;
  anon-JWT-as-Gemini-key branch removed.
- §3: cards fully visible below the header; long-name check done in code
  (name never on Home).
- §4: no Toast off the main thread anywhere on the voice path; every listener
  call try/catch-wrapped; every timeout/reconnect/error path audited; clean
  teardown + idle Home + retry works after an error.
- §5: manual server-voice path audited and working (mic actually forwarded,
  errors plain, no silent switch back to Live).
- Code compiles cleanly under the repo's GitHub Actions workflow
  (`.github/workflows/build-apk.yml`) — re-read your diffs for syntax,
  imports, and unresolved references before finishing.
