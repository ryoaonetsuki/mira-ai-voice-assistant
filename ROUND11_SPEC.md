# Mira AI ROUND 11 — SPEC (2026-11-06 ~23:17)

## Verified diagnosis (coordinator, from code + live checks — do NOT re-diagnose the keys)

### BUG 1 — voice still fails: "Chat service unavailable — please try again"
Phone test 2026-11-06 ~23:14–23:16 (round-10 APK): Toast "Server voice unavailable —
switching to Live voice", then Toast "Server voice: Chat service unavailable — please
try again", status stuck on "Connecting...". The watchdog/error surfacing works now;
the voice path itself is still broken.

Evidence chain (all verified 2026-11-06):
- The string "Chat service unavailable — please try again" is sent ONLY by
  `cloudflare-relay/server-voice-worker.js` `streamLlmAndTts()`, and ONLY after BOTH
  the Groq chat call AND the Gemini chat fallback have failed with non-auth errors.
- The flow reaches that point only AFTER a successful final Whisper transcription
  and the client "proceed" handshake. (If Whisper had failed, the error would be
  "Voice transcription unavailable".) So: WebSocket connects, keys arrive, audio
  flows, Whisper transcribes — the LLM stage is the failing stage.
- Groq key tested "Connected" in the app; Gemini chat works in the app; Worker
  health is 200 from this VM; WS without keys is rejected 401 (live-verified).

**PRIMARY ROOT CAUSE (deterministic, fails 100% of turns):** the Worker sends
`model: "gpt-oss-120b"` to `https://api.groq.com/openai/v1/chat/completions`.
Groq's model ID is **`openai/gpt-oss-120b`** (with the `openai/` prefix) —
Groq's own docs: https://console.groq.com/docs/model/openai/gpt-oss-120b and
every Groq code sample use `model="openai/gpt-oss-120b"`. The bare ID returns
HTTP 400 `model_not_found` on every call, so the Worker ALWAYS falls through
to the Gemini fallback.

**SECONDARY ROOT CAUSE (fragility):** the Gemini fallback is ONE attempt with no
retry and no model ladder. Salman's key demonstrably hits transient Gemini 503
("busy server busy" in Setup validation; chat only survives it because the app
retries + walks a model ladder). One 503 in the Worker kills the whole turn and
produces "Chat service unavailable".

Also relevant (from Groq docs / community notes): gpt-oss-120b is a REASONING
model — with default reasoning effort its chain-of-thought can eat the token
budget before any visible answer. Use `reasoning_effort: "low"`.

Live-verified model names (GET /v1beta/models with the stored Gemini key,
2026-11-06): gemini-3.8-flash YES, gemini-3.6-flash YES, gemini-3.5-flash-lite YES,
gemini-3.8-flash-tts YES, gemini-3.8-flash-lite-tts YES, gemini-3.8-live YES.
Whisper model `whisper-large-v3-turbo` is correct as-is. TTS ladder is correct
as-is. Do NOT change any other model name.

### BUG 2 — Home name overflow
`ui/screens/HomeScreen.kt:78,169` — Home renders `"$greeting, $userName"` at 32sp
bold with no maxLines/ellipsis. When Salman enters his full name in Settings,
the name renders on Home clipped mid-word at the top edge and breaks the Home
layout. His words: the name was only added in Settings, why does it appear on
Home at all. `userName` is used nowhere else in UI except the Settings input
field itself (`PersonalSettingsScreen.kt:50`, which is correct).

## Fixes

### FIX A — Worker LLM stage (cloudflare-relay/server-voice-worker.js ONLY)
1. `streamGroqChat`: `model: "openai/gpt-oss-120b"`. Add `reasoning_effort: "low"`.
   Replace `max_tokens: 600` with `max_completion_tokens: 600` (correct param
   for this reasoning model on Groq's OpenAI-compatible endpoint).
2. Retry the Groq chat call ONCE on transient 429/5xx (short ~1s pause). Do NOT
   retry on 400 (bad request) or 401/403 (auth — those keep their existing
   plain-words handling).
3. `streamGeminiChat`: retry transient 429/503 up to 2 times with ~1.5s backoff,
   then walk the model ladder `gemini-3.8-flash` → `gemini-3.6-flash` →
   `gemini-3.5-flash-lite` (each with the same retry) before giving up.
   Keep the existing auth-error handling (401/403 → "API key problem — check
   your keys in Settings").
4. Keep the existing distinct plain-words error messages. Add an error `code`
   field to the error frames sent to the app (e.g. `groq_failed`,
   `gemini_failed`, `transcribe_failed`) for logcat diagnostics — the app shows
   the human message, never the code. Do NOT log keys, transcripts, or audio.
5. Do NOT change: Whisper call, TTS ladder, WS handshake/key validation,
   endpoint detection, barge-in, proceed handshake, CORS, health endpoint.
6. Bump the health `version` to `"2.0.1"`.

### FIX B — Home name (ui/screens/HomeScreen.kt ONLY)
1. Do NOT render the user's name anywhere on the Home screen. DELETE the whole
   greeting Text block (`"$greeting, $userName"`, ~lines 161–178, the Column
   with the 32sp bold Text) from Home. Remove the now-unused `userName` flow
   collection and the `greeting` computation if nothing else uses them.
2. The name stays stored in UserPreferences for personalisation elsewhere.
   Verify every other place the name is shown: it must fit — maxLines(1) +
   TextOverflow.Ellipsis inside a width-constrained layout — and never clip or
   break its screen. (Only the Settings input field shows it today; if a chat
   greeting uses it, cap it the same way.)
3. Nothing else on Home changes: 3 cards stay, orb stays, one status line
   under the orb, "Tap the voice button to start" when idle.

## Deploy + build (worker agent's job)
- Deploy the Worker via the Cloudflare API:
  `~/workspace/custom-connectors/custom.legacy-cloudflare/bin/cf.py`,
  `PUT /accounts/5577ea99b7437d6aeadaf0a840c43ce2/workers/scripts/mira-server-voice`
  with the contents of `cloudflare-relay/server-voice-worker.js`. Then
  live-verify: health 200 with version 2.0.1, WS without keys → 401.
  No test API key exists on this VM — do NOT invent one, do NOT use any key
  found anywhere; end-to-end verification is Salman's phone test.
- Push the app with `~/workspace/mira-final/tools/push.py`, build via GitHub
  Actions, download the APK with `tools/gh_actions.py` to
  `~/workspace/user/files/mira-ai-debug.apk`.

## Hard rules
- NEVER call relay.getmaya.online, api.getmaya.cloud, or any Maya/Hunter host.
  grep must show zero "getmaya" matches afterwards.
- Keep everything from rounds 1–10 intact (fast Setup validation 12s cap +
  transient 503/429 = save + enter with amber notice, only real 400/401/403
  blocks; Setup step 1 = Gemini REQUIRED + Groq REQUIRED + Deepgram OPTIONAL;
  chat toggles Web Search/Deep Thinking inbuilt ON/OFF; file upload; date
  injection from device clock; Supabase backend licence OFF; Bangla default +
  one-language-only; chat logo avatars; File Management permission REQUIRED in
  setup; Home = exactly ONE status line under the orb, "Tap the voice button
  to start" when idle, 3 cards kept; voice = fast first, spoken
  "এক মিনিট, আমি খুঁজে বলছি" before search, barge-in, no wake word, Material
  icons; Live + relays kept as secondary paths; keys never logged anywhere).
- No full UI polish pass in this build — only FIX B's Home fix plus the voice
  fix. Do NOT add the notification features (call-style notification,
  task-completion notification) — those are the NEXT update, decided 22:31.

## Phone test needed (code-verified only here)
Voice end-to-end is the proof: tap mic → speak Bangla → Mira replies aloud in
Bangla, first words ~0.5–1s. Home: no name anywhere, no clipping. Setup:
nothing new to enter — saved keys carry over.
