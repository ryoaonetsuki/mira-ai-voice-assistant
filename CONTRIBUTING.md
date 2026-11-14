# Contributing to Mira AI

Contributions are welcome — thank you for helping make Mira smarter. This guide is the
short version: fork, branch, small focused PR, green CI.

## How to contribute

1. **Fork** the repo and create a branch from `main`:
   `feature/<short-name>` for features, `fix/<short-name>` for fixes.
2. Make **one focused change per PR** — one bug, one feature, one improvement. Small PRs
   get reviewed and merged fastest.
3. **Test it.** Add or update unit tests for behaviour you change, and run them locally
   if you can (`./gradlew testDebugUnitTest`). CI runs the full suite on every PR — it
   must be green.
4. Open a **pull request against `main`** with:
   - what you changed and why,
   - how you tested it (tests added, and phone-test evidence if you ran the APK —
     model + Android version + what you tried),
   - any behaviour that intentionally changed.
5. The maintainer reviews, may ask for changes, and merges. A phone test by the
   maintainer is the final verdict — code that compiles is not proof it works on a
   real phone.

## Code standards

- **Kotlin + Jetpack Compose**, following the existing code style. No new languages or
  frameworks in a drive-by PR.
- **Fluent icons** app-wide (Regular base, Filled for active/selected).
- **Every option must work.** No placeholders, fake UI, demo content, or dead code —
  anything that does not work gets removed, not shipped.
- **Generic fixes, never per-app patches.** Automation fixes must work for every app and
  every screen, not one app's flow.
- **Replies stay in one language** (the user's chosen language, Bangla by default; the UI
  itself stays in English). Never add a translation underneath an answer.
- **Mira branding in the UI.** User-visible names say Mira (for example "Mira Live",
  "Mira voices"). Do not put other companies' brand names in user-facing labels.
  Exceptions: the setup page keeps its "Gemini API" labels exactly (the user must
  understand what key to paste, including the link telling them where to obtain it);
  code identifiers, model strings (like `gemini-3.8-live`), and third-party credits.
- **No secrets in PRs.** Never commit API keys, tokens, keystores, `google-services.json`,
  or credentials of any kind. The app is BYOK — keys live on the user's device, encrypted.
- **Privacy:** conversation history and learned memory stay on the phone. Do not send
  them to any server.

## Third-party code

- Only **MIT- or Apache-2.0-compatible** code may be added. No AGPL, GPL, LGPL, or
  proprietary code, and no code copied from a live website — take it unchanged from the
  project's own GitHub repository, with attribution.
- When you add third-party code, update [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
  in the same PR (project, licence, what was taken).

## Reporting bugs

Open an issue with:

- the build (CI run id or commit SHA) and the APK you tested,
- device model + Android version,
- exact steps to reproduce, what you expected, what actually happened,
- a logcat excerpt if you have one (redact any keys first).

Feature ideas are welcome as issues too — describe the task it should do, not just the
feature name.

## Code of conduct

Be kind and honest in reviews and issues. Assume good intent, disagree about the code
rather than the person, and don't claim something works until it has actually been
tested.
