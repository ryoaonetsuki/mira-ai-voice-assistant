# Third-Party Notices

## Mika
- Repository: https://github.com/wangzan101/Mika
- License: Apache License 2.0
- Copyright: Copyright 2026 wangzan101

Ported files (adapted for Mira):
- `app/src/main/java/io/github/ryoaonetsuki/mira/automation/agent/MikaScreenReader.kt`
  (from `app/src/main/java/com/wangzan101/mika/cap/phone/a11y/ScreenReader.kt`)
- `app/src/main/java/io/github/ryoaonetsuki/mira/automation/agent/FrameGrid.kt`
  (from `core/src/main/kotlin/com/wangzan101/mika/core/visual/FrameGrid.kt` + `FrameTuning.kt`)
- `app/src/main/java/io/github/ryoaonetsuki/mira/automation/agent/MikaAccuracy.kt`
  (from `core/src/main/kotlin/com/wangzan101/mika/core/uistate/Accuracy.kt`)
- `app/src/main/java/io/github/ryoaonetsuki/mira/automation/agent/MikaPacing.kt`
  (from `core/src/main/kotlin/com/wangzan101/mika/core/cap/phone/TypingPacing.kt` + `GesturePacing.kt`)
- `app/src/main/java/io/github/ryoaonetsuki/mira/automation/agent/MikaReliableActions.kt`
  (from `app/src/main/java/com/wangzan101/mika/cap/phone/a11y/AndroidScreen.kt` reliability core)
- `app/src/main/java/io/github/ryoaonetsuki/mira/automation/agent/MikaOperatingPace.kt`
  (from `core/src/main/kotlin/com/wangzan101/mika/core/cap/phone/AppPacing.kt` + `OperatingPace.kt`)
- `app/src/main/java/io/github/ryoaonetsuki/mira/automation/agent/MiraScreenPort.kt`
  (from `core/src/main/kotlin/com/wangzan101/mika/core/uistate/ScreenPort.kt`)
- `app/src/main/java/io/github/ryoaonetsuki/mira/automation/agent/MiraAndroidScreen.kt`
  (from `app/src/main/java/com/wangzan101/mika/cap/phone/a11y/AndroidScreen.kt`, reliability
  core + ScreenPort implementation, virtual display / shell / multi-profile dropped)

Not yet ported: wiring the ported modules into Mira's decision loop (next chunk).
