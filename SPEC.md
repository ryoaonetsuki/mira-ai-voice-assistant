# Mira AI — FINAL build spec (PAUSED 2026-11-05 ~22:58 +06, Salman: do NOT build until he says so)

## Status
- BUILD ON HOLD by Salman's order. Nothing pushed to the new repo, no build run started.
- `~/workspace/mira-final/` is EMPTY except this file — the Claude (opus-4-6-thinking) Phase-1 plan run was killed mid-flight on the HOLD order; PLAN.md was never written. On resume, re-run Phase 1 (Claude plan) then Phase 2 (Gemini implement via agy).
- HOLD AGAIN 2026-11-05 ~23:06: Salman said do NOT start anything now — he will first tell us what features the BACKEND will have, and only then does work start. Phase-1 run killed a second time mid-flight. Wait for his backend-feature list before any planning/implementation.

## Clarifications from Salman (2026-11-05 ~23:04, fold into the plan on resume)
1. NO 3D character at all. Home visual is ONLY a 3D orb, ChatGPT-style — round animated orb, not a character, not a filmed person.
2. UI design is OUR OWN — do NOT copy Maya's design. Only the FEATURES are like Maya's, placed in similar positions / similar style. Own colours, layout polish, Lucide icons, premium look.
3. Backend: plan must include a backend-add option — Firebase (or another backend) WITH AN ADMIN PANEL, so features/macros can be managed later. Design the app so a backend can be attached afterwards; do NOT build the full backend now, but structure for it (e.g. backend config / admin-panel placeholder in Settings).

## Identity
- App name: **Mira AI**. Repo: https://github.com/ryoaonetsuki/mira-ai-voice-assistant (PUBLIC, EMPTY, id 1447251466, created 2026-11-05).
- Package: `io.github.ryoaonetsuki.mira` (matches portfolio ryoaonetsuki.github.io/mira). Prototype repo ryoaonetsuki/mira-app (com.salman.mira) NOT yet deleted — delete only when Salman approves final work.
- Stack: native Kotlin + Jetpack Compose, single module, minSdk 26, targetSdk 36, compileSdk 36, AGP 8.11.1, Kotlin 2.2.20, Gradle 8.14.3.
- agy Pro login (mdsalmanhelp@gmail.com) verified OK 2026-11-05; binary ~/.local/bin/agy. Workflow: Phase 1 Claude plan, Phase 2 Gemini (gemini-3.8-flash-high) implement. agy writes the code, never Muse.

## Why native (his verdict, 2026-11-05 22:47)
- Flutter prototype built green (run 42063710118, APK ~154 MB at ~/workspace/user/files/mira-prototype-debug.apk) but FAILED on his phone: after adding Gemini key + Speak-to-Talk, Listening shows, speaking gets NO instant reply; Maya replies instantly. His verdict: Flutter can't do real-time voice — final must be native Kotlin + Compose, fast, small, smooth, no lag, premium.

## TWO working features
1. **Voice** — real-time Gemini Live WebSocket `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent`, model `gemini-3.8-live`, fallback `gemini-2.5-flash-native-audio-preview-12-2025`. 16kHz PCM mic in, 24kHz PCM out, barge-in, instant reply. BYOK free Gemini key, encrypted on-device (EncryptedSharedPreferences/Keystore), never hardcoded.
2. **Chat** — dedicated text chat screen that WORKS: Gemini text model via Generative Language API, same BYOK key, conversation history on screen.

## UI — mirror Maya page-by-page
- Full Maya page flow, same screens in same order, same options in same places: permission wizard → setup screens → home → voice → chat → settings and every other screen. Reference: ~/workspace/rea-analyses/maya/DEEP-REPORT.md + REPORT.md, and ~/workspace/rea-analyses/maya/UI-REFERENCE.md (separate agent collecting it — check if it exists on resume).
- What changes ONLY: icons (Lucide-style original, never Maya's icons/assets), colours (Mira's own scheme, not Maya's), fonts (Mira's own), branding "Mira AI". Original code, no copied assets/code/branding.
- Dark premium theme, animated voice orb on home, Lucide icons ONLY, NO emojis, UI text ALL English.
- Voice selection in Settings (Gemini voices: Aoede, Kore, Zephyr, Puck, Fenrir, Charon, Leda, etc.) + LONG spoken-language list (English default, Bangla, Hindi, many more).
- Settings: Custom Instruction field (who Mira is, friendly tone) sent as system instruction to Live session.

## Everything else — placeholders, NOTHING left out
- ALL Maya features present in UI as placeholders (tappable → short "Coming soon", NOT working, NO fake/demo content anywhere — no fake chats/history/invented items): modes (Fast / Deep / Smart), personas (Mira, Friday, Venom, Jarvis), Memory, Macros, WhatsApp auto-reply, Connectors (Todoist, Notion, GitHub, Telegram, Spotify, YouTube, Drive), Tools, image generation, search, driving mode, SOS, PC link, Face Lock, Watchman.
- ONE exception: REMOVE the 3D model / 3D character option entirely (his 22:57 correction).
- Long-term: ALL options will eventually WORK — backend to be attached later so users can build custom macros themselves.

## Build / push notes
- GitHub Actions workflow .github/workflows/build-apk.yml: checkout, Java 17, Gradle assembleDebug, upload artifact app-debug. Verify green; save APK to ~/workspace/user/files/mira-ai-debug.apk, verify package with aapt2.
- Push via PAT CLI (~/workspace/custom-connectors/custom.legacy-github-pat/bin/gh.py, PYTHONPATH=/opt/muse/skills/custom-connector-setup/bin). Full-tree push script ~/workspace/tools/push_mira.py is UNRELIABLE (transient HTTP 500 on blob POST) — prefer single-file Contents API PUTs with retry loop, or fix script with retries.
- Local Flutter build NOT usable as proof (Java proxy bug) — GitHub Actions is the proof.
