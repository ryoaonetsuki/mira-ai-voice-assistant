# Mira AI — Round 6 Spec (2026-11-06, URGENT REGRESSION + Salman correction)

Repo: https://github.com/ryoaonetsuki/mira-ai-voice-assistant — package io.github.ryoaonetsuki.mira.
Local tree: ~/workspace/mira-final/. Build gate: GitHub Actions workflow must be GREEN; debug APK aapt2-verified.

## THE BUG (Salman's test of the round-5 build, run 42196265189)
Setup "Test & Save" spins forever: "Validating key with Google... (retrying if Google is busy)"
with a loading spinner, nothing ever comes back. He cannot enter the app at all.
The FIRST versions were instant on entering the key and chat worked — only voice was broken.

## ROOT CAUSE (verified from the code, not guessed)
`network/GeminiRestClient.kt` — shared OkHttp client: connectTimeout 15s,
readTimeout 60s, writeTimeout 15s, NO callTimeout. `validateApiKeyAndChat()` had
NO overall timeout, and SetupScreen launched it in a plain `scope.launch` with
no timeout. Round 5 stacked retries on top of that:
- Step 1 models-list: 3 attempts x up to ~75s per call + delays = ~229s worst case.
- Step 2 ladder: 3 models x `executeGenerateContent` (up to 4 attempts x ~75s
  + 17s backoff) = ~317s per model, ~951s total.
- Combined worst case ~20 minutes of spinner. When Google is 503-overloaded or
  slow, every call sits until the 60s read timeout, retries, moves to the next
  model — the user sees "forever".

## FIX (Salman's correction applied)
1. **Validation restored to the v1 fast single check** (`GeminiRestClient.kt`):
   ONE generateContent call on `gemini-3.8-flash` only — no models-list
   pre-check (removed), no ladder, no retries, no backoff. New dedicated
   `validationClient`: connect 5s / read 8s / write 5s / callTimeout 8s.
   Whole check wrapped in `withTimeoutOrNull(12_000L)`. Worst case ~12s.
   Timeout / 503 / 429 / 5xx / network error on a plausible key (AQ./AIza or
   reasonable format) = `TransientServerBusy` → key SAVED with the amber
   "Google's servers are busy right now — key saved, try again in a bit"
   notice, user enters the app. Only real auth errors (400/401/403) hard-block
   as `InvalidKey`. `executeGenerateContentSingleAttempt` gained an
   `httpClient: OkHttpClient = client` parameter so validation uses the
   short-timeout client; chat/voice REST paths keep the shared client and
   their existing retry behaviour UNCHANGED.
2. **Safety net in the UI logic (no page redesign):** SetupScreen and
   PersonalSettingsScreen wrap the validation call in `withTimeoutOrNull(25s)`;
   a null result is treated as `TransientServerBusy` (save + enter). The
   "Validating..." status text drops the stale "(retrying if Google is busy)"
   clause (no retries exist any more). Layouts untouched.
3. **Home diagnostics card REMOVED (Salman's order):** the round-4
   `VoiceDiagnosticsCard` composable and its call site are deleted from
   `HomeScreen.kt`, plus the six card-only state collections. Home is back to
   the v1.0.0 layout (orb + small status line + chips above bottom nav).
   Diagnostics still go to logcat only: MicService logs every
   `_lastVoiceDiagnostic` update (Log.i/w/e at lines ~181/185, ~490/491,
   ~559/569, ~613/615) — nothing on screen.
4. **Chat toggles VERIFIED (not redesigned):** Web Search + Deep Thinking
   FilterChips sit above the chat input (`ChatScreen.kt` ~681-737), bound to
   `webSearchEnabledFlow` (default true) / `deepThinkingEnabledFlow`
   (default false) in UserPreferences. The send path re-reads both prefs at
   send time (lines ~917-918), recomputes `shouldGround` (~972), and passes
   `enableGrounding`/`enableThinking` to the streaming call and both
   non-streaming fallbacks. The hardcoded initialisers (`webSearchOn = true`,
   `deepThinkingOn = false`) that were overwritten before any use are now
   initialised from the live chip state. He never saw the toggles because the
   round-5 build never let him past Setup — they were present in code all along.

## UNTOUCHED (rounds 1-5 preserved)
Voice fallback + Live client, file upload, Bangla default + one-language
replies, file-permission gate, date injection, Supabase backend (licence OFF),
clean copy, no PC connect, Home/Setup layouts as v1.0.0.

## STILL NEEDS SALMAN'S PHONE TEST
- Setup "Test & Save" with his AQ. key: must return in seconds (valid → step 2;
  busy Google → amber notice + step 2), never spin.
- Voice (Live + fallback) — still the real open problem; code-verified only,
  never phone-proven. Home UI must stay exactly as v1 while testing voice.
- Chat toggles: flip Web Search / Deep Thinking off and on and confirm replies
  change accordingly (search grounding on/off, short vs deep answers).
- Supabase project ref + admin panel URL still owed from earlier rounds.
