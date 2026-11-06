# Mira AI — Round 7 spec: Live tolerance + server-side voice (ephemeral tokens + relay)

Salman's choice: fix Live first, test, decide on Groq after. Server-side is on the table
(he authorised building whichever side is needed so voice works like Maya's).

## Evidence this round builds on
- REA (~/workspace/rea-analyses/mira/MIRA_APK_VOICE_REPORT.md,
  ~/workspace/rea-analyses/maya/VOICE_FRESH_EYES.md): shipped Live client is faithful
  to Maya on every wire detail; server accepts the socket (HTTP 101) and never
  answers setup. Differences that matter: Maya tolerates 45s (Mira 25s), has
  stall-resume, sends sessionResumption + contextWindowCompression in every setup.
- Official Google docs (ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens):
  ephemeral tokens are THE documented pattern for connecting directly from a
  user's device; raw ?key= in the WS URL is the server-to-server pattern.
  Token defaults: uses=1, 1 min to start a session, 30 min to send messages;
  sessionResumption is required to reconnect every 10 min.
- Supabase docs: Edge Functions support WebSocket relay servers for external APIs.
- The mira-gemini Edge Function was NEVER deployed (functions list was empty);
  its mintLiveToken action already accepted a customKey. No GEMINI_API_KEY
  secret is set on the project (BYOK customKey path needs none).

## Supabase project (fills the long-owed gap)
- Project "mira", ref `zvnfwlqxdbommtozugzt`, region ap-southeast-1, ACTIVE_HEALTHY.
- Functions deployed 2026-11-06 (Management API deploy endpoint, deploy.py):
  - `mira-gemini` — ACTIVE. mintLiveToken now accepts uses (1..5, default 1),
    expireMinutes (default 30), newSessionMinutes (default 1).
  - `gemini-live-relay` (new) — ACTIVE. Upgrades the app's WS, opens upstream WS
    to Google server-side with ?key=, forwards frames verbatim. Auth: apikey +
    Authorization (anon JWT) + x-mira-key header. Never logs key/frame content.
  - Live-verified: mira-gemini health 200 (configured:false — no server key,
    expected); relay health 200; relay WS upgrade with a key opens and Google
    rejects a dummy key cleanly (relay closes); without x-mira-key the gateway
    answers 502 (function's 401 is masked by the gateway — harmless).

## App changes (voice files only)
- GeminiLiveClient.kt:
  - SETUP_TIMEOUT_MS 25s -> 45s.
  - sessionResumption:{} + contextWindowCompression:{slidingWindow:{}} in EVERY
    setup variant (V1/V2/V3), not just V2.
  - Post-setup stall watchdog: 5s checks; user audio sent but no server message
    for >30s = STALL (onStalled(), session stays alive, no error); silence past
    8 min = give up with "Live session stalled — no reply from the server for
    8 minutes" (then the existing fallback path runs).
  - Auth-mode ladder (new sealed LiveAuthMode): Ephemeral(token) ->
    DirectKey(key) -> Relay(key, relayUrl). Mode 1 tries V1/V2/V3 (45s each);
    later modes V1 only. Overall safety budget 240s, then onError -> fallback.
    Diagnostic records modes tried.
- MicService.kt: UserKey flow mints an ephemeral token FIRST via
  mintLiveTokenForUserKey (uses:3, 30 min, 5 min new-session window); success ->
  [Ephemeral, DirectKey, Relay]; mint failure -> [DirectKey, Relay] (never
  blocks). ServerKey/NoKey behaviour unchanged. Relay URL from
  BuildConfig.SUPABASE_URL + /functions/v1/gemini-live-relay.
- ApiKeyResolver.kt: mintLiveTokenForUserKey(userKey) added; shared
  requestLiveToken(payload) helper; token read from `token` or `name`.

## Unchanged (per brief)
?key=-only for direct mode, gemini-3.8-live only, OkHttp 60/0/60 + ping 20s,
16kHz PCM16 realtimeInput.audio, fallback tap-to-talk, fast Setup validation
(12s cap), chat toggles, file upload, Bangla default, file-permission gate,
date injection, Supabase backend licence OFF, Home/Setup UI exactly as v1,
Material icons (parallel agent owns icons — untouched here).

## Phone test needed (code-verified only here)
Voice end-to-end: which auth mode connects (logcat MiraLive / MiraMicService
shows the mode + variants tried), 45s tolerance, stall behaviour, relay path.
No Gemini key exists on the VM, so no live WS/REST call was possible here.
