# Mira AI — Implementation Plan

**Target Application:** Native Android Voice & AI Assistant  
**Namespace / Package:** `io.github.ryoaonetsuki.mira`  
**App Name:** Mira AI  
**Developer:** Salman Biswas  
**Date:** 2026-11-05  

---

## 1. Architecture & Tech Stack

- **Platform:** Native Android (Single Module `:app`)
- **Language & Tooling:** Kotlin 2.2.20, Java 17, Gradle 8.14.3, Android Gradle Plugin (AGP) 8.11.1
- **Target SDK:** 36 (Android 16 preview) | **Compile SDK:** 36 | **Min SDK:** 26 (Android 8.0 Oreo)
- **UI Toolkit:** Jetpack Compose (Compose BOM 2024.10.01), Material 3 (`androidx.compose.material3:material3`)
- **Navigation:** Jetpack Navigation Compose 2.8.3 (`androidx.navigation:navigation-compose`)
- **Architecture Pattern:** MVVM (Model-View-ViewModel) with Kotlin Coroutines (`StateFlow` & `SharedFlow`)
- **Local Persistence:** 
  - Room 2.6.1 (`androidx.room:room-runtime`, `androidx.room:room-ktx`) for Chats, Memories, Rules, Notifications, Watchlist, SOS Contacts, Posts
  - EncryptedSharedPreferences (`androidx.security:security-crypto:1.1.0-alpha06`) for BYOK Gemini API key & connector tokens
  - DataStore Preferences 1.1.1 for user preferences (theme, persona, voice, toggles)
- **Networking & Real-Time Audio:**
  - OkHttp 4.12.0 for WebSocket (`BidiGenerateContent`) & REST calls
  - `AudioRecord` (16kHz mono 16-bit PCM) for continuous microphone uplink
  - `AudioTrack` (24kHz mono 16-bit PCM) for instant low-latency model voice playback
- **Foreground Service:** `MicService` foreground service with microphone type + persistent notification
- **Image & Web Loading:** Coil 2.7.0 (`io.coil-kt:coil-compose`) + Android WebView

---

## 2. Dependency Versions (Maven Central Verified)

| Component | Artifact | Version |
|---|---|---|
| AGP | `com.android.tools.build:gradle` | `8.11.1` |
| Kotlin | `org.jetbrains.kotlin:kotlin-gradle-plugin` | `2.2.20` |
| Compose Plugin | `org.jetbrains.kotlin.plugin.compose` | `2.2.20` |
| KSP | `com.google.devtools.ksp` | `2.2.20-2.0.4` |
| Compose BOM | `androidx.compose:compose-bom` | `2024.10.01` |
| Material 3 | `androidx.compose.material3:material3` | (BOM) |
| Material Icons Extended | `androidx.compose.material:material-icons-extended` | (BOM) |
| Navigation Compose | `androidx.navigation:navigation-compose` | `2.8.3` |
| Lifecycle ViewModel | `androidx.lifecycle:lifecycle-viewmodel-compose` | `2.8.6` |
| Activity Compose | `androidx.activity:activity-compose` | `1.9.3` |
| OkHttp | `com.squareup.okhttp3:okhttp` | `4.12.0` |
| Room DB | `androidx.room:room-runtime`, `room-ktx` | `2.6.1` |
| Room Compiler (KSP) | `androidx.room:room-compiler` | `2.6.1` |
| Security Crypto | `androidx.security:security-crypto` | `1.1.0-alpha06` |
| DataStore | `androidx.datastore:datastore-preferences` | `1.1.1` |
| Coil | `io.coil-kt:coil-compose` | `2.7.0` |
| Biometric | `androidx.biometric:biometric` | `1.1.0` |
| Coroutines Android | `org.jetbrains.kotlinx:kotlinx-coroutines-android` | `1.8.1` |

---

## 3. Gemini Live Protocol & Voice Pipeline

### 3.1 WebSocket Connection
- **Endpoint:** `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key={API_KEY}`
- **Fallback Ladder:**
  1. `models/gemini-3.8-live` (Mind)
  2. `models/gemini-2.5-flash-native-audio-preview-12-2025` (Soul)
  3. `models/gemini-3.1-flash-live-preview` (Swift)

### 3.2 Handshake Sequence
1. Send initial `setup` payload:
```json
{
  "setup": {
    "model": "models/gemini-3.8-live",
    "generationConfig": {
      "responseModalities": ["AUDIO"],
      "speechConfig": {
        "voiceConfig": {
          "prebuiltVoiceConfig": {
            "voiceName": "Aoede"
          }
        }
      }
    },
    "systemInstruction": {
      "parts": [{ "text": "{PERSONA_PROMPT} {CUSTOM_RULES}" }]
    },
    "inputAudioTranscription": {},
    "outputAudioTranscription": {}
  }
}
```
2. **AWAIT** server `{"setupComplete":{}}` response. Do not stream microphone audio until this frame is received.
3. Start streaming AudioRecord PCM16 frames (16kHz, mono, chunks of ~1600 samples / 100ms) encoded in Base64:
```json
{
  "realtimeInput": {
    "audio": {
      "data": "<base64_data>",
      "mimeType": "audio/pcm;rate=16000"
    }
  }
}
```

### 3.3 Audio Output & Barge-In
- Receive server chunks: `serverContent.modelTurn.parts[].inlineData.data` (`audio/pcm;rate=24000`).
- Feed decoded Base64 PCM bytes directly into `AudioTrack` write buffer (24,000 Hz, mono, 16-bit).
- On server `interrupted: true` or detected local user speech energy, **instantly flush and pause `AudioTrack`** to provide zero-lag barge-in interruption.
- Manage turn lifecycle: `turnComplete: true`, `interrupted: true`, auto-reconnect once on network drop.

---

## 4. Complete Screen Inventory & Routes

| Screen Route | Description |
|---|---|
| `splash` | Animated logo, key validation, directs to `setup` or `home` |
| `setup` | 2-step setup: BYOK Gemini Key test & mandatory + optional Permissions wizard |
| `home` | Animated Canvas gradient orb (mic RMS responsive), weather, date, mood cards, quick chips, talk button |
| `chat` | Full-featured LLM text chat with conversation history in Room |
| `memories` | CRUD for learned facts and long-term memory |
| `rules` | Custom user rules injected into prompt contexts |
| `documents` | App-local text documents file I/O & sharing |
| `photos` | MediaStore gallery viewer |
| `code_projects` | Gemini code generator & on-device HTML/JS WebView previewer |
| `whiteboard` | Drawing canvas with color/size, undo, clear, export to gallery |
| `vibes` | Music mood launcher (Spotify, YouTube Music, YouTube) |
| `markets` | Yahoo Finance free quotes watchlist & refresh |
| `marketing` | Business post generator via Gemini + scheduled posts |
| `pc_phone` | 6-digit sync pairing code screen & companion status |
| `extensions` | Built-in Android intent macros (WhatsApp, YouTube, Maps, Call, SMS, DDG) + runner |
| `settings` | Settings Hub with categorization |
| `settings_maya` | Persona (Mira / Orion), name, 30+ languages, driving mode, sleep timeout |
| `settings_personal` | User name, gender, phone, Gemini key test/change, favorite music |
| `settings_secret` | Friendly vs Romantic mode with age-gate prompt |
| `settings_voice` | Normal mode minds (Swift/Soul/Mind), 30 voices, Deepgram key, diagnostics |
| `settings_advanced` | 7 theme palettes, orb style, touch guard, screen lock, SOS, Face Lock (Biometric) |
| `settings_connectors`| Real token verification for GitHub, Notion, Telegram, Spotify, Todoist, Linear |
| `settings_email` | Send-as config, Gemini email composer with `ACTION_SENDTO` |
| `settings_messages` | WhatsApp auto-reply rules & NotificationListenerService status |
| `settings_social` | Social captions, handle, and daily stories |
| `settings_optional` | Image generation provider, search provider, Maps key |
| `settings_skills` | Installed skills toggle list |
| `settings_backup` | SAF JSON export & import of all memories, rules, and chats |
| `about` | Version, logo, "Developed by Salman Biswas", open-source license inventory |
| `image_gen` | Pollinations.ai free image generator with Coil display & save |
| `web_search` | DuckDuckGo web search results viewer |
| `browser` | In-app WebView browser with URL bar, tabs, reload, desktop toggle |
| `notifications` | Local notification event log persisted in Room |
| `update` | GitHub Releases check for `ryoaonetsuki/mira-ai-voice-assistant` |

---

## 5. File Tree

```
mira-final/
├── .github/
│   └── workflows/
│       └── build-apk.yml
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml
│           ├── assets/
│           ├── res/
│           │   ├── drawable/
│           │   ├── mipmap-anydpi-v26/
│           │   ├── mipmap-hdpi/
│           │   ├── mipmap-mdpi/
│           │   ├── mipmap-xhdpi/
│           │   ├── mipmap-xxhdpi/
│           │   ├── mipmap-xxxhdpi/
│           │   └── values/
│           │       ├── colors.xml
│           │       ├── strings.xml
│           │       └── themes.xml
│           └── java/io/github/ryoaonetsuki/mira/
│               ├── MiraApplication.kt
│               ├── MainActivity.kt
│               ├── backend/
│               │   ├── RemoteConfig.kt
│               │   ├── LocalRemoteConfig.kt
│               │   └── PushRegistrar.kt
│               ├── data/
│               │   ├── local/
│               │   │   ├── MiraDatabase.kt
│               │   │   ├── dao/
│               │   │   │   ├── ChatDao.kt
│               │   │   │   ├── MemoryDao.kt
│               │   │   │   ├── RuleDao.kt
│               │   │   │   ├── NotificationDao.kt
│               │   │   │   ├── MarketDao.kt
│               │   │   │   ├── PostDao.kt
│               │   │   │   └── SosContactDao.kt
│               │   │   └── entities/
│               │   │       ├── ChatEntities.kt
│               │   │       ├── MemoryEntity.kt
│               │   │       ├── RuleEntity.kt
│               │   │       ├── NotificationEntity.kt
│               │   │       ├── MarketEntity.kt
│               │   │       ├── PostEntity.kt
│               │   │       └── SosContactEntity.kt
│               │   ├── preferences/
│               │   │   ├── SecurePreferences.kt
│               │   │   └── UserPreferences.kt
│               │   └── model/
│               │       ├── Persona.kt
│               │       ├── VoiceCatalog.kt
│               │       └── Macro.kt
│               ├── service/
│               │   ├── MicService.kt
│               │   └── MiraNotificationListener.kt
│               ├── voice/
│               │   ├── GeminiLiveClient.kt
│               │   ├── AudioRecorder.kt
│               │   └── AudioPlayer.kt
│               ├── network/
│               │   ├── GeminiRestClient.kt
│               │   ├── ConnectorClient.kt
│               │   ├── WeatherClient.kt
│               │   ├── FinanceClient.kt
│               │   └── GitHubReleaseClient.kt
│               ├── ui/
│               │   ├── theme/
│               │   │   ├── Color.kt
│               │   │   ├── Theme.kt
│               │   │   └── Type.kt
│               │   ├── components/
│               │   │   ├── AnimatedOrb.kt
│               │   │   ├── AppTopBar.kt
│               │   │   ├── AppDrawer.kt
│               │   │   └── CommonUi.kt
│               │   ├── navigation/
│               │   │   ├── Screen.kt
│               │   │   └── NavGraph.kt
│               │   └── screens/
│               │       ├── SplashScreen.kt
│               │       ├── SetupScreen.kt
│               │       ├── HomeScreen.kt
│               │       ├── ChatScreen.kt
│               │       ├── MemoriesScreen.kt
│               │       ├── RulesScreen.kt
│               │       ├── DocumentsScreen.kt
│               │       ├── PhotosScreen.kt
│               │       ├── CodeProjectsScreen.kt
│               │       ├── WhiteboardScreen.kt
│               │       ├── VibesScreen.kt
│               │       ├── MarketsScreen.kt
│               │       ├── MarketingScreen.kt
│               │       ├── PcPhoneScreen.kt
│               │       ├── ExtensionsScreen.kt
│               │       ├── ImageGenScreen.kt
│               │       ├── WebSearchScreen.kt
│               │       ├── BrowserScreen.kt
│               │       ├── NotificationsScreen.kt
│               │       ├── UpdateScreen.kt
│               │       └── settings/
│               │           ├── SettingsHubScreen.kt
│               │           ├── MayaSettingsScreen.kt
│               │           ├── PersonalSettingsScreen.kt
│               │           ├── SecretSettingsScreen.kt
│               │           ├── VoiceEngineScreen.kt
│               │           ├── AdvancedSettingsScreen.kt
│               │           ├── ConnectorsSettingsScreen.kt
│               │           ├── EmailSettingsScreen.kt
│               │           ├── MessagesSettingsScreen.kt
│               │           ├── SocialSettingsScreen.kt
│               │           ├── OptionalSettingsScreen.kt
│               │           ├── SkillsSettingsScreen.kt
│               │           ├── BackupSettingsScreen.kt
│               │           └── AboutScreen.kt
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
└── README.md
```
