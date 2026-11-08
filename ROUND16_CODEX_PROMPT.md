# MIRA AI — ROUND 16: Full analysis + UI redesign FIRST + make Chat & Voice work

You are Codex, a coding agent. Work in this directory: the Mira Android app repo
(`ryoaonetsuki/mira-ai-voice-assistant`). Native Kotlin + Jetpack Compose,
package `io.github.ryoaonetsuki.mira`, app "Mira AI", minSdk 26 / targetSdk 36.
Model: **gpt-6-astra** (the best GPT model available on this gateway for coding).

The owner (Salman, Indie Developer) is not a developer. You decide the technical
approach. Free-only: no paid APIs, no billing, no paid plans.

This is now a PERSONAL app, not a generic open-source product: make it work in
real use. Personal means: ANYONE can use it with THEIR OWN Gemini API key
(BYOK) — each user enters their own key at setup. No shared server-held key,
no single key for everyone. Server-side infrastructure (relay/proxy) is allowed
if that is the working path, but each user's key stays their own and is
forwarded per session only, never stored or shared on the server.

## 1. FULL ANALYSIS FIRST

Read the code. Do not guess. Read these first:
`SPEC.md`, `ROUND15_SPEC.md`,
`~/workspace/goals/project-mira-app-build/files/VOICE_DIAGNOSIS_ROUND2_2026-11-06.md`.
Understand the whole app: setup, home, orb, voice, chat, settings, extensions,
connectors, backend (Supabase + Cloudflare Workers).

### Detailed history of problems and errors (all of it — this is what you must fix)

- v1.0.0: NO replies at all. Chat stuck on "Mira is thinking…", voice silent.
  Root cause: REST client sent the key only as `?key=`, with no `x-goog-api-key`
  header. AQ. keys return 401 on `?key=` REST calls.
- Round 1: voice mic audio was sent in deprecated `mediaChunks` (server got zero
  audio); `responseModalities` was placed at the setup top level (ignored, TEXT
  default); the transcription parser read a non-existent field. Fixed, but
  voice still failed: "no setupComplete in 25s".
- Round 2: dual auth header + `?key=` (header removed from Live client); locale
  `bn-IN` changed to `bn-BD`; OkHttp pingInterval removed; 3 setup variants
  added. Date bug: asked "today's date?" in Roman Bangla, Mira answered
  "5 March 2025" (real date: 6 Nov 2026). Chat coroutine-scope crash fixed.
- Round 3: Live client aligned byte-for-byte with the reference; fallback voice
  built (on-device STT → REST → Gemini TTS); date/time injected from the device
  clock; chat model gemini-3.8-flash.
- Round 4: fallback voice had a silent infinite restart loop on
  SpeechRecognizer errors (UI stuck on LISTENING, no error shown). Fixed: stop
  after 3 same-kind errors and show the real error.
- Round 5: setup validation failed with transient Google 503. Fixed: retry with
  backoff; a valid-format key with only transient errors saves with an amber
  notice; only real 400/401/403 block.
- Round 6: validation spun FOREVER (retries stacked on a shared OkHttp client
  with no callTimeout). Fixed: one fast check, 12 s cap.
- Round 7: setup timeout 25 s → 45 s; stall-resume; `sessionResumption` +
  context-window compression in every setup; auth ladder ephemeral → direct
  `?key=` → Supabase relay → Cloudflare Worker relay.
- Round 8: instant-voice mechanisms (8 s mic pre-roll ring, on-device VAD,
  8-minute session rotation, speaker pre-primed); Cloudflare Worker relay
  deployed at `mira-gemini-live-relay.salman-dev.workers.dev`.
- Round 9: streaming server voice built (Groq Whisper → Gemini brain → Gemini
  TTS via Cloudflare Worker `mira-server-voice`).
- Round 10: ROOT CAUSE — `AudioRecorder` delivered mic chunks only when
  `isLiveForwarding` was true, set only by `flushPreRoll()`, which only the
  Live path called. `ServerVoiceSession` never called it, so ZERO mic audio
  ever reached the Worker. Fixed with `enableLiveForwarding(true)`. Also fixed
  a Home name-overflow bug (user name clipped on Home).
- Round 12: native Live rebuild. VM live test PROVED header auth works end to
  end (WS 101 in ~0.7 s, setupComplete ~1.1 s, Bangla audio reply, multi-turn
  works). Without `audioStreamEnd` the server never replies in 30 s.
  Top-level `responseModalities` is REJECTED by the real server (close 1008).
  In-app Live-capability check added (GET /v1beta/models).
- Round 14: ROOT CAUSES PROVEN — (1) the capability check NEVER paginated
  (GET /v1beta/models default pageSize 50, gemini-3.8-live sits on page 2), so
  EVERY key got "This key cannot use Live voice". Fixed with pageSize=100 +
  nextPageToken. (2) Force-close: Toast called from OkHttp threads with no
  Looper → crash exactly at the timeout. All background Toasts removed.
  (3) Home cards clipped: NavGraph Scaffold padding ignored. Fixed. New
  8-stage in-app Live diagnostic (Settings → Voice → Voice diagnostics).
  VM re-test: setupComplete ~1.0 s + Bangla audio reply.
- Round 14 phone test: capability fix WORKED (badge "Available"), BUT voice
  STILL fails at diagnostic Stage 4: WS 101 in 612 ms, setup sent (559 bytes),
  NO setupComplete in 15 s. The server accepts the socket and the setup, then
  stays SILENT — no error, no close. VM test with the stored key gets
  setupComplete in ~1 s with the same protocol. The difference is the user's
  key/project OR his phone's network path — UNPROVEN which.
- Round 15: Cloudflare Live relay inspected, fixed and redeployed (accepts
  `x-mira-gemini-key`, forwards it as `x-goog-api-key`, WS only on `/live`);
  the relay is now the DEFAULT voice route. Diagnostic variants added
  (RELAY/DIRECT × STANDARD/MINIMAL_V3/NO_RESUMPTION/NO_TOOLS, 45 s timeout,
  full frame logging). Voice Guardian DELETED completely + placeholder purge.
  Call-style notification (End + Mute) + task-completion notification added
  (real in code, never observed on a device). Voice STILL NOT proven on the
  phone; the stage-4 silence is STILL UNEXPLAINED.

Standing facts: Live is primary, model `gemini-3.8-live`. Chat ladder
gemini-3.8-flash → 3.6-flash → 3.5-flash-lite. Bangla is the default reply
language, ONE language only, never a translation underneath. Spoken search
acknowledgement: "এক মিনিট, আমি খুঁজে বলছি". No wake word. Material icons.
User name never on Home. Keys never logged. Home has 3 cards (Weather / Date /
Mood) visible below the top bar, exactly ONE status line, idle hint "Tap the
voice button to start". File Management permission is REQUIRED in setup.
Server Voice (Groq) card is only HIDDEN behind a toggle, not deleted or proven.
Dead code remains (`LicenceScreen.kt`, `Screen.SettingsSkills`, Deepgram key
storage functions). Supabase project "mira", ref `zvnfwlqxdbommtozugzt`.
`mira-server-voice` Worker error-detail change is repo-only (live Worker was
NOT redeployed).

## 2. UI REDESIGN FIRST (top priority — the current UI looks bad)

- Home: keep the current layout (3 cards Weather / Date / Mood below the top
  bar; big centred orb; ONE small status line under the orb; chips just above
  the bottom nav, horizontally scrollable; NO input bar on Home; NO red error
  banner) but make it MUCH more stylish, premium and professional.
- Bottom navigation: FLOATING design. Tabs, in order: Home | Vision (camera
  icon) | BIG centre Voice (same behaviour as now) | Memory | Chat.
- Vision: for NOW it only opens the camera and scans QR codes. Do NOT build AI
  vision now — later the AI will see through it (the screen, what is in front
  of the camera). Keep the tab and make the QR scan work.
- Orb: MUST stay functional exactly as before — ChatGPT-style orb,
  amplitude-driven, speaking-state animation, calm pulse when idle. Do not
  break it and do not redesign how it behaves.
- Icons: keep the current Material icons, OR switch to a better icon library
  if you judge it improves the design. Your call.
- Extensions page: STAYS, but with ZERO built-in extensions (empty state, no
  placeholders, nothing fake). The built-in macros do NOT work — they come back
  in a later step. Do not include them now.
- Connectors and the other useful pages: STAY. Remove EVERY fake or
  non-working option everywhere, especially in Settings. No placeholders
  anywhere. Keep only things that work, or things currently in progress
  (voice, chat / messages).
- Only Chat + Voice must work in this build. Everything else that stays must
  be real and working, or removed.
- Setup: ONLY ONE API key at open time — the Gemini API key. No Groq key
  required, no Deepgram, no other keys.
- Clean copy: no "runs on Gemini / Gemini API / BYOK" mentions anywhere in
  the UI.

## 3. FIX THE BUGS SO CHAT + VOICE WORK

- Analyse and fix ALL root causes. Server-side OR client-side — whichever
  works best, you plan it and build it yourself. If it needs to be fully
  server-side, build it fully server-side; if client-side, fully client-side.
- Voice: Gemini Live is primary (`gemini-3.8-live`), native speech-to-speech
  only. No silent fallback to another engine. A missing Groq key must NEVER
  block Live.
- Key model: EVERY user uses their OWN Gemini API key, entered in the app at
  setup (BYOK). Do NOT build a shared server-held key — no single key on the
  server for everyone. A server-side relay/proxy is fine, but it must forward
  each user's own key per session (as the current relay does with the
  `x-mira-gemini-key` header), never store or share one key.
- The stage-4 silence is the decisive unknown (his key/project vs his phone
  network). The relay already exists
  (`wss://mira-gemini-live-relay.salman-dev.workers.dev/live`, header
  `x-mira-gemini-key`) and is the default route.
- NEVER call any Maya/Hunter endpoint (`relay.getmaya.online`,
  `api.getmaya.cloud`, `sync.hunterislive.online`, `toolbaaz.online`, or any
  host with `getmaya` / `hunter` in it). No paid APIs. Free tier only.
- Keep working: the call-style notification (End + Mute) and the
  task-completion notification; the Web Search and Deep Thinking toggles in
  chat; Bangla default, one language only.

## ADDENDUM (owner, 2026-11-07 ~10:41, while Aider is still analysing)
- COLOUR THEME: many places have colour problems — the current theme feels too
  generic, like a typical "AI app" vibe. Rework it to look premium,
  professional and distinctive, not like a generic AI app. Keep the dark theme
  and the logo colours as the base; fix clashing or wrong colours everywhere.
- ICONS: try changing icons — where current icons look bad, use different
  ones: other Material icons, or another icon library if it looks better.
- MAIN POINT: EVERYTHING must actually work.
- LAYOUT / POSITION FIT: on Home and everywhere else, buttons and elements
  must fit properly — fix sizes, spacing, alignment, overflow and clipping
  (e.g. the earlier Home-cards-clipped-into-header and name-overflow bugs).

## 4. EFFICIENCY (the model quota is finite — 10M tokens, 1 day)

- Read only the files you need. Do not re-read huge files. Do not repeat
  full-repo scans. Batch your edits. One focused implementation pass.
- Do not burn the quota on repeated exploration. Analyse once, then implement.

## 5. VERIFICATION + FINAL REPORT

- The build must be GREEN (GitHub Actions workflow exists at
  `.github/workflows/build-apk.yml`). Run the unit tests. Fix build errors
  yourself.
- Your final report must distinguish: code-implemented / build-passed /
  tests-passed / live-API-tested / physical-device-tested. Never claim voice
  works without device evidence — you have no phone, so say that plainly.
- List every file you changed, every fake/placeholder option you removed, the
  architecture you chose and why, and anything still owed (for example the
  wrangler-secret step, if it applies).
