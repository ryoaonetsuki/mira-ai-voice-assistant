# Mira AI — Round 9 spec (REVISED 2026-11-06 ~21:34, Salman's scope upgrade):
# STREAMING server voice, NOT turn-based

Salman's order: Gemini Live has FAILED on his phone through 8 rounds (server
silence after HTTP 101 with his key). Build a WORKING voice path on OUR
server that feels as close to Live as possible — STREAMING, not turn-based.
Target ~0.5–1 s delay.

## HARD RULES
- **NO Deepgram — explicitly excluded by Salman.** No Deepgram code, key
  field, dependency, or reference anywhere. Remove the existing Deepgram
  backup card from `VoiceEngineScreen.kt` (and its SecurePreferences
  accessors if nothing else uses them).
- **Exactly TWO keys on the Setup start page:** (1) Gemini API key (as
  now), (2) Groq API key (new, "Get Free Key" → https://console.groq.com,
  free, no card). Nothing else on the start page. Every user enters their
  OWN two keys (BYOK) — each gets their own free quota.
- **Llama is GONE from Groq free tier — do NOT use any Llama model.**
- Keys NEVER logged anywhere (client or server), never persisted
  server-side (no KV, no storage of keys/audio/text on the Worker).

## Free-tier facts (verified 2026-11-06)
- Groq Whisper `whisper-large-v3-turbo`: 20 RPM / 2,000 RPD / 7,200
  audio-sec/hour / 28,800 audio-sec/day (8 hrs/day). No card.
- Groq `gpt-oss-120b` (chat completions, streaming): 30 RPM / 1,000 RPD /
  200K TPD. 500+ tok/s — this is the streaming LLM.
- Gemini free tier: no card. TTS ladder `gemini-3.8-flash-tts` →
  `gemini-3.8-flash-lite-tts` (same as `GeminiTtsClient.kt` today) — most
  natural voice, same 30 voices.

## Architecture (decided — do not redesign)
New Cloudflare Worker `mira-server-voice`
(`https://mira-server-voice.salman-dev.workers.dev`, account "Salman-Dev"
`5577ea99b7437d6aeadaf0a840c43ce2`, free tier). **One WebSocket stays open
between phone and Worker for the whole voice session** (like Live).
Phone streams mic audio in small chunks; partial transcripts stream back;
LLM tokens stream; TTS speaks the first sentence while the rest generates;
barge-in works.

### Wire protocol (phone ↔ Worker WS, JSON text frames + binary audio frames)
- Connect: `wss://mira-server-voice.salman-dev.workers.dev/voice/stream`
  with headers `x-mira-groq-key` + `x-mira-gemini-key` (per-session BYOK,
  in memory only for this socket, never logged). Missing either → close
  4401 with JSON `{"error":"Missing API key header"}`.
- Phone → Worker:
  - Binary frame: raw PCM16 16 kHz mono (100 ms frames, 3,200 bytes each —
    same chunk size AudioRecorder already emits). No WAV header on the
    wire; Worker wraps in WAV when calling Whisper.
  - `{"type":"start","language":"Bangla (Bengali)","voiceName":"Aoede","systemPrompt":"...","dateLine":"..."}`
    — sent once after connect (systemPrompt = MicService's existing
    cachedSystemPrompt construction: persona + tone + identity + language
    rule ONE-language-only + date line + custom instructions + rules).
  - `{"type":"proceed","searchContext": "..."|null}` — client's answer to
    a final transcript (see turn flow below).
  - `{"type":"barge-in"}` — user started speaking mid-reply.
  - `{"type":"stop"}` — end session.
- Worker → Phone:
  - `{"type":"partial","text":"..."}` — rolling interim transcript.
  - `{"type":"transcript","text":"..."}` — final transcript of the turn.
  - `{"type":"token","text":"..."}` — streamed LLM token (client appends
    to `_modelTranscript`).
  - `{"type":"audio","data":"<base64 PCM16 24kHz>","sentence":n}` — one
    TTS sentence's audio, sent as soon as it is ready.
  - `{"type":"reply-done"}` — turn finished, listen for next turn.
  - `{"type":"error","message":"..."}` — short, user-facing, no key material.

### Worker turn flow (server-side, `cloudflare-relay/server-voice-worker.js`)
1. Accumulate PCM frames into the current utterance buffer. Every 1.0 s
   of new speech, transcribe the rolling window (whole utterance so far,
   capped at the last ~10 s) via Groq Whisper
   (`POST https://api.groq.com/openai/v1/audio/transcriptions`, multipart
   `file` WAV + `model=whisper-large-v3-turbo` + `language=bn` only when
   language is Bangla, else omit) → send `partial` (client shows it,
   replaces previous partial — never appends partials).
2. Endpoint: Worker-side silence detection (RMS per 100 ms frame; 600 ms
   below threshold = end of turn; also honour client VAD endpoint if the
   client sends it — either trigger ends the turn, never both racing: the
   first endpoint wins, the other is ignored for that turn). On endpoint:
   final Whisper transcribe of the full utterance → `transcript` → WAIT
   for the client's `proceed` message (max 5 s; on timeout proceed with
   `searchContext=null`).
3. LLM streaming: Groq `gpt-oss-120b` chat completions
   (`POST https://api.groq.com/openai/v1/chat/completions`,
   `stream:true`, model `gpt-oss-120b`, messages =
   `[{role:system, content: systemPrompt + dateLine},
     {role:user, content: transcript + searchContext?}]`, temperature
   ~0.7, max_tokens ~600) — parse SSE `data:` lines, forward each
   `choices[0].delta.content` as `token`. On Groq LLM error (non-auth):
   fall back ONCE to Gemini `gemini-3.8-flash:streamGenerateContent?alt=sse`
   with the same messages, forwarding its text deltas as `token`.
   Auth error on either → `error` "API key problem — check your keys in
   Settings" and stop the turn (do not retry).
4. Sentence-chunked TTS: accumulate streamed tokens; on sentence boundary
   (`.`, `!`, `?`, `।`, newline — min ~25 chars, flush remainder at stream
   end) call Gemini TTS REST (`gemini-3.8-flash-tts` →
   `-lite-tts`, responseModalities AUDIO, prebuilt voice = voiceName)
   and send `audio` immediately. First sentence speaks while the LLM is
   still generating the rest. TTS calls run sequentially per sentence.
5. `reply-done` when the LLM stream ends AND the last sentence audio was
   sent. Clear the utterance buffer, listen for the next turn.
6. Barge-in: on `barge-in`, abort the in-flight LLM fetch AND any
   in-flight TTS fetch via AbortController, discard buffered tokens/audio,
   send nothing more for that turn, reset to listening. Also abort on
   socket close; never leave upstream fetches running after the socket dies.
7. Limits: utterance cap 60 s (force endpoint); message size guard on
   binary frames; Groq 20 s / Gemini chat 30 s / TTS 30 s fetch timeouts
   via AbortSignal.timeout. Upstream failures → short `error` frames
   (never echo upstream bodies — they may contain key material).
8. `GET /?action=health` → `{"service":"mira-server-voice",
   "version":"2.0.0","ok":true,"streaming":true}`. Non-WS GET without
   `action=health` → 426. CORS: allow any origin.
9. Worker file: `cloudflare-relay/server-voice-worker.js` (new; keep the
   existing `worker.js` Live relay untouched and live). Deploy via the
   Cloudflare API (`~/workspace/custom-connectors/custom.legacy-cloudflare/bin/cf.py`,
   `PUT /accounts/5577ea99b7437d6aeadaf0a840c43ce2/workers/scripts/mira-server-voice`),
   then live-verify: health 200, WS without keys → 4401/401.
   No test API key exists on this VM — do NOT invent one, do NOT use any
   key found anywhere; end-to-end verification is Salman's phone test.

### Client turn flow (`voice/ServerVoiceSession.kt`, NEW — the DEFAULT path)
- Owns ONE OkHttp WebSocket to the Worker for the whole session (created
  at session start, closed at session end). Sends `start` once with the
  keys in the upgrade headers (keys never in the JSON body).
- Mic: reuse existing `AudioRecorder` (16 kHz PCM16 mono) + existing
  `VoiceActivityDetector` (150 ms speech / 300 ms silence). Every mic
  chunk is BOTH (a) sent as a binary WS frame and (b) fed to VAD.
- On VAD speech-start while state == SPEAKING (Mira replying): barge-in —
  immediately `audioPlayer.stopImmediatelyAndFlush()`, send
  `{"type":"barge-in"}`, clear `_modelTranscript` partial, back to
  LISTENING. (Client-side barge-in is instant even before the server
  processes it.)
- On `partial`: `_userTranscript` = text, stage RECOGNISING.
- On `transcript`: `_userTranscript` = final text. Then the search
  handshake: if `MiraChatSearchHeuristic.isSearchRequest(text)` → speak
  the acknowledgement FIRST via the existing `GeminiTtsClient`
  (phrase from `SearchAcknowledgement.getPhrase(language)` — "এক মিনিট,
  আমি খুঁজে বলছি" for Bangla) through `AudioPlayer`, run
  `FreeWebSearch.search` (max 5, same as Live path), build the search
  block with `currentDateTimeLine()` → send `proceed` with searchContext.
  Else send `proceed` with null immediately. Connector-command check
  (`ConnectorIntentHandler.isConnectorCommand`) + macro-stop words run on
  the final transcript BEFORE `proceed`, same as `handleFallbackFinalText`
  (connector result is spoken via `proceed` with searchContext carrying
  "User command result: ..." — reuse that pattern, do not invent a new one).
- On `token`: append to `_modelTranscript`, stage SPEAKING once first
  audio arrives (stage THINKING while tokens stream but no audio yet).
- On `audio`: base64-decode → feed `AudioPlayer` in 4096-byte chunks
  (same `playPcmChunks` pattern as the fallback path). First audio chunk
  plays immediately — no pre-buffer wait beyond AudioPlayer's own rule.
- On `reply-done`: stage LISTENING, keep listening for the next turn.
- On `error`: show message once; if it is a missing/invalid-key error →
  state ERROR with the exact message (tell the user which key to fix in
  Setup); else (network/upstream) → fall back to the Live attempt (see §6).
- Reconnect: if the WS drops mid-session (not user stop), reconnect ONCE
  with the same keys + resend `start`; if that fails → Live attempt.

## App changes (Kotlin)
### 1. SecurePreferences.kt — Groq key, same BYOK pattern as Gemini
- `KEY_GROQ_API_KEY = "groq_api_key"` in the SAME EncryptedSharedPreferences
  (AES256-GCM). `getGroqApiKey()/setGroqApiKey()/clearGroqApiKey()`
  (trim, blank → null). Never log the value (length only).
- `isPlausibleGroqKey(raw)`: trimmed, starts `gsk_`, length >= 20.
- Remove `getDeepgramKey/setDeepgramKey` + `KEY_DEEPGRAM_KEY` if nothing
  else uses them (after removing the VoiceEngineScreen card).

### 2. SetupScreen.kt (step 1) — EXACTLY TWO key fields, nothing else added
- Field 1: Gemini API key (existing, untouched). Field 2 (new, directly
  below, same visual style — OutlinedTextField, password toggle, Material
  icons only): label "Groq API Key (for voice)", placeholder "Paste your
  Groq key — starts with gsk_".
- Hint: "Free, no card — your own quota. Voice uses your Groq key for
  speech-to-text and fast replies; your Gemini key does the talking."
- "Get Free Key" button → `https://console.groq.com/keys` (browser intent,
  same pattern as the AI Studio button). Keep the existing Gemini "Get Free
  Key" + "Test & Save" untouched.
- Groq "Test" action: `GET https://api.groq.com/openai/v1/models` with
  `Authorization: Bearer <key>`, 8 s call timeout / 12 s overall cap (same
  fail-open rule as Gemini validation: plausible key + transient/network
  error = save + proceed with a transient message; 401/403 = invalid, do
  not save). Gemini key remains the gate to enter the app; Groq key is
  optional at setup but REQUIRED for server voice — the voice button
  explains that if it is missing. Do NOT add any third field, toggle, or
  option to this page.

### 3. VoiceEngineScreen.kt — remove Deepgram, add Groq card
- DELETE the "Backup Transcriber (Deepgram Nova-3)" card entirely.
- ADD a "Server Voice (Groq)" card in its place: Groq key field (password
  style) + note "Free at console.groq.com — no card, your own quota" +
  saved state. Reuse SecurePreferences Groq accessors.

### 4. New `network/ServerVoiceClient.kt` — WS + key validation (OkHttp, no new dependency)
- `fun connect(groqKey, geminiKey, listener): WebSocket` — OkHttp WS to
  `$SERVER_VOICE_URL/voice/stream` with headers `x-mira-groq-key` /
  `x-mira-gemini-key`, 10 s connect timeout. Listener callbacks:
  onPartial/onTranscript/onToken/onAudio/onReplyDone/onError/onClosed.
  Never log keys, transcripts, or audio (lengths only).
- `suspend fun validateGroqKey(key): KeyValidationOutcome` — the
  `GET /openai/v1/models` check from §2 (reuse the same outcome type as
  Gemini validation). Put the Groq HTTP check here so SetupScreen stays thin.
- `SERVER_VOICE_URL` from `BuildConfig` (add
  `buildConfigField("String", "SERVER_VOICE_URL",
  "\"https://mira-server-voice.salman-dev.workers.dev\"")`, fallback
  literal in code as the other clients do).

### 5. New `voice/ServerVoiceSession.kt` — as specified in "Client turn flow"
- Constructor takes: context, groqKey, geminiKey, voiceName, language,
  systemPrompt, dateLine, audioRecorder hooks (or owns its own
  AudioRecorder + VAD — prefer OWNING them, mirroring how MicService owns
  them today, so MicService stays the orchestrator), callbacks
  (onState/onStage/onUserTranscript/onModelTranscript/onError/
  onAudioChunk via AudioPlayer owned here, onRequestLiveFallback).
- Keep it decoupled from MicService internals: MicService passes in the
  already-built `systemPrompt`, `voiceName`, `language`, keys; the session
  owns recorder + VAD + WS + AudioPlayer and reports via callbacks/flows
  MicService already observes. If owning AudioPlayer inside the session
  conflicts with MicService's player, the session may accept MicService's
  AudioPlayer as a constructor arg instead — planner/implementer decides,
  but there must be exactly ONE AudioPlayer per session.
- Short-answer behaviour: systemPrompt already carries the voice rules;
  append (client-side, before `start`): "Keep voice answers short and
  fast — a few sentences at most unless the user asks for detail." plus,
  when a search happened, the searchContext must start with "You have
  already spoken the search acknowledgement aloud. Do NOT repeat it — give
  ONLY the final answer." (same wording as the fallback path).

### 6. MicService.kt — server voice FIRST, Live SECOND, SpeechRecognizer LAST
- `startLiveVoiceSession()`: resolve keys (Gemini via ApiKeyResolver as
  today + Groq via SecurePreferences). If BOTH keys present → start
  `ServerVoiceSession` (new default). If either key is missing → show the
  exact missing-key message and start the EXISTING Live path as the
  secondary attempt (do not change Live code — `GeminiLiveClient`,
  pre-roll, rotation, relays all stay). If server voice fails mid-session
  (WS drop after one reconnect, or non-key `error` frame) → stop the
  server session and start the existing Live path once; Live's own
  `onError` still falls through to `startFallbackVoiceSession`
  (SpeechRecognizer + Gemini REST + Gemini TTS) exactly as today.
- Keep: `AudioPlayer` pre-primed at session start (or owned by the server
  session per §5 — exactly one), VAD, foreground notification,
  `stopLiveVoiceSessionInternal()` releasing the server session too,
  transcript StateFlows. `isFallbackMode` semantics unchanged.

### 7. Keep intact (rounds 1–8)
Fast Setup validation (12 s cap), chat toggles, file upload, Bangla default
(ONE language only), file-permission gate, date injection from device
clock, Supabase backend licence OFF, Home/Setup UI exactly as v1 except
the ADDED Groq field/card (and Deepgram card removal), Material icons, no
wake word, Live tolerance + Supabase + Cloudflare relays, 30 voices,
personas.

## Verification (code only — no Gemini/Groq key on the VM)
- `./gradlew assembleDebug` locally if the SDK allows, else GitHub Actions
  run must be GREEN (the proven path).
- `grep -ri "getmaya\|hunteris\|toolbaaz" app/src` empty; no Llama model
  string anywhere (`grep -ri "llama" app/src cloudflare-relay` empty);
  NO Deepgram string anywhere (`grep -ri "deepgram" app/src` empty);
  no key logging (grep Log lines in new files show no key/transcript/audio
  values).
- Worker health 200 + WS without keys rejected, verified live after deploy.
- No real API key anywhere in the repo, spec, or logs.

## Phone test needed (code-verified only here)
Voice end-to-end is the proof: enter Groq key (console.groq.com) + Gemini
key in Setup → tap mic → speak Bangla → Mira replies aloud in Bangla,
first words in ~0.5–1 s, partial transcript visible while speaking.
Also test: search question → hears "এক মিনিট, আমি খুঁজে বলছি" first, then
the answer; barge-in → speak while Mira talks, she stops and listens.
Report which path answered (server / Live / SpeechRecognizer fallback)
from logcat tags MiraMicService / MiraServerVoice.
