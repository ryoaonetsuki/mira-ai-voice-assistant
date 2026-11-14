<p align="center">
  <img src="assets/logo-chosen.png" alt="Mira AI" width="120" />
</p>

<h1 align="center">Mira AI</h1>

<p align="center">
  <b>A real-time voice assistant for Android — talk to her like a person.</b><br />
  She keeps talking like a real human while she does the work on your phone,
  in the background, in one continuous voice session.
</p>

<p align="center">
  <a href="https://github.com/ryoaonetsuki/mira-ai-voice-assistant/releases"><img src="https://img.shields.io/github/v/release/ryoaonetsuki/mira-ai-voice-assistant" alt="Release" /></a>
  <a href="https://github.com/ryoaonetsuki/mira-ai-voice-assistant/releases/download/v1.0.0/mira-ai-debug.apk"><img src="https://img.shields.io/badge/Download-APK-3DDC84?logo=android" alt="Download APK" /></a>
  <img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License: Apache 2.0" />
  <a href="https://github.com/ryoaonetsuki/mira-ai-voice-assistant/actions/workflows/build-apk.yml"><img src="https://img.shields.io/github/actions/workflow/status/ryoaonetsuki/mira-ai-voice-assistant/build-apk.yml?branch=main" alt="Build status" /></a>
  <img src="https://img.shields.io/badge/Android-26%2B-3DDC84?logo=android" alt="Android 26+" />
  <img src="https://img.shields.io/badge/Kotlin-2.2.20-7F52FF?logo=kotlin" alt="Kotlin 2.2.20" />
</p>

<p align="center">Developed by <b>Salman Biswas</b> — <a href="https://github.com/ryoaonetsuki">github.com/ryoaonetsuki</a></p>

---

## What Mira does

- **Mira Live voice** — native speech-to-speech voice calls with barge-in (interrupt her
  mid-sentence, she listens). One voice, one call, never stops or restarts while work runs.
  Bring your own Gemini API key from Google AI Studio (BYOK, encrypted on-device).
- **Two personas, 30 voices** — Mira (female, default) and Orion (male); pick any of the 30
  Mira voices, with previews in Settings.
- **Phone control by voice** — tell her to open apps, tap, type, scroll, install, search, or
  do multi-step tasks in any app. She reads the whole screen (accessibility tree + vision),
  understands what each button does, re-reads the screen after every action, and finishes
  only when the goal state is visible.
- **Skills & macros** — do a task once and it becomes a skill: next time she replays it
  directly. Recorded macros, learned skills, and admin-defined macros all run from the
  Skills screen.
- **Text chat** — persistent on-device history, grouped by date, with Think / Search /
  Image modes. Image mode generates images (FLUX) — only when the Image button is on,
  using your full message as the prompt.
- **Vision** — point the camera and ask; answers are text-only.
- **Memory** — what she learns about you stays on the phone only, never on a server.
- **Websites** — ask her to build a multi-page static website; she saves it and serves it
  on localhost in her own browser.
- **3D orb** — the home screen is a single amplitude-driven orb; dark cyan-to-violet theme,
  Fluent icons throughout.

## Download

### ⬇️ Download Mira AI v1.0.0

[![Download APK](https://img.shields.io/badge/Download-APK%20v1.0.0-3DDC84?style=for-the-badge&logo=android)](https://github.com/ryoaonetsuki/mira-ai-voice-assistant/releases/download/v1.0.0/mira-ai-debug.apk)

**[mira-ai-debug.apk — v1.0.0 (57.5 MB)](https://github.com/ryoaonetsuki/mira-ai-voice-assistant/releases/download/v1.0.0/mira-ai-debug.apk)** — install it directly on your phone. No account, no sign-up. Older releases are on the [Releases page](https://github.com/ryoaonetsuki/mira-ai-voice-assistant/releases).

## Setup

1. Install the APK (download it above, or see [Building](#building) — GitHub Actions is
   the reliable path).
2. Open Mira and complete setup: paste your **Gemini API key** (get one free from
   [Google AI Studio](https://aistudio.google.com/)) and grant the permissions she needs:
   Accessibility Service, Display over other apps, Notifications, and File Management.
3. Tap the big Voice button and talk. Replies are in your chosen language (Bangla by
   default; UI stays in English).

Optional: **Settings → Tap numbers** shows numbered badges on tappable elements. It is off
by default — Mira works directly without it.

## Tech stack

- Android only: minSdk 26, targetSdk 36, compileSdk 36; single module
- Kotlin 2.2.20, AGP 8.11.1, Gradle 8.14.3, JDK 17
- Jetpack Compose (Material 3), Room, CameraX, OkHttp, Coil
- WebRTC AEC3 (vendored, software echo cancellation — never hardware AEC)
- NDK 28.2 with 16 KB page-size-aligned native code

## Building

```bash
# Clone the repository
git clone https://github.com/ryoaonetsuki/mira-ai-voice-assistant.git
cd mira-ai-voice-assistant

# Build the debug APK (needs Android SDK 36)
./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`. The reliable build path is
GitHub Actions (`.github/workflows/build-apk.yml`) — every push to `main` builds and runs
the full unit-test suite, and the `app-debug` artifact is the APK to install.

## Project layout

- `app/src/main/java/io/github/ryoaonetsuki/mira/` — app code
  - `voice/` — Mira Live session, prompts, resilience
  - `automation/` — AccessibilityService control, skills/macros, perception, vision
  - `ui/` — Compose screens (Home, Voice, Chat, Vision, Skills, Settings)
  - `chat/`, `data/`, `network/`, `notifications/`, `backend/` — chat, Room DB, API
    clients, notifications, licence
- `app/src/main/cpp/` — vendored WebRTC audio processing (AEC3)
- `app/src/test/` — unit tests (612 tests, run in CI)
- `THIRD_PARTY_NOTICES.md` — every third-party component and its licence

## Third-party credits

Mira stands on open-source work — Tapilot (MIT), Mika (Apache-2.0), flutter_automate
(MIT), Jarvis (MIT), and the WebRTC audio-processing library (BSD-3-Clause), among others.
The full list with attributions is in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md),
and the same credits are shown in the app (Settings → About → Credits).

## Contributing

Contributions are accepted — see [CONTRIBUTING.md](CONTRIBUTING.md) for the fork / branch /
PR flow, code standards, and the rules for third-party code.

## License

Licensed under the [Apache License, Version 2.0](LICENSE). See [LICENSE](LICENSE) for the
full text.
