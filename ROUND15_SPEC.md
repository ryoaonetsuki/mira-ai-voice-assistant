# Mira AI ROUND-15 SPEC — FINAL WORKING VERSION

Date: 2026-11-07 ~07:25 +06. Supersedes everything before it (rounds 1–14).
Repo: ryoaonetsuki/mira-ai-voice-assistant, package io.github.ryoaonetsuki.mira.
Work dir: ~/workspace/mira-final/. Build ONLY with agy (Gemini 3.8 Flash High does
plan + implementation; Claude Opus/Sonnet are quota-blocked; Salman has NOT given a
Claude API key — do NOT wait for one). Push with tools/push.py, build via GitHub
Actions, APK to ~/workspace/user/files/mira-ai-debug.apk.

## 0. THE FAILURE — NOW PRECISELY ISOLATED (Salman's phone test 2026-11-07 ~07:18, round-14 APK)

GOOD NEWS: the round-14 capability fix WORKED. His key shows "Live Voice
Capability: Available" — "models/gemini-3.8-live is listed for this key (supports
bidiGenerateContent)".

THE FAILURE (in-app "Run Live diagnostic", Settings → Voice → Voice diagnostics):
1. API Key Check ✓ (1ms) — 2. WebSocket Connection ✓ (612ms, HTTP 101) —
3. Setup Payload ✓ (sent, 559 bytes) — 4. Setup Confirmation ✗ — "Timed out
waiting for setupComplete from server (15s)". Stages 5–8 never run.
Last Error: "Live voice: Connection timed out — tap to retry". Last Session Stage:
STOPPED, WebSocket Status: HTTP 1000, Chunks Sent/Received: 0.
So: the server accepts the socket and the setup, then stays SILENT. No error
frame, no close with a reason.

## 0.1 RECON DONE BY THE COORDINATOR (2026-11-07 ~07:25, this VM) — READ FIRST

The EXACT diagnostic setup payload was byte-diffed against the VM raw-WS test
that works (scripts in ~/workspace/rea-analyses/mira/live-test-2026-11-06/,
`/tmp/live_ws_diag.py` variant `diag` = exact mirror of
`GeminiLiveClient.buildSetupPayload` as called by `LiveDiagnosticRunner`:
model models/gemini-3.8-live, generationConfig.responseModalities=[AUDIO],
speechConfig voice Aoede + languageCode bn-BD, realtimeInputConfig
automaticActivityDetection END_SENSITIVITY_HIGH/800ms, input+output audio
transcription, sessionResumption {}, contextWindowCompression slidingWindow,
systemInstruction "You are Mira. Reply in Bengali with only one word: 'নমস্কার'.").
Phone reported 559 chars; VM compact JSON is 572 UTF-8 bytes (559 chars —
the 7 Bengali chars are 3 bytes each; Kotlin String.length counts chars).
Result with the STORED key + header auth, run 2026-11-07 ~07:25:
**WS 101 in 0.9s → setup sent → setupComplete in 0.1s → text turn → Bangla
audio reply (7 chunks, 51,842 bytes, transcript "নমস্কার") → turnComplete →
2nd turn works.** THE PAYLOAD IS EXONERATED. The V2+V3 combination is NOT the
bug (round-12 already proved V1/V2/V3 individually; this proves the combined
exact-diagnostic payload too).

REMAINING VARIABLES between VM (works) and his phone (silent): THE KEY and THE
NETWORK PATH. Do NOT assume either — prove it with the relay experiment (§1).
Auth is NOT the prime suspect (101 + no 1008 close + no error frame = server
silently dislikes something downstream, or the reply path is broken).

## 1. PROVE IT: CLOUDFLARE WORKER LIVE RELAY (PRIMARY EXPERIMENT)

Salman explicitly authorised this (2026-11-07 ~07:19): "যদি কোনো প্রক্সি বা অন্য
কিছু লাগালে ঠিক হয়ে যায় তাহলে সেই সিস্টেমটা করো" — proxy/relay/server,
whatever fixes it. Free tier only, no paid endpoint, no Maya/Hunter host ever
(grep-verify zero getmaya/hunteris/toolbaaz).

- NOTE: a Live relay Worker ALREADY EXISTS in repo: `cloudflare-relay/worker.js`,
  deployed round-8 at https://mira-gemini-live-relay.salman-dev.workers.dev
  (account Salman-Dev 5577ea99b7437d6aeadaf0a840c43ce2, free tier; round-8 checks:
  health → 200, WS without key → 401, with dummy key → 101). INSPECT it first:
  verify it actually proxies BidiGenerateContent frames both ways and forwards
  the key as `x-goog-api-key` to Google. Reuse it if it is correct; rewrite it
  if it is not. Test it end-to-end FROM THIS VM with the stored key (surrogate
  exchange, key never visible): phone-simulated client → Worker WS → Google →
  setupComplete must arrive. If the existing worker is broken, fix it in-repo
  and redeploy with wrangler (deploy credentials via the Cloudflare connector;
  never print secrets).
- App side: new relay client (e.g. `voice/RelayLiveClient.kt`) — phone opens WS
  to the Worker (`wss://mira-gemini-live-relay.salman-dev.workers.dev/live`),
  sends the user's key per-session in a header (same pattern as the existing
  mira-server-voice Worker: `x-mira-gemini-key`, never logged, never persisted
  server-side). Worker opens WS to
  `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent`
  with `x-goog-api-key`, and relays frames bidirectionally (text + binary,
  ping/pong, close codes/reasons preserved). The diagnostic runner MUST gain a
  relay variant (same 8 stages, labelled "via Cloudflare relay") so Salman's
  phone test can compare direct vs relay on the same screen.
- DECISION RULE (apply exactly):
  - If the relay path gets setupComplete on his phone → the phone's DIRECT
    network path was the problem. The relay becomes the PRIMARY voice path
    (direct kept as a fallback the user can select, never silent).
  - If the relay ALSO fails with his key (same stage-4 silence) → the
    KEY/PROJECT is the problem. Then and ONLY then implement the server-key
    variant: the Worker holds the PROVEN-WORKING stored key as a wrangler
    secret (set via the connector credential flow — never print the value,
    never store it in a file or the repo), and the app offers "Use Mira's
    server key" as an explicit opt-in. WARN in the report that quota is then
    SHARED across all users (unlike BYOK) — per-user free quota no longer
    applies. Do NOT switch to server-key silently.
- The relay must also be used by the real voice session (MicService), not only
  by the diagnostic — otherwise the phone test proves nothing about voice.

## 2. DIAGNOSTIC VARIANTS (in-app, alongside the relay variant)

Extend `LiveDiagnosticRunner` so one screen can run, each with full frame
logging (every WS frame type, close code + close reason, exact ms timing,
never keys):
- (a) Minimal setup (round-12 V3: model + generationConfig + speechConfig +
  languageCode only, no sessionResumption, no contextWindowCompression, no
  realtimeInputConfig, no transcriptions).
- (b) Setup WITHOUT sessionResumption/contextWindowCompression (everything
  else as the current diagnostic).
- (c) Setup WITHOUT any tool (confirm no `tools` field is sent anywhere —
  the current payload has none; verify).
- (d) Longer setup timeout: 45s (matching the app's Live timeout), not 15s.
- (e) Direct path (current) AND relay path (§1) for every variant.
Report the exact failing stage for each variant. Keep the 8-stage display.

## 3. REMOVE VOICE GUARDIAN COMPLETELY

Delete it, do not hide it behind a flag. Remove: the Voice Guardian card in
`VoiceEngineScreen.kt` ("Enrolled Voiceprint: Owner (Alex)" is a FAKE demo —
it does nothing and needs a separate model download), any drawer entry, any
nav route, any Settings screen, any ViewModel/data/store field, any string
resource, any asset. Grep-verify zero matches for voiceguardian / voice
guardian / voiceprint / "Owner (Alex)" in the whole repo after removal.

## 4. REMOVE EVERY PLACEHOLDER / DEMO / FAKE THING FROM THE ENTIRE APP UI

Audit EVERY screen. Any card, option, toggle, button or screen that does not
actually work must be DELETED from the UI completely — not greyed out, not
"coming soon", not hidden behind a flag. If a feature cannot be made to work
in this round, remove its UI. The app must contain ZERO non-working UI.
Known candidates to verify (audit, don't assume — check each one in source
and either prove it works end-to-end or delete it):
- Voice Guardian (§3).
- Server Voice (Groq) card when the toggle is OFF — either the card works
  (real Groq key flow + real session) or it goes.
- Deepgram card — it is currently storage-only, unwired to any voice path.
  Either WIRE it into a real path (Deepgram nova-3 STT as an explicit,
  optional, user-selected voice input) or REMOVE the card entirely.
- Any demo macros / connectors / tools / extensions / skills / memories /
  vibes / markets / image-gen / marketing screens or entries that are not
  real and working. Check: ExtensionsScreen, SkillsSettings, Connectors,
  Memories, Rules, Vibes, Markets, Marketing, ImageGen, Whiteboard,
  Documents, CodeProjects, NotificationsScreen, LicenceScreen, UpdateScreen,
  MayaSettings, SecretSettings, SocialSettings, EmailSettings,
  MessagesSettings, BackupSettings, AdvancedSettings, OptionalSettings,
  PersonalSettings, AboutScreen — every entry on every screen.
- Any toggle that does nothing, any button that shows a Toast instead of
  doing the thing, any fake counter/stat/badge.
Report the full removal list in the final report (file + what was removed).

## 5. ADD IN THIS UPDATE (his order, no longer "next update")

(a) VOICE CALL-STYLE PERSISTENT NOTIFICATION during a voice session, like
ChatGPT/other AI apps: real NotificationChannel, ongoing notification with
the session state, and working actions — End (stops the session, returns
Home to idle) and Mute (mutes the mic, un-mute toggles back). The actions
must actually work (real PendingIntents → MicService commands), not
placeholders. Notification must appear when a session starts and disappear
when it ends (including on error/timeout/crash-path cleanup). Must work with
the app backgrounded and (where the OS allows) with the screen off.
(b) BACKGROUND TASK-COMPLETION NOTIFICATION ("আপনার কাজ হয়ে গেছে") when
Mira finishes building something (website/file/project) while the app is
closed/backgrounded: real trigger on actual task completion (hook the real
completion callback in the website/file build flow), tapping it opens the
finished thing. No fake/periodic notification.
Both need unit tests (notification actions, channel creation, trigger wiring)
and must be verified in CI where testable without a device.

## 6. KEEP EVERYTHING ALREADY DECIDED

Live primary; Gemini-only required credentials; Groq never required; no
silent fallback to another engine; fast Setup validation; chat Web Search +
Deep Thinking toggles; Bangla default one-language; spoken "এক মিনিট, আমি
খুঁজে বলছি" for search; barge-in; no wake word; Material icons; name never
on Home; keys never logged; Home 3 cards visible below the top bar (round-14
fix — verify it stays fixed, do not regress it); no full UI polish pass in
this build (removals in §3–§4 are deletions, not redesign).

## 7. TESTING — DO NOT SKIP

- Gradle build + Kotlin compilation via GitHub Actions; CI runs
  `./gradlew testDebugUnitTest` BEFORE assembleDebug (round-14 CI change —
  keep it).
- Unit tests: ADD tests for the relay client (frame relay, auth header
  forwarding, close-code passthrough, no key in logs), the diagnostic
  variants (§2), and the notification actions (§5). Mock the HTTP/WS layer;
  never a real key in tests.
- VM live tests with the stored key (surrogate exchange, never print the
  key): (i) re-run the exact-diagnostic-payload test (already proven §0.1 —
  re-run after any payload change); (ii) end-to-end test of the relay Worker
  (§1) — client → Worker → Google → setupComplete → audio reply.
- Distinguish in the report: code-implemented / build-passed /
  automated-tests-passed / live-API-tested / physical-device-tested. No
  physical device is available here — do NOT claim physical-device
  verification. Do NOT claim voice works until Salman phone-tests it.

## 8. DELIVERABLES

- All fixes in the repo, pushed via tools/push.py, GitHub Actions build
  GREEN, APK downloaded to ~/workspace/user/files/mira-ai-debug.apk and
  verified with aapt2 (package, version, size, md5) + dex-string check for
  the new code (relay client, diagnostic variants, notification actions)
  and zero getmaya/hunteris/toolbaaz.
- Final report MUST contain: the exact root cause of the stage-4 silence
  (with the evidence that proves it — which variant/relay test proved it);
  why the VM test passes and the phone failed; which fix path was chosen
  and why; every file changed; every placeholder removed (list them);
  notification implementation proof; build + test results (Actions run
  number, APK path/size/md5); whether a real native voice conversation was
  verified (and on what — VM only or phone); free-tier limits; anything
  still owed. If voice still cannot be proven on the phone, say so plainly
  with the exact remaining blocker and the next decisive test.
