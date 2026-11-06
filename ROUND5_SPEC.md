# Mira AI — Round 5 Spec (2026-11-06)

Repo: https://github.com/ryoaonetsuki/mira-ai-voice-assistant — package io.github.ryoaonetsuki.mira.
Local tree: ~/workspace/mira-final/ (you are already inside it; edit files HERE, do NOT push — pushing/building is done by the orchestrator afterwards).
Build gate (orchestrator): GitHub Actions workflow must be GREEN; debug APK aapt2-verified.

## THE BUG (Salman's screenshot, Setup step "Add Your API Key")
Test & Save failed with red error:
"generateContent check failed: HTTP 503: This model is currently experiencing high demand. Spikes in demand are usually temporary. Please try again later."
His AQ. key is entered (dots shown). The setup validation hard-blocks him from entering the app on a TRANSIENT 503 overload. A busy Google server must NEVER look like a bad key and must NEVER block entry.

## Verified current code (confirm in code as you implement)
- `network/GeminiRestClient.kt` `validateApiKeyAndChat()`:
  - Step 1: models-list check, single attempt, any failure hard-fails.
  - Step 2: ONE generateContent call on `gemini-3.8-flash` only (line ~388), no retry, no ladder.
  - Failure message is `generateContent check failed: <reason>` — a 503 reads as a key problem.
- `generateChatResponse()` / `generateChatResponseStreaming()` already walk the ladder
  `gemini-3.8-flash → gemini-3.6-flash → gemini-3.5-flash-lite`, but `executeGenerateContent()`
  has NO retry/backoff: one transient 503/429 on a model kills that model immediately.
- `ui/screens/SetupScreen.kt` "Test & Save" button (~line 261): on ANY validation failure it
  shows the error in red and stays on step 1 — no save, no entry into the app.

## TASK 1 — Fix the 503 block (top priority)
In `GeminiRestClient.kt`, introduce a small typed validation outcome instead of plain
`Result<Boolean>` for the setup check (keep `validateApiKey()` delegating so existing callers compile):

1a. Retry transient errors with backoff. Treat HTTP 503, 429 and 500/502/504, plus
    "overloaded" / "high demand" / "UNAVAILABLE" in the message, as TRANSIENT. Retry each
    generateContent attempt up to 3 times with backoff (e.g. ~2s, ~5s, ~10s — total ~30s max
    per model) before giving up on that model. Apply the same retry inside
    `executeGenerateContent()` (or a wrapper used by chat) so chat/voice REST calls also retry.

1b. Ladder the validation check. Step 2 must try the full chat ladder in order:
    `gemini-3.8-flash → gemini-3.6-flash → gemini-3.5-flash-lite` (reuse `chatModelLadder`),
    with the 1a retry on each model. If 3.8-flash is overloaded, 3.6-flash or the lightest
    3.5-flash-lite must get a chance. Also make Step 1 (models-list) retry transient
    503/429 once or twice with short backoff instead of failing instantly; a transient
    failure in Step 1 must NOT hard-fail validation when Step 2 can still prove the key.

1c. NEVER hard-block saving on a transient server error. New outcome kinds:
    - VALID: key works → save + enter app (as today).
    - TRANSIENT / SERVER BUSY: key format is valid (non-blank, starts with `AQ.` for new
      keys — note: also accept other plausible key formats, do not over-restrict) but every
      ladder model returned only transient errors (503/429/overloaded) after retries →
      SAVE the key anyway and let the user into the app, with a clear notice.
    - INVALID KEY: only hard-block on real auth errors — HTTP 400/401/403, "API key not
      valid", "invalid key", permission-denied style errors → do NOT save, show red error.
    - NETWORK: no connectivity / timeout after retries → also do NOT hard-block on a
      format-valid key? NO — network failure is ambiguous: show a clear "couldn't reach
      Google — check connection, key saved so you can retry inside the app" path ONLY if
      you can distinguish it; otherwise treat like TRANSIENT (save + notice) since the key
      format was valid. Keep it simple: format-valid key + only transient/network errors
      after full ladder+retries = save + notice + enter app.

1d. Error text must say plainly whether it is a KEY problem or GOOGLE being busy — never
    make a busy-server error look like a bad key. Exact user-facing strings (use these):
    - Transient save notice (green/amber, NOT red): "Google's servers are busy right now — key saved, try again in a bit"
    - Invalid key (red): "This API key looks invalid — check the key and try again (Google said: <short reason>)"
    - Busy-server hard failure (only if saving somehow impossible): "Google's servers are busy right now (HTTP 503 — high demand). Your key was NOT rejected — please try again in a bit."
    Add a helper e.g. `isTransientError(statusCode, message)` and `isAuthError(statusCode, message)`
    in GeminiRestClient.kt and use it in BOTH validation and chat error paths so chat errors
    also say "Google is busy" vs "key problem" (chat already surfaces raw errors; at minimum
    prefix/annotate the final chat failure message the same way).

## TASK 2 — SetupScreen wiring
Update the "Test & Save" handler in `SetupScreen.kt` to the new outcome type:
- VALID → save encrypted + "Valid API Key! Stored encrypted on device." + advance to step 2 (as today).
- TRANSIENT (saved) → save encrypted + show the busy notice (amber/green, NOT red) + advance
  to step 2 so he can enter the app. The message colour must reflect "saved, not an error".
- INVALID → red invalid-key message, stay on step 1, do not save.
- While validating, the button shows progress; validation may now take up to ~30-60s across
  ladder+retries — update the in-progress text to "Validating key with Google... (retrying if Google is busy)" so he knows waiting is normal. Do NOT add a fake/demo key path, do NOT skip validation entirely, do NOT change the AQ. hint text.
- Check whether `PersonalSettingsScreen.kt` has a similar key re-validation flow and apply the
  same outcome handling there if it does (same strings, same save-on-transient rule).

## TASK 3 — Keep everything from rounds 1–4 intact
Do not touch: Gemini Live client, fallback voice, diagnostics card, file upload, Web Search /
Deep Thinking toggles, Lucide icons, Bangla default + one-language replies, file-permission
gate, date injection, Supabase backend, home layout (cards stay, big orb, chips just above
bottom nav). No placeholders, no fake content, no 'Coming soon'. Only the files needed for
tasks 1–2 (plus PersonalSettingsScreen if it re-validates).

## Verification (do it before finishing)
- Run a compile check if a local Gradle build is feasible; at minimum re-read every edited
  file fully and confirm: no unresolved references, no changed public signatures that break
  other callers (search all callers of validateApiKey / validateApiKeyAndChat /
  executeGenerateContent / generateChatResponse and fix them), Kotlin compiles logically.
- Report exactly: which files changed, what the check was using before, what changed,
  and the new user-visible strings.
