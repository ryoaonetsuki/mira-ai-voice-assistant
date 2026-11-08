# Mira AI — Round 4 Spec (2026-11-06)

Repo: https://github.com/ryoaonetsuki/mira-ai-voice-assistant — package io.github.ryoaonetsuki.mira.
Local tree: ~/workspace/mira-final/ (push via tools/push.py Contents API; do NOT use git push).
Build gate: GitHub Actions workflow .github/workflows/build-apk.yml must be GREEN; debug APK
downloaded to ~/workspace/user/files/mira-ai-debug.apk, verified with aapt2
(package io.github.ryoaonetsuki.mira, versionName 1.0.0).

## Verified diagnosis (confirm in code as you implement)
- FallbackVoiceSession.onError restarts forever (scheduleRestart 300ms) on ERROR_NETWORK /
  ERROR_SERVER / ERROR_NO_MATCH / ERROR_SPEECH_TIMEOUT; onError callback never fires for those.
  UI stays LISTENING forever. PRIME SUSPECT for stuck LISTENING.
- FallbackVoiceSession.onRmsChanged is EMPTY — no mic amplitude in fallback, orb never moves.
- Live V1 setup has outputAudioTranscription only, no inputAudioTranscription — no "Heard:"
  transcript can appear even if Live connects (V2 already has it, line ~358).
- No visible indication of WHICH voice path is active (Live vs fallback) or what stage it is in.
- HomeScreen shows errors only as a Toast when state==ERROR; fallback path sets
  state=LISTENING while storing errors in a flow Home never displays.

## Phase 1 — VOICE (top priority)
1a. Visible voice-diagnostics card on Home, shown ONLY while a session is active
    (liveState != IDLE): Path (LIVE / TAP-TO-TALK), stage (Connecting… / Waiting for setup /
    Listening / Recognising / Thinking / Speaking), live mic-level bar driven by
    MicService.micAmplitude, "Heard: <transcript>" line, audio chunks sent/received counts,
    last error line when one exists. Home stays clean when idle. Keep existing layout:
    cards stay, big orb, small status line, chips pinned just above bottom nav.
    Expose what Home needs: add MicService StateFlows for voice path (isFallbackMode),
    audio chunks sent/received (GeminiLiveClient already counts them privately —
    add public getters; fallback path: count recognizer turns / TTS replies as applicable,
    or 0 with a clear label). Add a MicService "voice stage" flow if cleaner than
    deriving stage from LiveState + path in Home.
1b. Fix fallback silent loop: add onAmplitude callback to FallbackVoiceSession; in
    onRmsChanged normalize rmsdB to 0..1 (rmsdB range is roughly -2..10; clamp) and feed
    MicService._micAmplitude. Count consecutive SpeechRecognizer errors; after 3
    consecutive errors of the same kind, STOP restarting and surface the real error visibly
    (set LiveState.ERROR with the message so toast + diagnostics line show it; reset the
    counter on any successful onResults / onReadyForSpeech). Surface
    isRecognitionAvailable == false immediately as ERROR, not LISTENING.
    (Note: FallbackVoiceSession.start() already calls onError when unavailable — make
    MicService's onError handler in startFallbackVoiceSession set LiveState.ERROR and
    _lastVoiceDiagnostic, not just store the message.)
1c. Add inputAudioTranscription:{} to Live V1 setup (GeminiLiveClient.sendSetupMessage,
    alongside outputAudioTranscription) — harmless, matches V2.
1d. Keep Live primary + fallback automatic. Do not remove anything that works.

## Phase 2 — VOICE SEARCH ACKNOWLEDGEMENT (both paths)
When the user asks Mira to search something, or asks about something Mira does not know /
has no latest news about: Mira must FIRST SAY ALOUD the acknowledgement in the user's
SELECTED language, THEN run the web search, THEN speak the FINAL answer. Never stay
silent while searching, never guess when it does not know.
Localize via GeminiLiveClient.mapLanguageToBcp47 prefix: bn "এক মিনিট, আমি খুঁজে বলছি",
en "One moment, let me search and tell you", hi "एक मिनट, मैं खोजकर बताती हूँ",
ur "ایک منٹ، میں تلاش کر کے بتاتی ہوں", ar "لحظة واحدة، سأبحث وأخبرك", es "Un momento, déjame buscar y decírtelo",
fr "Un instant, je cherche et je vous dis", de "Einen Moment, ich suche es für dich",
ja "ちょっと待って、調べてお伝えします", ko "잠시만요, 검색해서 알려드릴게요",
pt "Um momento, deixe-me pesquisar e te contar", ru "Минутку, я поищу и расскажу",
tr "Bir dakika, arayıp söyleyeyim", id "Sebentar, saya cari dan beri tahu",
vi "Đợi một chút, để tôi tìm rồi nói cho bạn", th "รอสักครู่ เดี๋ยวฉันค้นหาแล้วบอก",
ta "ஒரு நிமிடம், தேடிச் சொல்கிறேன்", default English.
Put the map in ONE shared place (e.g. network/SearchAcknowledgement.kt or inside
MiraChatSearchHeuristic companion) and use it from both paths.
- Fallback path: in MicService.handleFallbackFinalText / generateAndSpeakRestReply, detect
  search intent (MiraChatSearchHeuristic.isTimeSensitive OR explicit search words incl.
  Bangla/Roman-Bangla: search, খুঁজে, khoje/khuje, google, "search koro", "khoje dekho").
  If search needed: speak the acknowledgement FIRST via Gemini TTS (speakFallbackReply,
  or Android TTS if Gemini TTS fails), then run FreeWebSearch + REST with the results,
  then speak the final answer.
- Live path: in MicService onTranscript(isUser=true), when the transcript is search-intent,
  immediately send a clientContent text message instructing the model to say the
  acknowledgement aloud in the selected language, then inject search results as today
  (existing liveSearchJob). Add this rule to the Live system prompt (built in
  startLiveVoiceSession). Normal voice talk stays fast.
- Add isSearchRequest(text) helper to MiraChatSearchHeuristic (explicit search words +
  isTimeSensitive) and reuse it in both paths.

## Phase 3 — CHAT FILE UPLOAD (+) BUTTON
- Add a + button in the ChatScreen.kt input row, next to the text field. Tapping opens a
  file picker (ActivityResultContracts.OpenDocument).
- Verify readability FIRST with the Gemini API — only offer file types Mira can actually read:
  * Images (JPEG/PNG/WebP/GIF): send as inlineData base64 in generateContent.
  * Text files (.txt/.md/.csv/.json/.html/.kt/.js/.py etc.): read the text locally, include
    in the prompt as a text part.
  * PDF: upload via the Gemini Files API (POST https://generativelanguage.googleapis.com/upload/v1beta/files
    with x-goog-api-key header, multipart), then reference with fileData in generateContent.
    If Files API upload is not workable from the app, render PDF pages as images via
    android.graphics.pdf.PdfRenderer and send as inlineData.
  If a type cannot be read, DO NOT offer it in the picker (restrict MIME types to
  verified-readable ones).
- Save uploaded files to Mira/Files/ (existing pattern: WebsiteProjectManager.saveSingleCodeFile
  uses context.getExternalFilesDir(null)/Mira/Files). Show the friendly message + ONE open
  link in chat (existing WebsiteProjectManager pattern: "Creating your file..." → friendly
  message + link). Do not dump raw file content into chat.
- Implement the upload in a new network/GeminiFilesClient.kt; wire fileData/inlineData into
  GeminiRestClient.generateChatResponse + generateChatResponseStreaming (add an optional
  `attachments: List<ChatAttachment> = emptyList()` parameter; do not break existing calls).
  Define ChatAttachment in GeminiRestClient.kt (mimeType, base64Data for inlineData,
  fileUri for fileData, textContent for text files). Attachments attach to the LAST user
  turn in contents. Respect server-proxy mode: if useServerProxy, send attachments through
  the edge function payload only if it supports them, else fall back to direct API for
  attachment messages (document the choice in code).
- Picker MIME types: image/jpeg, image/png, image/webp, image/gif, text/plain,
  text/markdown, text/csv, application/json, text/html, text/* (code files), application/pdf.
  Cap image size (downscale/compress to max ~1568px / ~1MB base64) before sending.

## Phase 4 — CHAT TWO REAL TOGGLES (Web Search + Deep Thinking)
- In ChatScreen add TWO visible on/off toggles (compact row above the input bar):
  (a) Web Search, (b) Deep Thinking. Persist both in UserPreferences (new boolean keys;
  defaults: Web Search ON, Deep Thinking OFF). Read them with .first() in the send handler.
- They must ACTUALLY gate behaviour:
  * Web Search OFF: enableGrounding=false AND no FreeWebSearch call anywhere in that
    request path — including the build-research block inside prepareChatInput
    (GeminiRestClient.kt ~390-412, currently searches unconditionally for build requests —
    gate it on the resolved shouldSearch). When OFF, zero search calls are made.
  * Web Search ON: heuristic-gated search as today.
  * Deep Thinking ON: enableThinking=true, thinkingBudget 8192 (already in code), plus a
    "think step-by-step, research thoroughly" instruction appended to the system prompt
    (slow is fine).
  * Deep Thinking OFF: enableThinking=false, short/fast answers instruction appended.
- Apply the gates to BOTH generateChatResponseStreaming and the non-streaming
  generateChatResponse fallback calls in ChatScreen.kt (all three call sites: streaming,
  empty-stream fallback, exception fallback).
- Voice fallback keeps its existing fast behaviour (gemini-3.5-flash-lite, no thinking)
  except for the Phase-2 acknowledgement.

## Phase 5 — ICONS: MATERIAL → LUCIDE, APP-WIDE
- Remove Material icons from EVERYWHERE, switch to Lucide (premium thin-line free set).
  Scope: 187 `Icons.*` usages across 36 files, ~105 distinct icons (all
  Icons.Rounded.* — no Filled/Outlined variants exist; re-grep to confirm exact list).
- Remove `androidx.compose.material:material-icons-extended` from app/build.gradle.kts once
  nothing uses it.
- Approach (Lucide has no official Compose artifact): download the official Lucide SVGs
  (ISC licence, lucide GitHub repo) for the icons needed; write a small Python converter
  (SVG path/circle/rect/line/polyline/polygon → Compose ImageVector builder code,
  24x24 viewport, strokeWidth 2, round caps/joins, no fill) and generate
  ui/icons/Lucide.kt with one val per icon. Map each Material icon to the closest Lucide
  equivalent and VALIDATE every Lucide name against the official lucide icon list
  (icons.json from the repo) — no invented names. Replace all `Icons.Rounded.X` usages
  with `Lucide.x` (same ImageVector API, keep existing tints/sizes). Remove the
  `import androidx.compose.material.icons.*` lines. No emojis anywhere.
- Verify: zero `Icons.` references remain, build compiles, APK still small.
- Keep the converter script at tools/lucide-convert.py for reproducibility.

## KEEP INTACT (rounds 1–3)
Bangla default primary language + one-language-only replies; chat model gemini-3.8-flash →
3.6-flash → 3.5-flash-lite; voice model gemini-3.8-live; real Accessibility macros; real
connectors; voice previews; Supabase backend with licence OFF by default; clean copy
(no "runs on Gemini / Gemini API" UI mentions); no PC connect; Home layout (cards stay,
big orb, small status line, chips pinned just above bottom nav); file-permission gate in
setup; date/time injection from device clock; embedded free search (SearXNG → DDG Lite).

## Rules for the implementer
- Do NOT change anything outside the phase you are assigned. Do not "improve" other code.
- After each phase, run a local compile check if possible (./gradlew assembleDebug);
  report exact compiler errors if it fails — do not silently skip.
- No emojis in UI or code comments. No fake/placeholder behaviour.
- Never claim voice works without a phone test — code-verified only.
