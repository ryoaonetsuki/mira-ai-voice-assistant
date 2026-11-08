# Mira AI — Round 8 spec: Maya's instant-voice mechanisms + Cloudflare relay rung

Salman's order: do what Maya does, so voice WORKS. Build Maya's MECHANISMS
(pre-roll, queue flush, pre-primed speaker, on-device endpointing, session
rotation) with OUR OWN infrastructure — Google's public Live API with the
user's key / ephemeral token, plus our Supabase relay, plus (new) a
Cloudflare Worker relay as an extra ladder rung. Free tier only, always.

## HARD PROHIBITIONS (Salman's clarification, 2026-11-06)
- NEVER call any Maya/Hunter endpoint from Mira — no `relay.getmaya.online`
  (ticket mint would FAIL their attestation: wrong app signature hash, no
  Maya licence lease, HMAC-signed headers; their system has a device-BAN
  verdict), no `api.getmaya.cloud/tts`, no `getmaya.online`, no
  `sync.hunterislive.online`, no `ops.hunterailabs.online`. "Free voice
  inside their app" is free for users of THEIR app under THEIR licence/trial
  system — it is NOT a public API for other apps. Do not call, probe, or
  reference these hosts in code, comments, or docs (except this prohibition).
- Do NOT use Maya's Ultra/Hunter TTS (proprietary). Do NOT copy Maya code.
- No wake word. Model stays `gemini-3.8-live` (Maya's FAST
  `gemini-3.1-flash-live-preview` shuts down 17 Nov 2026; only 3.8-live
  survives). Keep the round-7 auth ladder order; Cloudflare relay is an
  ADDITIONAL rung after the Supabase relay, not a replacement.

## Evidence
- `~/workspace/rea-analyses/maya/MAYA_VOICE_DEEP_DIVE.md` §6 (mechanisms +
  constants) and §7 (what Mira lacks). Read it before coding.
- Current state: round-7 (run 42214567200 GREEN). `GeminiLiveClient.kt`
  (828 lines): 45s setup timeout, stall-resume (30s stall / 8-min give-up),
  sessionResumption + contextWindowCompression in EVERY setup variant
  (V1/V2/V3), auth ladder Ephemeral -> DirectKey -> Supabase Relay
  (`gemini-live-relay`, project "mira" ref `zvnfwlqxdbommtozugzt`, live-
  verified 2026-11-06). `MicService.kt`: AudioRecorder starts ONLY on
  onSetupComplete (line ~361) — words spoken while the socket opens are
  lost today. `sendAudioChunk` drops audio before setupComplete.
  `AudioPlayer.kt`: AudioTrack created + `play()` at construction, playback
  loop blocks on `take()` (plays first chunk immediately — no pre-buffer
  rule yet). No VAD dependency: implement endpointing with plain RMS
  (Maya's own method), no new Gradle dependency.

## Build (voice files only — Home/Setup UI exactly as v1, Material icons)

### 1. 8-SECOND MIC PRE-ROLL RING (biggest win)
- New `PreRollRingBuffer` (or inside `AudioRecorder`): continuous 16 kHz
  PCM16 mono ring, window 8,000 ms.
- Mic recording starts when the voice session STARTS (MicService session
  start, before/with the socket open) — NOT on setupComplete.
- On setupComplete: flush ring contents in order into the Live session
  (discard frames older than 20,000 ms), log `t2.7 preroll-flushed ms=<n>`.
- Ring keeps recording during the session; stop + release on session end.
- Must not break the mic-permission gate or the File Management permission
  gate; if mic start fails, same error path as today.

### 2. PRE-SETUP HELD-QUEUE FLUSH (GeminiLiveClient)
- `sendAudioChunk` / `sendTextMessage` called before setupComplete must
  QUEUE, not drop. Held queue capped at 128,000 bytes (oldest dropped past
  the cap); on setupComplete, flush in order before live audio flows.
- Log queue depth + flushed bytes. Queue is cleared on session end / mode
  advance (do not replay stale audio into a new socket).

### 3. TRANSCRIBER-READY BUFFERING — merged into (2)
- Mira has no transcriber (pure Live by Salman's decision). The 128,000-byte
  held queue in (2) IS the equivalent: audio held until the session is
  ready, flushed first. Do not build a transcriber.

### 4. SPEAKER PRE-PRIMED (AudioPlayer)
- AudioTrack created + `play()` called at session activation (already true
  at construction — keep it, and make sure MicService constructs the player
  at session start, before setupComplete).
- Drain rule: first chunk of an utterance plays when queued audio is
  < 9,600 bytes OR 350 ms have elapsed since the first chunk arrived
  (`prebuffer_ms` logged as `t5 playback-start prebuffer_ms=<n>`).
- No decode step on the Live path (raw PCM16 straight to AudioTrack —
  already true; keep it). Barge-in flush behaviour unchanged.

### 5. ON-DEVICE ENDPOINTING (new, no dependency)
- New `VoiceActivityDetector` (RMS per frame, adaptive threshold):
  speech declared after 150 ms above threshold, endpoint after 300 ms
  silence. No server round-trip to detect end of speech.
- Used for local speech-state callbacks + log markers (`t-speech`,
  `t-endpoint`) and to stop sending trailing silence; do NOT disable the
  server-side detection (V2 `automaticActivityDetection` stays), do NOT
  send `activityEnd` (protocol risk — server VAD remains authoritative).

### 6. DUAL-SLOT SESSION SWAP / ROTATION
- Capture `sessionResumptionUpdate.newHandle` from server messages;
  persist it (SharedPreferences/DataStore, never log the value) and replay
  as `sessionResumption: {handle: <stored>}` in every setup (empty object
  when none stored — as today).
- Zero-gap rotation at 8 min from setupComplete: open a STANDBY
  GeminiLiveClient with the same auth modes + stored handle; on its
  setupComplete, swap it in as current (mic stream retargets, old session
  closed). If standby setup fails, keep the current session and retry
  rotation once. Log `rotation-standby`, `rotation-swapped`.
- Keep the existing 8-min stall give-up; rotation must not fight it
  (rotation at 8 min of a HEALTHY session; stall give-up only when the
  server has been silent).

### 7. CLOUDFLARE WORKER RELAY RUNG (new infrastructure)
- New `LiveAuthMode.CloudflareRelay(key, workerUrl)` rung AFTER the
  Supabase Relay rung in the ladder: Ephemeral -> DirectKey ->
  Supabase Relay -> Cloudflare Relay. One variant (V1) only, same 45s
  setup timeout, same overall 240s safety budget.
- Worker (free tier, account "Salman-Dev", deployed via
  `~/workspace/custom-connectors/custom.legacy-cloudflare/bin/cf.py`):
  upgrades the app's WS, opens upstream WS to Google Live with `?key=`
  from the `x-mira-key` header, forwards frames verbatim both ways,
  never logs keys or frame content. Mirror the Supabase `gemini-live-relay`
  behaviour exactly (same headers: `apikey`/`Authorization` not needed on
  Workers — use a shared Worker secret header if the API supports it;
  simplest: same `x-mira-key` header, no other auth, Worker URL
  unguessable). Deploy it, live-verify it (WS upgrade with a dummy key
  must reach Google and fail cleanly; without `x-mira-key` must reject),
  and record the Worker URL in this spec + the final report.
  - **Deployed Worker URL**: `https://mira-gemini-live-relay.salman-dev.workers.dev` (WebSocket endpoint: `wss://mira-gemini-live-relay.salman-dev.workers.dev`)
  - **Live Verification**:
    - Health probe (`GET ?action=health`): HTTP 200 `{"service":"gemini-live-relay","version":"1.0.0","ok":true}`
    - Missing key (`Upgrade: websocket` without `x-mira-key`): HTTP 401 `{"error":"Missing x-mira-key header"}`
    - Upstream handshake with key: Upgrades to HTTP 101 WebSocket connection to Google Live API cleanly.
- Worker URL goes in `BuildConfig` (or the same config path the Supabase
  relay URL uses) — never hardcode a key. No user personal data, no chat
  history, no keys stored in the Worker.
- If the Cloudflare deploy is blocked (API/account problem), STOP that
  part, keep the Supabase relay as the last rung, and report the blocker
  exactly — do not fake it, do not retry through another route.

### 8. KEEP INTACT (rounds 1–7)
Fast Setup validation (12s cap), chat toggles, file upload, Bangla default
(ONE language only), file-permission gate, date injection, Supabase backend
licence OFF, Home/Setup UI exactly as v1, Material icons, no wake word,
45s setup timeout, stall-resume, sessionResumption/contextWindowCompression
in every setup variant, ephemeral-first auth ladder.

## Verification (code only — no Gemini key on the VM)
- `./gradlew assembleDebug` must pass locally if the SDK allows, else the
  GitHub Actions run must be GREEN (that is the proven path).
- Log markers must exist: `t2.7 preroll-flushed`, `t5 playback-start`,
  `t-speech` / `t-endpoint`, `rotation-swapped`, resumption-handle
  persist/replay (value never logged).
- No reference to any Maya/Hunter host anywhere in the tree
  (`grep -ri "getmaya\|hunteris\|toolbaaz" app/src` must be empty).

## Phone test needed (code-verified only here)
Voice end-to-end: tap and speak IMMEDIATELY (even before the orb shows
listening) — the words before the tap must reach Mira via pre-roll.
First reply target ~1s after speech ends. Report which auth mode
connected (logcat MiraLive / MiraMicService) and whether the Cloudflare
rung was ever reached.
