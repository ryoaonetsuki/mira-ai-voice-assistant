// mira-server-voice Cloudflare Worker
// Low-latency streaming voice server for Project Mira
// Handles WebSocket audio stream, rolling Groq Whisper STT,
// Groq gpt-oss-120b LLM streaming with Gemini fallback,
// and sentence-chunked Gemini TTS.
// NEVER logs API keys, transcripts, or audio data.

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "*",
};

addEventListener("fetch", (event) => {
  event.respondWith(handleRequest(event.request));
});

async function handleRequest(request) {
  const url = new URL(request.url);

  // CORS preflight
  if (request.method === "OPTIONS") {
    return new Response(null, { status: 204, headers: CORS_HEADERS });
  }

  // Health probe: GET ?action=health
  if (url.searchParams.get("action") === "health") {
    return new Response(
      JSON.stringify({
        service: "mira-server-voice",
        version: "2.0.1",
        ok: true,
        streaming: true,
      }),
      {
        status: 200,
        headers: {
          "Content-Type": "application/json",
          ...CORS_HEADERS,
        },
      }
    );
  }

  // WebSocket upgrade validation
  const upgradeHeader = request.headers.get("Upgrade");
  if (!upgradeHeader || upgradeHeader.toLowerCase() !== "websocket") {
    return new Response("WebSocket upgrade required", {
      status: 426,
      headers: CORS_HEADERS,
    });
  }

  // Validate BYOK keys
  const groqKey = (request.headers.get("x-mira-groq-key") || "").trim();
  const geminiKey = (request.headers.get("x-mira-gemini-key") || "").trim();

  if (!groqKey || !geminiKey) {
    return new Response(
      JSON.stringify({ error: "Missing API key header" }),
      {
        status: 401,
        headers: {
          "Content-Type": "application/json",
          ...CORS_HEADERS,
        },
      }
    );
  }

  // Create WebSocket pair
  const [client, server] = Object.values(new WebSocketPair());
  server.accept();

  // Run the session handler
  handleVoiceSession(server, groqKey, geminiKey);

  return new Response(null, {
    status: 101,
    webSocket: client,
    headers: CORS_HEADERS,
  });
}

/**
 * Manages state for an active streaming voice session.
 */
function handleVoiceSession(ws, groqKey, geminiKey) {
  // Session configuration from "start" message
  let sessionConfig = {
    language: "Bangla (Bengali)",
    voiceName: "Aoede",
    systemPrompt: "You are Mira, a helpful AI companion.",
    dateLine: "",
  };

  // Turn state
  // State: "LISTENING" | "PROCESSING"
  let turnState = "LISTENING";
  let utteranceFrames = [];
  let utteranceByteLength = 0;
  let inSpeech = false;
  let continuousSpeechBytes = 0;
  let lastPartialSpeechBytes = 0;
  let silenceDurationMs = 0;
  let isWhisperPartialRunning = false;

  // Active turn controls
  let activeTurnController = null;
  let proceedResolver = null;
  let proceedTimer = null;

  const resetListening = () => {
    turnState = "LISTENING";
    utteranceFrames = [];
    utteranceByteLength = 0;
    inSpeech = false;
    continuousSpeechBytes = 0;
    lastPartialSpeechBytes = 0;
    silenceDurationMs = 0;
    isWhisperPartialRunning = false;
  };

  const abortActiveTurn = () => {
    if (activeTurnController) {
      try {
        activeTurnController.abort();
      } catch (_) {}
      activeTurnController = null;
    }
    if (proceedTimer) {
      clearTimeout(proceedTimer);
      proceedTimer = null;
    }
    proceedResolver = null;
  };

  const sendJson = (payload) => {
    try {
      ws.send(JSON.stringify(payload));
    } catch (_) {}
  };

  ws.addEventListener("message", async (event) => {
    // 1. Binary frames: 16kHz PCM16 mono audio
    if (event.data instanceof ArrayBuffer || (event.data && event.data.buffer instanceof ArrayBuffer)) {
      if (turnState !== "LISTENING") {
        return;
      }

      const pcmBytes = event.data instanceof ArrayBuffer
        ? new Uint8Array(event.data)
        : new Uint8Array(event.data.buffer, event.data.byteOffset, event.data.byteLength);

      // Guard against oversized malicious frames (>64KB)
      if (pcmBytes.byteLength > 65536) {
        return;
      }

      utteranceFrames.push(pcmBytes);
      utteranceByteLength += pcmBytes.byteLength;

      // 60-second utterance cap (60s * 32,000 bytes/s = 1,920,000 bytes)
      if (utteranceByteLength >= 1920000) {
        finishUtterance();
        return;
      }

      // Calculate RMS for silence/endpoint detection
      const rms = calculateRms(pcmBytes);
      const isVoiceActive = rms >= 500;

      if (isVoiceActive) {
        inSpeech = true;
        silenceDurationMs = 0;
        continuousSpeechBytes += pcmBytes.byteLength;

        // Rolling Whisper partial every ~1.0 s of new speech (32,000 bytes)
        if (
          !isWhisperPartialRunning &&
          continuousSpeechBytes - lastPartialSpeechBytes >= 32000 &&
          utteranceByteLength >= 32000
        ) {
          lastPartialSpeechBytes = continuousSpeechBytes;
          runRollingWhisperPartial();
        }
      } else {
        if (inSpeech) {
          // Approximate frame duration: (sampleCount / 16000) * 1000
          const sampleCount = Math.floor(pcmBytes.byteLength / 2);
          const frameMs = Math.round((sampleCount * 1000) / 16000);
          silenceDurationMs += frameMs;

          // 600 ms below threshold = end of turn
          if (silenceDurationMs >= 600) {
            finishUtterance();
          }
        }
      }
      return;
    }

    // 2. Text frames: JSON control messages
    if (typeof event.data === "string") {
      let msg;
      try {
        msg = JSON.parse(event.data);
      } catch (_) {
        return;
      }

      switch (msg.type) {
        case "start":
          if (msg.language) sessionConfig.language = msg.language;
          if (msg.voiceName) sessionConfig.voiceName = msg.voiceName;
          if (msg.systemPrompt) sessionConfig.systemPrompt = msg.systemPrompt;
          if (msg.dateLine) sessionConfig.dateLine = msg.dateLine;
          resetListening();
          break;

        case "proceed":
          if (proceedResolver) {
            if (proceedTimer) {
              clearTimeout(proceedTimer);
              proceedTimer = null;
            }
            const res = proceedResolver;
            proceedResolver = null;
            res(msg.searchContext || null);
          }
          break;

        case "barge-in":
          abortActiveTurn();
          resetListening();
          break;

        case "endpoint":
          // Client VAD trigger: end turn if listening and has speech
          if (turnState === "LISTENING" && inSpeech && utteranceByteLength > 0) {
            finishUtterance();
          }
          break;

        case "stop":
          abortActiveTurn();
          try {
            ws.close(1000, "Client requested stop");
          } catch (_) {}
          break;
      }
    }
  });

  ws.addEventListener("close", () => {
    abortActiveTurn();
    resetListening();
  });

  ws.addEventListener("error", () => {
    abortActiveTurn();
    resetListening();
  });

  /**
   * Runs interim rolling Whisper partial on the last ~10s of accumulated audio.
   */
  async function runRollingWhisperPartial() {
    isWhisperPartialRunning = true;
    try {
      // Last ~10 seconds: max 320,000 bytes
      const maxRollingBytes = 320000;
      let startIdx = 0;
      let accumulated = 0;

      for (let i = utteranceFrames.length - 1; i >= 0; i--) {
        accumulated += utteranceFrames[i].byteLength;
        if (accumulated >= maxRollingBytes) {
          startIdx = i;
          break;
        }
      }

      const rollingChunks = utteranceFrames.slice(startIdx);
      const combined = concatByteArrays(rollingChunks);
      if (combined.byteLength < 16000) {
        // Less than 0.5s, skip
        isWhisperPartialRunning = false;
        return;
      }

      const text = await transcribeWithGroqWhisper(combined, groqKey, sessionConfig.language, AbortSignal.timeout(10000));
      if (text && text.trim().length > 0 && turnState === "LISTENING") {
        sendJson({ type: "partial", text: text.trim() });
      }
    } catch (_) {
      // Partial errors are silently ignored
    } finally {
      isWhisperPartialRunning = false;
    }
  }

  /**
   * Finalizes the current utterance, generates final transcript,
   * waits for proceed, and streams LLM + TTS.
   */
  async function finishUtterance() {
    if (turnState !== "LISTENING" || utteranceByteLength === 0) {
      return;
    }

    turnState = "PROCESSING";
    abortActiveTurn();
    activeTurnController = new AbortController();
    const turnSignal = activeTurnController.signal;

    const fullAudio = concatByteArrays(utteranceFrames);
    utteranceFrames = [];
    utteranceByteLength = 0;
    inSpeech = false;

    // Reject extremely short audio (< 300ms)
    if (fullAudio.byteLength < 9600) {
      resetListening();
      return;
    }

    try {
      // Final Whisper transcription
      let finalText = "";
      try {
        finalText = await transcribeWithGroqWhisper(
          fullAudio,
          groqKey,
          sessionConfig.language,
          turnSignal
        );
      } catch (err) {
        if (turnSignal.aborted) return;
        const errMsg = String(err.message || "");
        if (errMsg.includes("401") || errMsg.includes("403")) {
          sendJson({ type: "error", code: "auth_failed", message: "API key problem — check your keys in Settings" });
          resetListening();
          return;
        }
        sendJson({ type: "error", code: "transcribe_failed", message: "Voice transcription unavailable — try again" });
        resetListening();
        return;
      }

      const trimmedFinal = (finalText || "").trim();
      if (!trimmedFinal) {
        // Empty transcription (silence/cough)
        resetListening();
        return;
      }

      if (turnSignal.aborted) return;

      // Send final transcript to client
      sendJson({ type: "transcript", text: trimmedFinal });

      // Wait for client "proceed" handshake (searchContext) with 5s timeout
      const searchContext = await new Promise((resolve) => {
        proceedResolver = resolve;
        proceedTimer = setTimeout(() => {
          if (proceedResolver) {
            proceedResolver = null;
            resolve(null);
          }
        }, 5000);
      });

      if (turnSignal.aborted) return;

      // Stream LLM & Sentence-Chunked TTS
      await streamLlmAndTts(trimmedFinal, searchContext, turnSignal);
    } catch (err) {
      if (!turnSignal.aborted) {
        sendJson({ type: "error", code: "reply_failed", message: "Voice reply unavailable — try again" });
      }
    } finally {
      if (!turnSignal.aborted) {
        resetListening();
      }
    }
  }

  /**
   * Streams LLM chat response (Groq gpt-oss-120b with Gemini fallback)
   * and dispatches sentence-chunked Gemini TTS.
   */
  async function streamLlmAndTts(transcript, searchContext, signal) {
    const systemPromptText = sessionConfig.systemPrompt + (sessionConfig.dateLine ? `\n${sessionConfig.dateLine}` : "");
    const userPromptText = transcript + (searchContext ? `\n${searchContext}` : "");

    // Sentence chunking state
    let tokenBuffer = "";
    let sentenceIndex = 0;

    // Sequential TTS promise queue
    let ttsQueue = Promise.resolve();

    const dispatchSentenceTts = (sentenceText) => {
      const idx = sentenceIndex++;
      ttsQueue = ttsQueue.then(async () => {
        if (signal.aborted) return;
        try {
          const audioBase64 = await synthesizeSentenceAudio(
            geminiKey,
            sessionConfig.voiceName,
            sentenceText,
            signal
          );
          if (audioBase64 && !signal.aborted) {
            sendJson({
              type: "audio",
              data: audioBase64,
              sentence: idx,
            });
          }
        } catch (err) {
          // If a single TTS sentence fails, continue with next
        }
      });
    };

    const processToken = (token) => {
      if (signal.aborted || !token) return;
      sendJson({ type: "token", text: token });
      tokenBuffer += token;

      // Sentence boundaries: . ! ? । (Bengali dari \u0964) or newline
      // Require at least 25 characters to prevent tiny audio fragments
      const match = tokenBuffer.match(/([.!?\u0964\n])/);
      if (match && match.index !== undefined) {
        const delimIdx = match.index;
        if (delimIdx >= 24) {
          const sentence = tokenBuffer.slice(0, delimIdx + 1).trim();
          tokenBuffer = tokenBuffer.slice(delimIdx + 1);
          if (sentence.length > 0) {
            dispatchSentenceTts(sentence);
          }
        }
      }
    };

    let llmSuccess = false;

    // 1. Primary LLM: Groq openai/gpt-oss-120b streaming
    try {
      await streamGroqChat(groqKey, systemPromptText, userPromptText, processToken, signal);
      llmSuccess = true;
    } catch (groqErr) {
      if (signal.aborted) return;
      const msg = String(groqErr.message || "");
      if (msg.includes("401") || msg.includes("403")) {
        sendJson({ type: "error", code: "groq_failed", message: "API key problem — check your keys in Settings" });
        return;
      }
      // Non-auth error: fall back to Gemini streaming ladder
    }

    // 2. Fallback LLM: Gemini streaming ladder
    if (!llmSuccess && !signal.aborted) {
      try {
        await streamGeminiChat(geminiKey, systemPromptText, userPromptText, processToken, signal);
        llmSuccess = true;
      } catch (geminiErr) {
        if (signal.aborted) return;
        const msg = String(geminiErr.message || "");
        if (msg.includes("401") || msg.includes("403")) {
          sendJson({ type: "error", code: "gemini_failed", message: "API key problem — check your keys in Settings" });
          return;
        }
        sendJson({ type: "error", code: "gemini_failed", message: "Chat service unavailable — please try again" });
        return;
      }
    }

    if (signal.aborted) return;

    // Flush any remaining tokens as the final sentence
    const remainingText = tokenBuffer.trim();
    if (remainingText.length > 0) {
      dispatchSentenceTts(remainingText);
    }

    // Wait for all queued TTS sentences to complete
    await ttsQueue;

    if (!signal.aborted) {
      sendJson({ type: "reply-done" });
    }
  }
}

/**
 * Calls Groq Whisper transcription API.
 */
async function transcribeWithGroqWhisper(pcmBytes, groqKey, language, signal) {
  const wavHeader = createWavHeader(pcmBytes.byteLength, 16000, 1, 16);
  const wavBlob = new Blob([wavHeader, pcmBytes], { type: "audio/wav" });

  const formData = new FormData();
  formData.append("file", wavBlob, "audio.wav");
  formData.append("model", "whisper-large-v3-turbo");

  const langLower = (language || "").toLowerCase();
  if (langLower.includes("bangla") || langLower.includes("bengali") || langLower === "bn") {
    formData.append("language", "bn");
  }

  const response = await fetch("https://api.groq.com/openai/v1/audio/transcriptions", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${groqKey}`,
    },
    body: formData,
    signal,
  });

  if (!response.ok) {
    throw new Error(`Groq Whisper error HTTP ${response.status}`);
  }

  const data = await response.json();
  return data.text || "";
}

/**
 * Sleep helper that respects AbortSignal.
 */
function sleep(ms, signal) {
  return new Promise((resolve, reject) => {
    if (signal && signal.aborted) {
      return reject(new Error("Aborted"));
    }
    let timer = null;
    const onAbort = () => {
      if (timer) clearTimeout(timer);
      reject(new Error("Aborted"));
    };
    timer = setTimeout(() => {
      if (signal) signal.removeEventListener("abort", onAbort);
      resolve();
    }, ms);
    if (signal) {
      signal.addEventListener("abort", onAbort, { once: true });
    }
  });
}

/**
 * Streams Groq openai/gpt-oss-120b chat completions via SSE.
 * Retries once on transient 429/5xx with ~1s pause (never on 400/401/403).
 */
async function streamGroqChat(groqKey, systemPrompt, userPrompt, onToken, signal) {
  const requestBody = JSON.stringify({
    model: "openai/gpt-oss-120b",
    messages: [
      { role: "system", content: systemPrompt },
      { role: "user", content: userPrompt },
    ],
    temperature: 0.7,
    reasoning_effort: "low",
    max_completion_tokens: 600,
    stream: true,
  });

  let response;
  for (let attempt = 0; attempt < 2; attempt++) {
    if (signal.aborted) throw new Error("Aborted");

    try {
      response = await fetch("https://api.groq.com/openai/v1/chat/completions", {
        method: "POST",
        headers: {
          Authorization: `Bearer ${groqKey}`,
          "Content-Type": "application/json",
        },
        body: requestBody,
        signal,
      });
    } catch (err) {
      if (signal.aborted) throw err;
      if (attempt === 0) {
        await sleep(1000, signal);
        continue;
      }
      throw err;
    }

    if (response.ok) {
      break;
    }

    const status = response.status;
    // Auth errors: 401, 403; Bad request: 400. Do NOT retry on these.
    if (status === 400 || status === 401 || status === 403) {
      throw new Error(`Groq LLM error HTTP ${status}`);
    }

    // Transient 429 / 5xx: retry ONCE
    const isTransient = status === 429 || (status >= 500 && status < 600);
    if (attempt === 0 && isTransient && !signal.aborted) {
      await sleep(1000, signal);
      continue;
    }

    throw new Error(`Groq LLM error HTTP ${status}`);
  }

  if (!response || !response.ok) {
    throw new Error(`Groq LLM error HTTP ${response ? response.status : "unknown"}`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";

  while (!signal.aborted) {
    const { done, value } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split("\n");
    buffer = lines.pop() || "";

    for (const rawLine of lines) {
      const line = rawLine.trim();
      if (!line.startsWith("data:")) continue;
      const dataStr = line.slice(5).trim();
      if (dataStr === "[DONE]") return;

      try {
        const parsed = JSON.parse(dataStr);
        const delta = parsed.choices?.[0]?.delta?.content;
        if (delta) {
          onToken(delta);
        }
      } catch (_) {}
    }
  }
}

/**
 * Streams Gemini chat completions via SSE as fallback.
 * Retries transient 429/503 up to 2 times (~1.5s backoff),
 * walking the model ladder: gemini-3.8-flash -> gemini-3.6-flash -> gemini-3.5-flash-lite.
 */
async function streamGeminiChat(geminiKey, systemPrompt, userPrompt, onToken, signal) {
  const models = ["gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.5-flash-lite"];
  let lastError = null;

  for (const model of models) {
    if (signal.aborted) throw new Error("Aborted");

    const url = `https://generativelanguage.googleapis.com/v1beta/models/${model}:streamGenerateContent?alt=sse&key=${geminiKey}`;
    const requestBody = JSON.stringify({
      systemInstruction: {
        parts: [{ text: systemPrompt }],
      },
      contents: [
        {
          role: "user",
          parts: [{ text: userPrompt }],
        },
      ],
      generationConfig: {
        temperature: 0.7,
        maxOutputTokens: 600,
      },
    });

    for (let attempt = 0; attempt <= 2; attempt++) {
      if (signal.aborted) throw new Error("Aborted");

      let response;
      try {
        response = await fetch(url, {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "x-goog-api-key": geminiKey,
          },
          body: requestBody,
          signal,
        });
      } catch (err) {
        if (signal.aborted) throw err;
        lastError = err;
        if (attempt < 2) {
          await sleep(1500, signal);
          continue;
        }
        break; // retries exhausted for this model, walk ladder
      }

      if (response.ok) {
        const reader = response.body.getReader();
        const decoder = new TextDecoder("utf-8");
        let buffer = "";

        while (!signal.aborted) {
          const { done, value } = await reader.read();
          if (done) break;

          buffer += decoder.decode(value, { stream: true });
          const lines = buffer.split("\n");
          buffer = lines.pop() || "";

          for (const rawLine of lines) {
            const line = rawLine.trim();
            if (!line.startsWith("data:")) continue;
            const dataStr = line.slice(5).trim();

            try {
              const parsed = JSON.parse(dataStr);
              const textChunk = parsed.candidates?.[0]?.content?.parts?.[0]?.text;
              if (textChunk) {
                onToken(textChunk);
              }
            } catch (_) {}
          }
        }
        return; // Success!
      }

      const status = response.status;
      lastError = new Error(`Gemini stream error HTTP ${status}`);

      // Auth error (401, 403): abort immediately, do NOT retry or walk ladder
      if (status === 401 || status === 403) {
        throw lastError;
      }

      // Transient 429 or 503: retry up to 2 times with ~1.5s backoff
      const isTransient = status === 429 || status === 503;
      if (attempt < 2 && isTransient && !signal.aborted) {
        await sleep(1500, signal);
        continue;
      }

      // Non-transient error or retries exhausted: move to next model in ladder
      break;
    }
  }

  throw lastError || new Error("Gemini chat fallback failed across all models");
}

/**
 * Synthesizes speech using the Gemini TTS model ladder:
 * 1. gemini-3.8-flash-tts
 * 2. gemini-3.8-flash-lite-tts
 * Returns Base64 PCM 24kHz audio string directly.
 */
async function synthesizeSentenceAudio(geminiKey, voiceName, text, signal) {
  const cleanText = text.replace(/[*#_`~]/g, "").trim();
  if (!cleanText) return null;

  const ttsModels = ["gemini-3.8-flash-tts", "gemini-3.8-flash-lite-tts"];

  for (const model of ttsModels) {
    if (signal.aborted) return null;
    try {
      const url = `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${geminiKey}`;
      const response = await fetch(url, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "x-goog-api-key": geminiKey,
        },
        body: JSON.stringify({
          contents: [{ parts: [{ text: cleanText }] }],
          generationConfig: {
            responseModalities: ["AUDIO"],
            speechConfig: {
              voiceConfig: {
                prebuiltVoiceConfig: {
                  voiceName: voiceName || "Aoede",
                },
              },
            },
          },
        }),
        signal,
      });

      if (!response.ok) continue;

      const data = await response.json();
      const parts = data.candidates?.[0]?.content?.parts;
      if (parts && parts.length > 0) {
        for (const p of parts) {
          if (p.inlineData && p.inlineData.data) {
            return p.inlineData.data;
          }
        }
      }
    } catch (_) {
      // Try next model in ladder
    }
  }

  return null;
}

/**
 * Calculates RMS amplitude for 16-bit mono PCM.
 */
function calculateRms(pcmBytes) {
  const sampleCount = Math.floor(pcmBytes.byteLength / 2);
  if (sampleCount === 0) return 0;
  const view = new DataView(pcmBytes.buffer, pcmBytes.byteOffset, pcmBytes.byteLength);

  let sumSquares = 0;
  for (let i = 0; i < sampleCount; i++) {
    const sample = view.getInt16(i * 2, true); // Little-endian
    sumSquares += sample * sample;
  }
  return Math.sqrt(sumSquares / sampleCount);
}

/**
 * Creates canonical 44-byte WAV header for 16-bit PCM.
 */
function createWavHeader(dataLength, sampleRate = 16000, numChannels = 1, bitsPerSample = 16) {
  const buffer = new ArrayBuffer(44);
  const view = new DataView(buffer);
  const byteRate = (sampleRate * numChannels * bitsPerSample) / 8;
  const blockAlign = (numChannels * bitsPerSample) / 8;

  // "RIFF"
  view.setUint32(0, 0x52494646, false);
  // File size - 8
  view.setUint32(4, 36 + dataLength, true);
  // "WAVE"
  view.setUint32(8, 0x57415645, false);
  // "fmt "
  view.setUint32(12, 0x666d7420, false);
  // Subchunk1Size (16 for PCM)
  view.setUint32(16, 16, true);
  // AudioFormat (1 for PCM)
  view.setUint16(20, 1, true);
  // NumChannels
  view.setUint16(22, numChannels, true);
  // SampleRate
  view.setUint32(24, sampleRate, true);
  // ByteRate
  view.setUint32(28, byteRate, true);
  // BlockAlign
  view.setUint16(32, blockAlign, true);
  // BitsPerSample
  view.setUint16(34, bitsPerSample, true);
  // "data"
  view.setUint32(36, 0x64617461, false);
  // Subchunk2Size
  view.setUint32(40, dataLength, true);

  return new Uint8Array(buffer);
}

/**
 * Concatenates an array of Uint8Arrays into one Uint8Array.
 */
function concatByteArrays(arrays) {
  let totalLength = 0;
  for (const arr of arrays) {
    totalLength += arr.byteLength;
  }
  const result = new Uint8Array(totalLength);
  let offset = 0;
  for (const arr of arrays) {
    result.set(arr, offset);
    offset += arr.byteLength;
  }
  return result;
}
