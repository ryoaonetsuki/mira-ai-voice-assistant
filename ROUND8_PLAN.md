# Mira AI — Round 8 Implementation Plan: Maya's Instant-Voice Mechanisms & Cloudflare Relay Rung

> **Goal**: Achieve sub-second voice response latency matching Maya's proven architecture using Mira's own infrastructure (Google Gemini Live API + Supabase Relay + Cloudflare Worker Relay), while maintaining BYOK integrity, free-tier limits, and zero external proprietary dependencies.

---

## 1. Executive Summary & Architecture Overview

Maya achieves near-instant voice interactions (<1s from tap to audible reply) not through pre-warmed sockets, but through **aggressive audio pipelining, buffering, and pre-priming**:
1. Capturing microphone audio into an **8-second pre-roll ring buffer** starting immediately when the session initiates, flushing words spoken before WebSocket connection finishes.
2. Holding and queuing audio/text frames before session setup completion in a **128,000-byte held queue**, preventing dropped syllables during handshake.
3. Pre-priming the speaker (`AudioTrack` running in `PLAYSTATE_PLAYING` immediately) and applying a **≤350 ms / 9,600-byte drain rule** so model speech begins audible output on the very first chunks.
4. Detecting voice boundaries on-device with zero-dependency **Voice Activity Detection** (RMS per frame, 150ms speech / 300ms endpoint) to eliminate trailing silence transmission without relying on server round-trips.
5. Managing connection longevity through **session resumption handle persistence** and **zero-gap dual-slot session rotation at 8 minutes**.
6. Adding a **Cloudflare Worker Relay** as a 4th ladder rung (`Ephemeral -> DirectKey -> Supabase Relay -> Cloudflare Relay`) to bypass mobile ISP WebSocket routing blocks.

### Hard Prohibitions Preserved
- **Zero calls to Maya/Hunter hosts**: No `relay.getmaya.online`, `api.getmaya.cloud`, `getmaya.online`, `sync.hunterislive.online`, `ops.hunterailabs.online`, or `toolbaaz.online`.
- **No proprietary TTS**: No Ultra TTS, No Hunter TTS.
- **Model fixed to `gemini-3.8-live`**: Maya's default `gemini-3.1-flash-live-preview` is deprecated/shuts down 17 Nov 2026.
- **No wake word**: Voice starts on manual tap / session start.
- **No new Gradle dependencies**: VAD and ring buffers implemented in pure Kotlin.

---

## 2. Exact Constants Specification

| Mechanism | Constant Name | Value | Purpose |
| :--- | :--- | :--- | :--- |
| **Mic Pre-Roll** | `PRE_ROLL_WINDOW_MS` | `8,000 ms` | Rolling circular buffer window (256,000 bytes @ 16kHz PCM16 mono) |
| **Pre-Roll Discard** | `PRE_ROLL_MAX_AGE_MS` | `20,000 ms` | Frames older than 20s discarded at setupComplete flush |
| **Held Queue Cap** | `HELD_QUEUE_CAP_BYTES` | `128,000 bytes` | Cap for pre-setup frames (~4,000 ms of audio or text turns) |
| **Speaker Pre-Buffer Bytes**| `PREBUFFER_MAX_BYTES` | `9,600 bytes` | Queue threshold to start draining audio to AudioTrack (200 ms @ 24kHz) |
| **Speaker Pre-Buffer Timeout**| `PREBUFFER_TIMEOUT_MS`| `350 ms` | Maximum wait time before playing first audio chunk |
| **Speech Start VAD** | `VAD_SPEECH_TRIGGER_MS` | `150 ms` | Continuous audio above RMS threshold to declare speech start (`t-speech`) |
| **Speech Endpoint VAD** | `VAD_ENDPOINT_SILENCE_MS`| `300 ms` | Continuous silence below threshold to declare turn endpoint (`t-endpoint`) |
| **Session Rotation** | `ROTATION_INTERVAL_MS` | `480,000 ms` (8 min) | Time from `setupComplete` to standby session swap |
| **Setup Timeout** | `SETUP_TIMEOUT_MS` | `45,000 ms` | Per-variant WebSocket setupComplete deadline |
| **Safety Budget** | `TOTAL_SAFETY_BUDGET_MS`| `240,000 ms` (4 min) | Overall session connection safety timeout |

---

## 3. New Classes & Components

### 3.1 `PreRollRingBuffer.kt`
- **Location**: `app/src/main/java/io/github/ryoaonetsuki/mira/voice/PreRollRingBuffer.kt`
- **Role**: Thread-safe circular timestamped buffer holding 8,000 ms of 16kHz PCM16 mono audio frames.
- **Key Methods**:
  - `fun write(pcmData: ByteArray)`: Appends chunk with current timestamp; prunes items exceeding 8,000 ms window or 256,000 bytes.
  - `fun flush(maxAgeMs: Long = 20_000L): List<ByteArray>`: Drains all frames not older than 20,000 ms; computes total flushed duration; logs `t2.7 preroll-flushed ms=<flushed_ms>`.
  - `fun clear()`: Empties the buffer.

### 3.2 `VoiceActivityDetector.kt`
- **Location**: `app/src/main/java/io/github/ryoaonetsuki/mira/voice/VoiceActivityDetector.kt`
- **Role**: On-device RMS-based voice activity detector with adaptive background noise baseline.
- **Behavior**:
  - Computes root-mean-square amplitude for each 16-bit PCM chunk: $RMS = \sqrt{\frac{1}{N}\sum s_i^2}$.
  - Dynamically updates noise floor baseline during quiet periods.
  - Declares speech after **150 ms** continuous above threshold $\rightarrow$ logs `t-speech` and triggers `onSpeechStart()`.
  - Declares endpoint after **300 ms** continuous silence $\rightarrow$ logs `t-endpoint` and triggers `onSpeechEndpoint()`.
  - Informs `MicService` to suppress sending dead trailing silence chunks to the network until speech resumes. Server VAD remains enabled (`automaticActivityDetection` in V2); does NOT emit `activityEnd`.

### 3.3 `SessionResumptionStore.kt`
- **Location**: `app/src/main/java/io/github/ryoaonetsuki/mira/voice/SessionResumptionStore.kt`
- **Role**: Manages persisting and retrieving `sessionResumptionUpdate.newHandle`.
- **Security & Logging**:
  - Stored in SharedPreferences (`mira_session_resumption`).
  - **NEVER** logs the handle content. Only logs `length=<n>` or fingerprint prefix.
  - Provides `getHandle(): String?`, `saveHandle(handle: String)`, `clearHandle()`.

### 3.4 Cloudflare Worker Relay (`worker.js`)
- **Location**: `cloudflare-relay/worker.js` (managed in repo workspace)
- **Role**: Serverless edge WebSocket proxy deployed to Cloudflare Workers (account `Salman-Dev`, free tier).
- **Behavior**:
  - Validates `Upgrade: websocket` header (returns 426 otherwise).
  - Validates `x-mira-key` header (returns 401 if missing).
  - Upgrades client connection using `WebSocketPair`.
  - Opens upstream connection to Google Live API (`wss://generativelanguage.googleapis.com/.../BidiGenerateContent?key=<x-mira-key>`) via `fetch(upstreamUrl, { headers: { Upgrade: "websocket" } })`.
  - Bi-directionally pipes raw frames verbatim without buffering or inspection.
  - Zero logging of keys or payload frames.
  - Supports health check endpoint: `GET ?action=health` $\rightarrow$ `{"service": "gemini-live-relay", "version": "1.0.0", "ok": true}`.

---

## 4. File-by-File Changes

### 4.1 `app/build.gradle.kts`
- Add BuildConfig field for the Cloudflare Relay endpoint:
  ```kotlin
  buildConfigField(
      "String",
      "CLOUDFLARE_RELAY_URL",
      "\"https://mira-gemini-live-relay.salman-dev.workers.dev\""
  )
  ```

### 4.2 `app/src/main/java/io/github/ryoaonetsuki/mira/voice/GeminiLiveClient.kt`
- **Auth Ladder Update**:
  - Add `LiveAuthMode.CloudflareRelay(val key: String, val workerUrl: String) : LiveAuthMode("cf-relay")`.
  - In `connectWithCurrentAttemptLocked()`, wire `LiveAuthMode.CloudflareRelay`:
    - One setup variant (V1) only.
    - URL: `workerUrl` (or `CLOUDFLARE_RELAY_URL`).
    - Headers: `x-mira-key: <key>`. (No Supabase `apikey` or `Authorization` headers required).
- **128,000-byte Pre-Setup Held Queue**:
  - Define `private val heldQueue = ArrayDeque<HeldMessage>()` and `private var heldQueueBytes = 0`.
  - In `sendAudioChunk(pcmBytes)`:
    - If `!isSetupComplete.get() || webSocket == null`:
      - If `isRunning.get()`: buffer chunk in `heldQueue`, drop oldest if `heldQueueBytes > 128_000`.
      - Return early.
  - In `sendTextMessage(text)`:
    - If `!isSetupComplete.get() || webSocket == null`:
      - Buffer in `heldQueue`, enforce 128,000 byte cap.
      - Return early.
  - In `handleIncomingMessage`: on `setupComplete`:
    - Flush all valid items from `heldQueue` (discarding any older than 20,000 ms) in original FIFO order before streaming live frames.
    - Log queue depth and flushed byte count.
  - In `handleSocketTermination()` and `endSessionLocked()`:
    - Clear `heldQueue` and reset `heldQueueBytes = 0` on session end and mode advance to avoid stale replay.
- **Session Resumption Replay & Capture**:
  - Add constructor parameter `val storedResumptionHandle: String? = null`.
  - Add listener callback `fun onSessionResumptionUpdate(newHandle: String)`.
  - In `sendSetupMessage()`:
    ```kotlin
    val resumptionObj = JSONObject()
    if (!storedResumptionHandle.isNullOrBlank()) {
        resumptionObj.put("handle", storedResumptionHandle)
        Log.i(TAG, "Replaying sessionResumption handle in setup (length=${storedResumptionHandle.length})")
    }
    setupDetails.put("sessionResumption", resumptionObj)
    ```
  - In `handleIncomingMessage()`:
    - Parse `sessionResumptionUpdate.newHandle` (at root or inside `serverContent`).
    - If present, invoke `listener.onSessionResumptionUpdate(newHandle)`.

### 4.3 `app/src/main/java/io/github/ryoaonetsuki/mira/voice/AudioRecorder.kt`
- **Continuous Pre-Roll Integration**:
  - Instantiate `private val preRollRing = PreRollRingBuffer(windowMs = 8000L)`.
  - In `recordingThread`:
    - Record audio chunk $\rightarrow$ calculate RMS $\rightarrow$ pass to `onAmplitude`.
    - Always write chunk to `preRollRing.write(chunkCopy)`.
    - If `isLiveForwarding` is enabled: call `onAudioChunk(chunkCopy)`.
  - New public method `fun enableLiveForwarding(enabled: Boolean)`.
  - New public method `fun flushPreRoll(): List<ByteArray>`:
    - Calls `preRollRing.flush(maxAgeMs = 20_000L)`.
    - Automatically enables `isLiveForwarding = true`.
  - New public method `fun resetPreRoll()`:
    - Clears pre-roll buffer and sets `isLiveForwarding = false`.

### 4.4 `app/src/main/java/io/github/ryoaonetsuki/mira/voice/AudioPlayer.kt`
- **≤350 ms / 9,600-byte Playback Pre-Buffer Drain Rule**:
  - In `startPlaybackLoop()`:
    - When transitioning from idle to playing (first chunk of an utterance arrives):
      - Record arrival timestamp: `val firstArrivalMs = System.currentTimeMillis()`.
      - Buffer chunks in an utterance batch while:
        `totalQueuedBytes < 9600 && (System.currentTimeMillis() - firstArrivalMs) < 350`
      - As soon as queued bytes reach $\ge 9,600$ OR $350\text{ ms}$ have elapsed (or queue has arrived chunks):
        - Compute `val prebufferMs = System.currentTimeMillis() - firstArrivalMs`.
        - Log marker: `Log.i(TAG, "t5 playback-start prebuffer_ms=$prebufferMs queued_bytes=$totalQueuedBytes")`.
        - Switch speaking state: `isSpeakingState = true; onSpeakingStateChanged(true)`.
        - Write all buffered chunks to `AudioTrack`.
    - Proceed with streaming playback loop (300 ms silence grace period on utterance end).
  - Pre-priming: AudioTrack is initialized and `play()` is called immediately upon `AudioPlayer` creation (retained).
  - Barge-in `stopImmediatelyAndFlush()`: clears queue, flushes `AudioTrack`, and resets pre-buffer state (retained).

### 4.5 `app/src/main/java/io/github/ryoaonetsuki/mira/service/MicService.kt`
- **Pre-Roll Recording Activation at Session Start**:
  - In `startLiveVoiceSession()`:
    - Check microphone permission early (existing gate).
    - Construct `AudioPlayer` immediately (pre-priming).
    - Construct `AudioRecorder` immediately and call `audioRecorder?.startRecording()` **at session start** before WebSocket connection opens.
    - Do NOT wait for `onSetupComplete` to start recording. Audio fills the 8-second pre-roll ring buffer while WebSocket handshakes.
- **Auth Ladder Setup**:
  - Build `authModes` ladder:
    1. `LiveAuthMode.Ephemeral(token)` (if user key mint succeeds)
    2. `LiveAuthMode.DirectKey(userKey)`
    3. `LiveAuthMode.Relay(userKey, relayUrl)` (Supabase Edge Function)
    4. `LiveAuthMode.CloudflareRelay(userKey, cfRelayUrl)` (Cloudflare Worker Relay)
- **Session Resumption & Setup Complete Flush**:
  - Wire `SessionResumptionStore`: load stored handle and pass to `GeminiLiveClient`.
  - In `onSetupComplete()`:
    - Flush pre-roll ring buffer:
      ```kotlin
      val preRollChunks = audioRecorder?.flushPreRoll() ?: emptyList()
      for (chunk in preRollChunks) {
          liveClient?.sendAudioChunk(chunk)
      }
      ```
    - Transition `liveState` to `LISTENING`.
    - Launch 8-minute rotation timer: `scheduleSessionRotation()`.
  - On `onSessionResumptionUpdate(newHandle)`:
    - Save handle to `SessionResumptionStore` (never logging the value).
- **On-Device VAD Wiring**:
  - Attach `VoiceActivityDetector`:
    - Listen for `onSpeechStart` $\rightarrow$ log `t-speech`, set UI `VoiceStage.LISTENING` / `RECOGNISING`.
    - Listen for `onSpeechEndpoint` $\rightarrow$ log `t-endpoint`.
    - Trailing silence suppression: when VAD declares endpoint and enters silence, pause sending audio chunks to `liveClient` until speech resumes.
- **Dual-Slot Session Rotation**:
  - Private field `private var standbyLiveClient: GeminiLiveClient? = null`.
  - After 8 minutes of healthy session:
    - Log `rotation-standby`.
    - Instantiate `standbyLiveClient` with same auth modes and stored resumption handle.
    - Call `standbyLiveClient.startSession()`.
    - When `standbyLiveClient` triggers `onSetupComplete`:
      - Log `rotation-swapped`.
      - Atomically swap: `val oldClient = liveClient; liveClient = standbyLiveClient; standbyLiveClient = null`.
      - Safely close `oldClient?.endSession()`.
      - Schedule next 8-minute rotation.
    - If standby setup fails or times out:
      - Log warning: `rotation-standby failed; keeping current session`.
      - Clean up `standbyLiveClient = null`.
      - Retry standby rotation once after 30 seconds.

---

## 5. Cloudflare Worker Relay Design & Deployment

### 5.1 Worker Architecture (`worker.js`)
```javascript
// mira-gemini-live-relay Cloudflare Worker
const GOOGLE_LIVE_WS_URL =
  "https://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";

addEventListener("fetch", (event) => {
  event.respondWith(handleRequest(event.request));
});

async function handleRequest(request) {
  const url = new URL(request.url);

  // Health probe
  if (url.searchParams.get("action") === "health") {
    return new Response(
      JSON.stringify({ service: "gemini-live-relay", version: "1.0.0", ok: true }),
      { headers: { "Content-Type": "application/json" }, status: 200 }
    );
  }

  // WebSocket upgrade validation
  const upgradeHeader = request.headers.get("Upgrade");
  if (!upgradeHeader || upgradeHeader.toLowerCase() !== "websocket") {
    return new Response("WebSocket upgrade required", { status: 426 });
  }

  // Extract BYOK key from x-mira-key header
  const apiKey = (request.headers.get("x-mira-key") || "").trim();
  if (!apiKey) {
    return new Response(JSON.stringify({ error: "Missing x-mira-key header" }), {
      headers: { "Content-Type": "application/json" },
      status: 401,
    });
  }

  // Connect upstream to Google Live
  const upstreamUrl = `${GOOGLE_LIVE_WS_URL}?key=${encodeURIComponent(apiKey)}`;
  let upstreamResponse;
  try {
    upstreamResponse = await fetch(upstreamUrl, {
      headers: { Upgrade: "websocket" },
    });
  } catch (err) {
    return new Response(`Upstream connect failed: ${err.message}`, { status: 502 });
  }

  if (upstreamResponse.status !== 101 || !upstreamResponse.webSocket) {
    return new Response(
      `Upstream handshake rejected (status ${upstreamResponse.status})`,
      { status: upstreamResponse.status === 101 ? 502 : upstreamResponse.status }
    );
  }

  const upstreamSocket = upstreamResponse.webSocket;
  upstreamSocket.accept();

  // Create Client WebSocket pair
  const [client, server] = Object.values(new WebSocketPair());
  server.accept();

  let closed = false;
  const closeBoth = (code, reason) => {
    if (closed) return;
    closed = true;
    try { upstreamSocket.close(code, reason); } catch (_) {}
    try { server.close(code, reason); } catch (_) {}
  };

  upstreamSocket.addEventListener("message", (event) => {
    try { server.send(event.data); } catch (_) {}
  });
  upstreamSocket.addEventListener("close", (e) => closeBoth(e.code || 1000, e.reason || "Upstream closed"));
  upstreamSocket.addEventListener("error", () => closeBoth(1011, "Upstream error"));

  server.addEventListener("message", (event) => {
    try { upstreamSocket.send(event.data); } catch (_) {}
  });
  server.addEventListener("close", (e) => closeBoth(e.code || 1000, e.reason || "Client closed"));
  server.addEventListener("error", () => closeBoth(1011, "Client error"));

  return new Response(null, { status: 101, webSocket: client });
}
```

### 5.2 Deployment Steps via `cf.py` & `dynamic_credentials`
- **Account**: `Salman-Dev` (Account ID: `5577ea99b7437d6aeadaf0a840c43ce2`).
- **Worker Subdomain**: `salman-dev.workers.dev`.
- **Target Script**: `mira-gemini-live-relay`.
- **URL**: `https://mira-gemini-live-relay.salman-dev.workers.dev`.
- **Deployment Execution**:
  Because Cloudflare API v4 requires `Content-Type: application/javascript` for uploading worker scripts (while `cf.py` default sets `application/json`), the implementing agent will run a small upload helper script importing `dynamic_credentials` from `/opt/hatch/skills/skill-creator/bin`:
  ```bash
  export PYTHONPATH="/opt/hatch/skills/skill-creator/bin:$PYTHONPATH"
  python3 -c "
  import urllib.request, sys
  from dynamic_credentials import add_surrogate_to_request, read_response_body

  with open('cloudflare-relay/worker.js', 'rb') as f:
      code = f.read()

  req = urllib.request.Request(
      'https://api.cloudflare.com/client/v4/accounts/5577ea99b7437d6aeadaf0a840c43ce2/workers/scripts/mira-gemini-live-relay',
      data=code,
      headers={'Content-Type': 'application/javascript'},
      method='PUT'
  )
  add_surrogate_to_request(req, 'custom.cloudflare', allowed_hosts=['api.cloudflare.com'])
  with urllib.request.urlopen(req, timeout=30) as resp:
      print(read_response_body(resp).decode('utf-8'))
  "
  ```
- **Enable workers.dev Subdomain**:
  ```bash
  python3 /home/hatch/workspace/custom-connectors/custom.legacy-cloudflare/bin/cf.py POST /accounts/5577ea99b7437d6aeadaf0a840c43ce2/workers/scripts/mira-gemini-live-relay/subdomain '{"enabled":true}'
  ```

### 5.3 Live Verification Steps
1. **Health Check**:
   ```bash
   curl -s "https://mira-gemini-live-relay.salman-dev.workers.dev/?action=health"
   ```
   *Expected*: `{"service":"gemini-live-relay","version":"1.0.0","ok":true}`.
2. **Missing Key Rejection**:
   ```bash
   curl -i -s "https://mira-gemini-live-relay.salman-dev.workers.dev/" -H "Upgrade: websocket"
   ```
   *Expected*: `HTTP/1.1 401 Unauthorized` with `{"error":"Missing x-mira-key header"}`.
3. **Upstream Dummy Key Handshake**:
   ```bash
   curl -i -N -H "Connection: Upgrade" -H "Upgrade: websocket" -H "x-mira-key: test-dummy-key" "https://mira-gemini-live-relay.salman-dev.workers.dev/"
   ```
   *Expected*: HTTP 400/502 indicating clean rejection by Google Live endpoint without crashing.

---

## 6. Critical Risks & Mitigations

### 6.1 Rotation vs Stall Watchdog Race
- **Risk**: The stall watchdog kills the socket if user sent audio and server was silent for 8 minutes (`STALL_MAX_SILENCE_MS = 480_000 ms`). The rotation also triggers at 8 minutes. If both fire simultaneously, the session could crash during rotation.
- **Mitigation**:
  - Rotation timer is anchored strictly to `onSetupComplete()` of an active session.
  - Rotation only initiates if the session is healthy (`liveState == LISTENING || liveState == SPEAKING`).
  - Standby session connects in the background while the primary session continues operating.
  - Primary session is only closed after standby session receives `onSetupComplete()`. If standby fails, the primary session is retained and rotation is retried once after 30 seconds.

### 6.2 Held-Queue Staleness & Mode Retries
- **Risk**: If variant V1 fails or mode 1 (Ephemeral) fails and advances to mode 2 (DirectKey), frames held in `heldQueue` could be seconds old and replayed into the wrong session attempt, causing confusing dialogue or out-of-order errors.
- **Mitigation**:
  - Enforce max age: Any frame in `heldQueue` older than `20,000 ms` is discarded on flush.
  - Flush & reset: When a socket terminates before setup completion and advances to the next auth mode or retry variant, `heldQueue` is cleared completely.

### 6.3 Pre-Roll Flush Ordering & Duplication
- **Risk**: If the recorder continues streaming live audio while the pre-roll ring is being flushed, live audio chunks could interleave with or overtake pre-roll chunks, causing garbled speech. Furthermore, if chunks are queued in both the pre-roll buffer and held queue, duplicate audio would be sent.
- **Mitigation**:
  - `AudioRecorder` keeps `isLiveForwarding = false` until `flushPreRoll()` is called.
  - On `setupComplete`, `flushPreRoll()` synchronously extracts all pre-roll chunks, sends them to `liveClient.sendAudioChunk()`, and enables `isLiveForwarding = true`.
  - All pre-roll chunks are flushed in strict FIFO chronological sequence before any real-time chunks are emitted.

---

## 7. Verification Checklist

- [ ] **Prohibition Audit**: `grep -ri "getmaya\|hunteris\|toolbaaz" app/src` returns zero matches.
- [ ] **Build Validation**: `./gradlew assembleDebug` passes locally, or GitHub Actions run is GREEN.
- [ ] **Log Marker Audit**:
  - [ ] `t2.7 preroll-flushed ms=<n>` emitted on setupComplete.
  - [ ] `t5 playback-start prebuffer_ms=<n>` emitted on first audio playback.
  - [ ] `t-speech` and `t-endpoint` logged on voice start/stop boundaries.
  - [ ] `rotation-standby` and `rotation-swapped` logged during 8-minute swap.
  - [ ] `sessionResumption` handle saved and replayed without logging raw values.
- [ ] **Cloudflare Relay Verification**:
  - [ ] Health check probe passes (`HTTP 200`).
  - [ ] Rejects missing `x-mira-key` (`HTTP 401`).
  - [ ] Dummy key rejects upstream cleanly (`HTTP 400` / `502`).
  - [ ] URL recorded in `BuildConfig.CLOUDFLARE_RELAY_URL` and `ROUND8_SPEC.md`.

---

## 8. Numbered Step List for Implementing Agent

1. **Deploy Cloudflare Worker Relay**:
   - Create `cloudflare-relay/worker.js` with the bidirectional WebSocket proxy code.
   - Run the upload script with surrogate auth to deploy to `Salman-Dev` account.
   - Enable `salman-dev.workers.dev` subdomain routing via `cf.py`.
   - Test endpoints with `curl` (health check, 401 on missing key, dummy key handshake).
   - Record deployed URL in `ROUND8_SPEC.md`.

2. **Add BuildConfig Entry**:
   - Update `app/build.gradle.kts` with `CLOUDFLARE_RELAY_URL`.

3. **Create `PreRollRingBuffer.kt`**:
   - Implement circular timestamped buffer with 8,000 ms window, 20,000 ms discard rule, and `t2.7 preroll-flushed` log marker.

4. **Create `VoiceActivityDetector.kt`**:
   - Implement zero-dependency RMS calculations with adaptive noise baseline, 150 ms speech detection (`t-speech`), and 300 ms endpointing (`t-endpoint`).

5. **Create `SessionResumptionStore.kt`**:
   - Implement SharedPreferences storage for resumption handle with strict no-value logging.

6. **Update `AudioRecorder.kt`**:
   - Embed `PreRollRingBuffer`.
   - Keep mic recording immediately on start while deferring live forwarding until `flushPreRoll()`.

7. **Update `AudioPlayer.kt`**:
   - Implement the $\le 350\text{ ms}$ / $9,600\text{ bytes}$ playback pre-buffer drain rule.
   - Add log marker `t5 playback-start prebuffer_ms=<n>`.

8. **Update `GeminiLiveClient.kt`**:
   - Add `LiveAuthMode.CloudflareRelay` mode.
   - Implement the 128,000-byte pre-setup held queue for `sendAudioChunk` and `sendTextMessage`.
   - Add `storedResumptionHandle` parameter to constructor and replay in setup JSON.
   - Extract and report `sessionResumptionUpdate.newHandle` from incoming frames.

9. **Update `MicService.kt` Wiring**:
   - Start `AudioRecorder` and `AudioPlayer` immediately upon session start.
   - Add Cloudflare Relay as the 4th rung in `authModes`.
   - Flush pre-roll ring into `liveClient` on `onSetupComplete()`.
   - Wire `VoiceActivityDetector` to suppress trailing silence frames.
   - Implement 8-minute dual-slot session rotation with standby client swap.
   - Wire `SessionResumptionStore` to capture and replay handles.

10. **Run Verification & Audit**:
    - Confirm zero occurrences of prohibited domains with `grep`.
    - Verify code compilation and push to GitHub Actions to confirm GREEN build.
