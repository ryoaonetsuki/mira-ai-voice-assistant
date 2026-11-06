# Mira AI — Native Android Voice Assistant

Mira AI is a premium, real-time voice and multimodal AI assistant built natively in **Kotlin** and **Jetpack Compose** for Android. Designed as an intelligent, empathetic companion and daily productivity hub, Mira features zero-lag conversational audio streaming powered directly by Google's **Gemini Live WebSocket API**.

Developed by **Salman Biswas**.

---

## Key Highlights

- **Streaming Server Voice & Gemini Live:**
  - **Server Voice (Round 9 default):** High-speed bidirectional streaming via Cloudflare Worker `mira-server-voice`. Rolling Groq Whisper (`whisper-large-v3-turbo`) partial transcription (~1s), Groq `gpt-oss-120b` streaming LLM with automatic Gemini fallback, sentence-chunked Gemini TTS (`gemini-3.8-flash-tts` → `gemini-3.8-flash-lite-tts`), and instant client-side barge-in.
  - **Direct Gemini Live:** Direct bidirectional WebSocket connection to Gemini Live (`BidiGenerateContent`) as secondary voice engine.
  - **Uplink:** Microphone capture at 16,000 Hz 16-bit mono PCM streamed in real-time.
  - **Downlink:** Audio playback at 24,000 Hz 16-bit mono PCM via low-latency `AudioTrack`.
  - **Zero-Latency Barge-In:** Immediate hardware buffer flush on speech interruption.
  - **Resilient Fallback Ladder:** Server Voice → Gemini Live → SpeechRecognizer fallback.
  - **Foreground Service:** Dedicated `MicService` with microphone foreground type and ongoing notification to prevent Android background termination.
- **BYOK (Bring Your Own Keys):** Safe and 100% private. Enter your own free API keys with personal quotas:
  1. **Gemini API Key:** From Google AI Studio (powers conversational intelligence and natural TTS voices).
  2. **Groq API Key:** Free from console.groq.com (powers ultra-fast speech-to-text and low-latency responses).
  Keys are stored encrypted on-device via `EncryptedSharedPreferences` backed by Android Keystore (AES256-GCM), passed only in request headers, never logged, and never stored on servers.
- **Interactive Animated Orb:** Canvas-rendered gradient orb responsive to microphone RMS amplitude, speaking shimmer phase, idle breathing, and connecting rotations.
- **Dual Personas & 30 Gemini Voices:**
  - Personas: **Mira** (warm, empathetic, lively companion) and **Orion** (crisp, focused machine intelligence).
  - Voice catalogue: All 30 official Gemini voices (13 female, 17 male) with style descriptors, plus 30+ supported spoken languages.
- **Full Productivity & Creation Suite:**
  - **Chat:** Dedicated Gemini text conversation screen with persistent history in Room DB.
  - **Memories:** Editable and searchable long-term learned memory facts with biometric protection.
  - **Mira Rules:** Custom behavior instructions injected into every Live voice and chat prompt.
  - **Documents:** Local text file management, viewing, and native Android intent sharing.
  - **Photos:** MediaStore image gallery grid and fullscreen inspection.
  - **Code Projects:** Full-stack website generation with on-device HTML/JS execution via WebView.
  - **Whiteboard:** Touch drawing canvas with color selection, stroke width, undo, and direct MediaStore gallery export.
  - **Vibes & Music:** Mood launchers for Spotify, YouTube Music, and YouTube.
  - **Markets:** Free Yahoo Finance quote watchlist and live tracking.
  - **Marketing:** AI caption and business post generator with local scheduling queue.
  - **Extensions & Macros:** On-device runtime executing Android intents for WhatsApp, YouTube, Maps, Calls, SMS, and DuckDuckGo search.
  - **Tools Suite:** Free AI image generation (Pollinations), private web search, in-app tabbed browser, and update manager.
- **Advanced Security & Theming:**
  - **Face Lock:** Biometric & device credential gating for sensitive memories and conversations.
  - **Theme Palettes:** 7 selectable themes (*Midnight, Obsidian, Nocturne, Ember, Abyss, Rosewood, Daylight*).
  - **WhatsApp Auto-Reply:** `NotificationListenerService` responding to incoming chats with customizable templates.

---

## Tech Stack & Architecture

- **Operating System:** Android (minSdk 26, targetSdk 36, compileSdk 36)
- **Language:** Kotlin 2.2.20
- **Android Gradle Plugin (AGP):** 8.11.1
- **Gradle:** 8.14.3
- **Java:** JDK 17
- **UI:** Jetpack Compose (Material 3, Compose BOM `2024.10.01`)
- **Navigation:** Jetpack Navigation Compose `2.8.3`
- **Database:** Room `2.6.1` with Kotlin Coroutines Flow
- **Security:** AndroidX Security Crypto `1.1.0-alpha06` (EncryptedSharedPreferences) & Biometric `1.1.0`
- **Networking:** OkHttp `4.12.0` (WebSocket & REST)
- **Image Loading:** Coil Compose `2.7.0`

---

## Building the Project

Ensure you have JDK 17 installed:

```bash
# Clone the repository
git clone https://github.com/ryoaonetsuki/mira-ai-voice-assistant.git
cd mira-ai-voice-assistant

# Build the Debug APK
./gradlew assembleDebug --stacktrace
```

The compiled APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## License

This project is licensed under the Apache License 2.0.
