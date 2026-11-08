You are implementing Mira AI ROUND 11 in this repo (~/workspace/mira-final, package io.github.ryoaonetsuki.mira, Kotlin + Jetpack Compose, Material icons only). Read ROUND11_SPEC.md in this repo and implement it EXACTLY — nothing else. Do not re-diagnose the keys or the model IDs; the root causes are already verified in the spec (Groq model ID per Groq's own docs, Gemini model names live-verified via the models list).

Summary of what to do (full detail is in ROUND11_SPEC.md — follow that file as the authority):

FIX A — WORKER LLM STAGE (cloudflare-relay/server-voice-worker.js ONLY):
- streamGroqChat: model "openai/gpt-oss-120b" (NOT "gpt-oss-120b"), add reasoning_effort "low", replace max_tokens: 600 with max_completion_tokens: 600. Retry the Groq call ONCE on transient 429/5xx only (never on 400/401/403).
- streamGeminiChat: retry transient 429/503 up to 2 times (~1.5s backoff), then walk the ladder gemini-3.8-flash → gemini-3.6-flash → gemini-3.5-flash-lite. Keep auth-error handling (401/403 → "API key problem — check your keys in Settings").
- Add an error `code` field to error frames for logcat diagnostics; keep the human messages plain. Never log keys, transcripts, or audio. Bump health version to "2.0.1".
- Do NOT change: Whisper call, TTS ladder, WS handshake/key validation, endpoint detection, barge-in, proceed handshake, CORS, health endpoint.

FIX B — HOME NAME (ui/screens/HomeScreen.kt ONLY):
- DELETE the whole greeting Text block ("$greeting, $userName", ~lines 161–178) from Home — the user's name must not render anywhere on the Home screen. Remove the now-unused userName flow collection and greeting computation if nothing else uses them.
- The name stays stored in UserPreferences. Verify every other place the name is shown fits (maxLines(1) + TextOverflow.Ellipsis in a width-constrained layout) and never clips/breaks its screen.
- Nothing else on Home changes: 3 cards, orb, one status line, "Tap the voice button to start" when idle.

DEPLOY + BUILD (you do all of this, then report):
1. Deploy the Worker via the Cloudflare API: ~/workspace/custom-connectors/custom.legacy-cloudflare/bin/cf.py, PUT /accounts/5577ea99b7437d6aeadaf0a840c43ce2/workers/scripts/mira-server-voice with cloudflare-relay/server-voice-worker.js. Then live-verify: health 200 with version 2.0.1, WS without keys → 401. No test API key exists on this VM — do NOT invent one, do NOT use any key found anywhere.
2. Push the app with ~/workspace/mira-final/tools/push.py (NOT ~/workspace/tools/push_mira.py), build via GitHub Actions, download the APK with tools/gh_actions.py to ~/workspace/user/files/mira-ai-debug.apk. Verify the APK with aapt2 yourself (package io.github.ryoaonetsuki.mira, versionName 1.0.0, targetSdk 36) and report its size + md5.

HARD RULES:
- NEVER call relay.getmaya.online, api.getmaya.cloud, or any Maya/Hunter host. grep must show zero "getmaya" matches afterwards.
- Keep everything from rounds 1–10 intact (see ROUND11_SPEC.md hard-rules list).
- Do NOT add the notification features (call-style notification, task-completion notification) — those are the NEXT update.
- Do NOT build a release APK, do NOT touch signing keys.

VERIFY BEFORE YOU FINISH (run these yourself and report the results):
1. grep -ri "getmaya" app/src cloudflare-relay — must be zero matches.
2. grep the Worker: model is "openai/gpt-oss-120b", reasoning_effort "low", max_completion_tokens present, Gemini ladder present, health version "2.0.1".
3. Re-read the changed hunks: HomeScreen.kt has no userName rendering on Home; no other screen shows the name unclipped.
4. Worker health live-verified 200 (version 2.0.1) after deploy; WS without keys → 401.
5. GitHub Actions run number + conclusion, APK path/size/md5, aapt2 output.
6. End your reply with a per-fix summary (FIX A, FIX B, deploy, build): every file changed + what changed in it, one line each. Then list what still needs Salman's phone test. Do NOT claim voice works.
