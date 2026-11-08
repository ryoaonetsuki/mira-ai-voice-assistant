# AGY PROMPT — Mira AI ROUND-15: FINAL WORKING VERSION

You are implementing Mira AI round-15 in ~/workspace/mira-final/ (repo
ryoaonetsuki/mira-ai-voice-assistant, package io.github.ryoaonetsuki.mira,
native Kotlin + Jetpack Compose, app "Mira AI"). Read ROUND15_SPEC.md in full
first — it is the authority. Do NOT skip the spec. Do NOT redesign the UI.
Do NOT write code outside this project.

## THE PROBLEM (proven, not guessed)

Salman's phone test of the round-14 APK (2026-11-07 ~07:18): the capability
check now works ("Live Voice Capability: Available"), the in-app Live
diagnostic connects (WS 101 in 612ms) and sends setup (559 bytes) — then the
server stays SILENT: "Timed out waiting for setupComplete (15s)", stage 4
fails, stages 5–8 never run. Chunks sent/received: 0.

The coordinator already proved on this VM (2026-11-07 ~07:25, stored key,
header auth) that the EXACT diagnostic setup payload works: WS 101 in 0.9s,
setupComplete in 0.1s, Bangla audio reply (7 chunks, 51,842 bytes,
"নমস্কার"), turnComplete, 2nd turn works. So the payload is exonerated.
What differs between VM and phone is THE KEY or THE NETWORK PATH. Prove
which one with the relay experiment — do not assume.

## WHAT TO BUILD (in this order)

1. **Cloudflare Worker Live relay** (free tier, account Salman-Dev
   5577ea99b7437d6aeadaf0a840c43ce2). First INSPECT the existing
   `cloudflare-relay/worker.js` (deployed round-8 at
   mira-gemini-live-relay.salman-dev.workers.dev) — reuse it if it correctly
   proxies BidiGenerateContent both ways with the key forwarded as
   `x-goog-api-key`; rewrite it if not. Redeploy with wrangler if changed.
   The app gets a relay client: phone opens WS to the Worker, sends the
   user's key per-session in `x-mira-gemini-key` (never logged, never
   persisted server-side); the Worker opens WS to Google's BidiGenerateContent
   and relays frames both ways (text + binary, ping/pong, close codes).
   Decision rule: relay works on his phone → relay becomes PRIMARY voice
   path. Relay also fails with his key → key/project is the problem, and
   ONLY then implement the server-key variant (Worker holds the stored key
   as a wrangler secret — never print it, never store it in a file/repo —
   with an explicit user opt-in; quota is then SHARED, warn about it).
2. **Diagnostic variants in-app**: extend LiveDiagnosticRunner with minimal
   setup, setup without sessionResumption/contextWindowCompression, setup
   without tools, 45s timeout, and direct-vs-relay for every variant, with
   full frame logging (frame type, close code + reason, exact ms). Report the
   exact failing stage per variant.
3. **DELETE Voice Guardian completely** (card in VoiceEngineScreen.kt,
   drawer entry, nav route, ViewModel/data, strings, assets). Grep-verify
   zero matches for voiceguardian / voice guardian / voiceprint /
   "Owner (Alex)".
4. **DELETE every placeholder/demo/fake UI element in the whole app.**
   Audit every screen (Extensions, Skills, Connectors, Memories, Rules,
   Vibes, Markets, Marketing, ImageGen, Whiteboard, Documents, CodeProjects,
   Notifications, Licence, Update, Maya/ Secret/ Social/ Email/ Messages/
   Backup/ Advanced/ Optional/ Personal/ About settings, Server Voice (Groq)
   card, Deepgram card). If it does not work end-to-end, delete its UI.
   Deepgram: either wire it into a real STT path or remove the card.
5. **Call-style persistent notification**: real NotificationChannel, ongoing
   notification during a voice session with working End (stops session, Home
   to idle) and Mute (mutes mic, toggles) actions via real PendingIntents to
   MicService. Appears on session start, disappears on end/error/timeout.
6. **Task-completion notification**: when Mira finishes building a
   website/file/project while the app is backgrounded/closed, post a real
   notification ("আপনার কাজ হয়ে গেছে") from the actual completion callback;
   tapping it opens the finished thing.
7. **Keep**: Live primary, Gemini-only required credentials, Groq never
   required, no silent fallback, fast Setup validation, chat Web Search +
   Deep Thinking toggles, Bangla default one-language, spoken "এক মিনিট,
   আমি খুঁজে বলছি" for search, barge-in, no wake word, Material icons, name
   never on Home, Home 3 cards visible below the top bar (do not regress the
   round-14 fix).

## CONSTRAINTS

- FREE-ONLY. No paid endpoint, no billing, no silent switch to a paid API.
- NEVER call relay.getmaya.online, api.getmaya.cloud, or any Maya/Hunter
  host. Grep-verify zero getmaya/hunteris/toolbaaz in the final tree.
- NEVER log keys/tokens. Never put a key in a file, a test, or a log.
- NEVER ask Salman for another API key.
- Every user-visible string stays in English (app UI language is English;
  Mira's SPOKEN replies default to Bangla).

## TESTS + VERIFICATION (do not skip)

- Add unit tests: relay client (frame relay, auth forwarding, close-code
  passthrough, no key in logs), diagnostic variants, notification actions.
  Tests must run in CI (`./gradlew testDebugUnitTest` before assembleDebug).
- VM live tests with the stored key via the surrogate flow (never print the
  key): re-run the exact-diagnostic-payload test after any payload change;
  end-to-end relay test (client → Worker → Google → setupComplete → audio).
- Push with tools/push.py. GitHub Actions build must be GREEN. Download the
  APK artifact to ~/workspace/user/files/mira-ai-debug.apk. Verify with
  aapt2 (package io.github.ryoaonetsuki.mira, version, size, md5) + dex
  strings for the new code + zero getmaya.

## REPORT BACK (full, honest)

Exact root cause of the stage-4 silence with the evidence that proved it;
why the VM passes and the phone failed; which fix path was chosen and why;
every file changed; every placeholder removed (list them); notification
implementation proof; build + test results (Actions run number, APK
path/size/md5); whether a real native voice conversation was verified (VM
only or phone); free-tier limits; anything still owed. If voice still cannot
be proven on the phone, say so plainly with the exact remaining blocker and
the next decisive test. Never claim physical-device testing that did not
happen. Never claim voice works until Salman phone-tests it.
