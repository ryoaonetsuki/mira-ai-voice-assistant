# ROUND-14 AGY PROMPT — Mira AI final root-cause debugging & complete voice repair

Work in ~/workspace/mira-final/ (repo ryoaonetsuki/mira-ai-voice-assistant, package
io.github.ryoaonetsuki.mira). Read ROUND14_SPEC.md FIRST — it contains four root
causes already proven with live evidence. Implement the spec exactly; do not
re-litigate the diagnosis, do not ask for another API key, do not merely recommend
fixes — make the actual code changes.

## The decisive fact
Salman created a BRAND-NEW Gemini key and got the SAME "Live Voice Capability: Not
Supported / This key cannot use Live voice" error. The key was never the cause.
Live-proven root cause (spec §0.1): `checkLiveCapability` calls `GET /v1beta/models`
with NO pageSize and NO pagination. Google's default pageSize is 50; the Live models
(including models/gemini-3.8-live) sort onto page 2 (50 on page 1, 11 on page 2).
The check can NEVER find the model, so it says "Not Supported" for EVERY key.

## What to do (spec §1–§9, in order)
1. **Capability check (§1):** `GET /v1beta/models?pageSize=100` + follow
   `nextPageToken` (cap ~5 pages); match the configured Live model AND require
   `bidiGenerateContent`. Classify errors distinctly (400/401/403 auth, 429/503
   transient, network, parse) — NEVER blanket "Not Supported". Only a full
   paginated fetch with the model genuinely absent may say "not listed". No
   cached negative results. Label it as "listed", not "works" — the diagnostic
   (§2) is the proof.
2. **Isolated Live diagnostic (§2):** new `voice/LiveDiagnosticRunner.kt` + a "Run
   Live diagnostic" button in Settings → Voice → Voice diagnostics. Stages with
   timestamps: key present → WS connect (HTTP code) → setup sent → setupComplete
   (ms) → controlled TEXT turn → first server response → first native AUDIO chunk
   → turnComplete. Stop at the exact failing stage with the exact error. No mic,
   no Server Voice/Groq/Whisper/Deepgram. Never report success from WS-open alone.
3. **Protocol verification (§3):** verify GeminiLiveClient against the official
   Live docs (ai.google.dev/gemini-api/docs/live). FIX: gate mic frames to after
   setupComplete (today CONFIGURING also sends). Keep header auth, proven setup
   shape, continuous streaming incl. silence, audioStreamEnd on endpoint.
4. **State bug + timeout crash (§4):** remove EVERY Toast from non-main-thread
   paths (MicService onError/onStalled are called from OkHttp threads / Default
   watchdogs — Toast without a Looper throws and force-closes the app on the
   timeout path; this is the crash). Errors go through _errorMessage StateFlow
   only (Home already toasts on Main). Harden all listener callbacks against
   exceptions. Guarantee terminal ERROR state + clean stop on every failure path.
   Add a 45s MicService CONNECTING watchdog. Never crash on voice failure; Home
   returns to idle ("Tap the voice button to start") with error + retry.
5. **Audio (§5):** only after connection works — verify real byte buffers
   (PCM16 16kHz LE in, 24kHz LE out), AudioRecord/AudioTrack lifecycle, VAD +
   barge-in, cleanup on every exit path.
6. **Server Voice (§6):** worker must include the underlying error detail
   (provider, HTTP status, short message, never keys) in its error frame instead
   of only "Chat service unavailable". Do NOT invent the downstream cause — live
   Worker logs were not accessible; say so in the report. Stays optional/OFF.
7. **Home cards (§7):** NavGraph ignores the Scaffold `padding` for Home, so
   content draws under AppTopBar — apply `Modifier.padding(padding)` to the Home
   content. 3 cards fully visible, v1.0.0 layout, name NEVER on Home, long-name
   safe.
8. **Tests + CI (§9):** add unit tests for §1 pagination/classification and §2
   stage recording. Add `./gradlew testDebugUnitTest` to
   `.github/workflows/build-apk.yml` BEFORE assembleDebug (the 7 round-12 tests
   were never executed). Confirm tests pass in CI.

## Constraints (keep all of §8)
Gemini Native Live PRIMARY; Groq NOT required; no separate Gemini TTS for primary
voice; native speech-to-speech only; Bangla default, one language only; barge-in;
no wake word; Material icons; keys NEVER logged (logs, files, chat); never call
relay.getmaya.online, api.getmaya.cloud, or any Maya/Hunter host; no full UI polish.

## Verify before finishing
- `./gradlew assembleDebug` thinking: you cannot run Gradle here (no JVM on this
  VM) — the build runs on GitHub Actions after push. So: keep changes compiling
  by careful review, add the CI test step, and make sure no file references a
  missing symbol. After the parent pushes + builds, the parent reports CI results.
- When you finish, list every file you modified/added, the exact behaviour change
  per file, and anything you could NOT verify (be explicit — do not claim the
  build, the tests, or voice works until CI + Salman's phone test say so).
