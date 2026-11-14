# Mira AI — Native Android Voice Assistant

Mira AI is a real-time voice assistant for Android, built natively in **Kotlin** and
**Jetpack Compose**. Talk to her like a person — she keeps talking like a real human while
she does the work on your phone, in the background, in one continuous voice session.

Developed by **Salman Biswas** — [github.com/ryoaonetsuki](https://github.com/ryoaonetsuki).

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
  directly. Recorded macros, learned skills, and admin-defined macros (from Supabase) all
  run from the Skills screen.
- **Text chat** — persistent on-device history, grouped by date, with Think / Search /
  Image modes. Image mode generates images (FLUX via a Cloudflare Worker) — only when the
  Image button is on, using your full message as the prompt.
- **Vision** — point the camera and ask; answers are text-only.
- **Memory** — what she learns about you stays on the phone only, never on a server.
- **Websites** — ask her to build a multi-page static website; she saves it and serves it
  on localhost in her own browser.
- **3D orb** — the home screen is a single amplitude-driven orb; dark cyan-to-violet theme,
  Fluent icons throughout.

## Setup

1. Install the APK (see [Building](#building) — GitHub Actions is the reliable path).
2. Open Mira and complete setup: paste your **Gemini API key** (get one free from
   [Google AI Studio](https://aistudio.google.com/)) and grant the permissions she needs:
   Accessibility Service, Display over other apps, Notifications, and File Management.
3. Tap the big Voice button and talk. Replies are in your chosen language (Bangla by
   default; UI stays in English).

Optional: **Settings → Tap numbers** shows numbered badges on tappable elements. It is off
by default — Mira works directly without it.

## Admin backend

Supabase project `mira` backs the admin panel at
[https://mira-admin-8qg.pages.dev/](https://mira-admin-8qg.pages.dev/) (licence off by
default; banner, kill-switch, maintenance mode, forced update, and macros as JSON).
Conversation history is never sent to Supabase — it stays on the phone.

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
    clients, notifications, Supabase/licence
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
