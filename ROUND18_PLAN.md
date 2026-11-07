# Round 18 Comprehensive Implementation Plan: Premium UI Overhaul & DeepSeek Chat UX

## Executive Overview

Round 18 elevates Mira AI from a functional Android assistant to a flagship, premium application matching the latest 2026 ChatGPT and DeepSeek design standards. This plan covers:
1. **Unified Theme Token System**: Colors, typography, shapes, and motion tokens built on Material 3 Expressive and Compose-expert principles.
2. **Per-Screen UI Overhaul Checklist**: Detailed refinements for all 14 app screens, 12 settings screens, AppTopBar, AppDrawer, and the floating BottomNavBar.
3. **DeepSeek-Style Chat UX**: Visible thinking accordion with real-time steps, progressive line-by-line streaming, removal of AI message bubble boxes (clean flowing markdown text), copyable code blocks, localhost hosting for interactive previews, download links, and a strict "just code" output mode.
4. **Image-Gen % Badge Fix**: Moving the percentage badge from the card center to the bottom-right corner as shown in the ChatGPT "Sketching it out" reference.
5. **Admin Panel Investigation & Remediation**: Diagnosis of the 302 Zero Trust redirect on `mira-admin.pages.dev`, verification of Supabase auth and embedded keys in `dist/`, and the deployment fix.
6. **File-by-File Implementation Breakdown**: Comprehensive file mapping with exact composable, state, and function modifications.
7. **Verification & Guardrails**: Strict enforcement of voice code freezes, AnimatedOrb signature preservation, unit tests, and build checks.

---

## 1. Theme Token System

The design system follows Brad Frost's Atomic Design mapped to Jetpack Compose primitives (Tokens → Atoms → Molecules → Organisms → Templates). All visual attributes are hoisted into theme tokens rather than hardcoded in individual composables.

### 1.1 Color Palette & Semantic Roles (`Color.kt` & `Theme.kt`)
The app retains its 7 distinct user-selectable palettes (`Midnight`, `Obsidian`, `Nocturne`, `Ember`, `Abyss`, `Rosewood`, `Daylight`), while adding semantic role tokens to `MiraPalette`:

| Semantic Token | Purpose / Role | Default Value (`Midnight`) |
|---|---|---|
| `primary` | Interactive accents, active icons, glowing rings | `#22D3EE` (Cyan Accent) |
| `secondary` | Secondary brand accents, subtle highlights | `#8B5CF6` (Violet Accent) |
| `background` | Full-screen canvas background | `#080D16` |
| `surface` | Cards, sheets, dialog surfaces | `#101A29` |
| `surfaceVariant` | Elevated pills, chips, input field backgrounds | `#19283B` |
| `surfaceSubtle` | Muted card backgrounds, secondary containers | `Color(0xFF0D1522)` |
| `borderSubtle` | 1dp card and chip borders | `Color(0xFF1E2F45)` |
| `textPrimary` | High-emphasis body, titles, headings | `#F2F7FC` |
| `textSecondary` | Muted subtitles, timestamps, placeholders | `#A9B9CC` |
| `codeBackground` | Background for code block container | `Color(0xFF121824)` |
| `codeHeader` | Header bar for code blocks | `Color(0xFF0A0F17)` |
| `codeBorder` | Subtle border around code containers | `Color(0xFF1F2C3F)` |
| `thinkingBorder` | Vertical accent line for thinking accordion | `Color(0xFF22D3EE).copy(alpha = 0.4f)` |
| `thinkingSurface` | Background for expanded thinking state | `Color(0xFF101A29).copy(alpha = 0.5f)` |

### 1.2 Typography Hierarchy (`Type.kt`)
Typography is standardized using a proportional modular scale with balanced line heights to prevent text clipping and ensure readability:

- **Display / Header 1 (`headlineLarge`)**: `30.sp`, line height `36.sp`, `FontWeight.Bold`, letter spacing `-0.4.sp`. Screen heroes.
- **Header 2 (`headlineMedium`)**: `22.sp`, line height `28.sp`, `FontWeight.SemiBold`, letter spacing `-0.2.sp`. Section titles.
- **Title Medium (`titleMedium`)**: `16.sp`, line height `22.sp`, `FontWeight.SemiBold`. Card headers, dialog titles.
- **Body Large (`bodyLarge`)**: `15.sp`, line height `24.sp`, `FontWeight.Normal`, letter spacing `0.1.sp`. Chat flowing text, general copy.
- **Body Medium (`bodyMedium`)**: `13.5.sp`, line height `20.sp`, `FontWeight.Normal`. Supporting descriptions.
- **Code Monospace (`bodySmall`)**: `12.5.sp`, line height `18.sp`, `FontFamily.Monospace`. Inside code blocks.
- **Label / Pill (`labelSmall`)**: `11.sp`, line height `15.sp`, `FontWeight.Medium`, letter spacing `0.3.sp`. Badges, metadata tags, model pills.

### 1.3 Shapes Scale
Corner radii are normalized across all components using consistent tokens:
- `ExtraSmall`: `4.dp` (Code preview tags, micro badges)
- `Small`: `8.dp` (Dropdown items, nested chips)
- `Medium`: `12.dp` (Input containers, interactive chips, message pills)
- `Large`: `16.dp` (Standard cards, dialog containers, code cards)
- `ExtraLarge`: `24.dp` (Progress cards, prominent surfaces)
- `Pill`: `999.dp` / `CircleShape` (Action buttons, floating bottom bar, avatar badges)

### 1.4 Motion Tokens (`MiraMotion.kt`)
Built on Material 3 Motion tokens and Expressive motion specifications:
- **Spatial Spring**: `spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)` for drawer slide, modal sheets, and layout bounds adjustments.
- **Effects Spec**: `tween(durationMillis = 200, easing = FastOutSlowInEasing)` for color transitions, icon states, and opacity.
- **Thinking Shimmer**: `infiniteRepeatable(animation = tween(1200, easing = LinearEasing), repeatMode = RepeatMode.Restart)` for the streaming thinking pulse.
- **Accordion Expand**: `expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn()` for expanding thought steps and code blocks.

---

## 2. Per-Screen Overhaul Checklist

Every screen in the application is audited and restyled to eliminate size, fitting, position, color, and style mismatches, while strictly retaining Fluent UI icons (Regular base, Filled active).

### 2.1 Navigation & Global Shell
- [ ] **AppTopBar (`ui/components/AppTopBar.kt`)**:
  - Unify height to standard 64dp with 16dp horizontal edge padding.
  - Navigation drawer button: `ic_fluent_navigation_24_regular` with 22dp size.
  - Notification icon: `ic_fluent_alert_24_regular`. Redesign unread badge: 16dp pill with `palette.primary` background and `palette.background` text (replacing harsh black text).
  - Avatar: Circular 36dp container with subtle 1dp border (`palette.surfaceVariant`) and ripple feedback on tap.
- [ ] **AppDrawer (`ui/components/AppDrawer.kt`)**:
  - Drawer sheet width: 300dp with smooth spring slide transition.
  - Drawer item: 44dp height, 12dp rounded corner shape, 12dp horizontal padding.
  - Active item: Background `palette.surfaceVariant.copy(alpha = 0.7f)`, text `palette.primary`, icon filled (`item.filledIcon`).
  - Inactive item: Background transparent, text `palette.textPrimary`, icon regular (`item.regularIcon`).
  - Dividers: Replace harsh borders with `palette.surfaceVariant.copy(alpha = 0.35f)` 1dp horizontal rules.
- [ ] **HomeBottomNavBar (`ui/screens/HomeScreen.kt`)**:
  - Keep 68dp floating pill container with 30dp corner radius and subtle shadow elevation (12dp).
  - Navigation items: 5 slots evenly spaced (Home, Vision, Voice, Memory, Chat).
  - Voice button: Keep central 56dp circular gradient button with `ic_fluent_mic_24_regular` / `ic_fluent_stop_24_filled`. Preserved completely.
  - Tabs: Fluent regular when unselected, Fluent filled when selected with `palette.primary` tint and 2dp indicator dot below label.

### 2.2 Core Screens
- [ ] **HomeScreen (`ui/screens/HomeScreen.kt`)**:
  - **AnimatedOrb constraint**: The home orb stays **EXACTLY in place**. Do NOT modify `AnimatedOrb` signature or call-site arguments (`state = orbDisplayState, amplitude = ..., orbStyle = orbStyle`). Restyle layout strictly around it.
  - Top info cards: Harmonize Weather Card and Date Card to identical 84dp height, 14dp rounded corners, `palette.surface` background with 1dp `borderSubtle`.
  - Remote config banner: Restyle with smooth fade-in, 12dp corner radius, and subtle primary glow.
  - Status text under orb: Align center, 13sp medium, `palette.textSecondary`, with gentle crossfade between states.
- [ ] **ChatScreen (`ui/screens/ChatScreen.kt`)**:
  - Full DeepSeek / ChatGPT overhaul (detailed in Section 3).
- [ ] **ScanScreen (`ui/screens/ScanScreen.kt`)**:
  - Camera preview: Full bleed layout with proper aspect ratio handling (`PreviewView`).
  - Top controls: Floating pill container holding camera flip (`ic_fluent_camera_switch_24_regular`) and flash mode (`ic_fluent_flash_24_regular`).
  - Mode selector: Floating horizontal pill slider switching smoothly between "Vision" and "Scan (QR/Barcode)" modes with animated slide pill.
  - Shutter button: 72dp concentric circular ring with spring tap scale animation.
  - Vision prompt input: Floating pill text input at screen bottom when photo is captured, with thumbnail preview chip and send button.
- [ ] **MemoriesScreen (`ui/screens/MemoriesScreen.kt`)**:
  - Top bar: Fluent search input with clear button and biometric lock icon if Face Lock active.
  - Category filter row: Horizontal scrolling chips (`All`, `Personal`, `Work`, `Preferences`) with animated pill indicator.
  - Memory cards: Surface cards with 14dp corners, timestamp tag, subtle pin action, and swipe-to-delete.
  - Empty state: Clean illustrated placeholder with `ic_fluent_sparkle_24_regular` and "No memories saved yet".
- [ ] **RulesScreen (`ui/screens/RulesScreen.kt`)**:
  - Header: Clean informational banner explaining how active rules guide Mira's responses.
  - Rule cards: Surface container with title, description, custom toggle switch, and delete confirmation dialog.
  - Floating action button: Extended pill "Add Rule" with `ic_fluent_add_24_regular`.
- [ ] **CodeProjectsScreen (`ui/screens/CodeProjectsScreen.kt`)**:
  - Clean project list / grid displaying generated websites and scripts.
  - Project cards: Language/type icon (`ic_fluent_globe_24_regular` for web, `ic_fluent_code_24_regular` for scripts), file count, last modified date.
  - Action buttons: "Open in Browser" (starts localhost server) and "Export / Share".
- [ ] **WhiteboardScreen (`ui/screens/WhiteboardScreen.kt`)**:
  - Drawing surface: Smooth Canvas rendering.
  - Tool dock: Bottom floating toolbar with Pen, Eraser, Color Palette (6 primary swatches), Stroke Width slider, Undo, Redo, and Clear.
- [ ] **VibesScreen (`ui/screens/VibesScreen.kt`)**:
  - Ambient sound mixer: Sleek cards with sound icons (Rain, Forest, Waves, White Noise, Fireplace), volume sliders, and play/pause toggle.
  - Presets: "Focus", "Relax", "Sleep" one-tap preset chips.
- [ ] **ExtensionsScreen (`ui/screens/ExtensionsScreen.kt`)**:
  - Connector integration cards with status chips (Connected, Disconnected, Setup Required).
- [ ] **BrowserScreen (`ui/screens/BrowserScreen.kt`)**:
  - In-app browser top bar: Back, Forward, Refresh, URL domain pill, and Close button.
  - Loading bar: Smooth linear progress indicator at the top of the webview.
- [ ] **NotificationsScreen (`ui/screens/NotificationsScreen.kt`)**:
  - Grouped sections (Today, Yesterday, Older).
  - Unread items marked with primary cyan dot.
  - Swipe-to-dismiss and "Mark all as read" button.
- [ ] **UpdateScreen (`ui/screens/UpdateScreen.kt`)**:
  - Version info card, release notes card, and "Check for Updates" button with loading state.
- [ ] **SplashScreen (`ui/screens/SplashScreen.kt`)**:
  - Minimalist layout: Centered Mira logo with breathing ambient glow, app title in `headlineLarge`, and subtle loading indicator.
- [ ] **SetupScreen (`ui/screens/SetupScreen.kt`)**:
  - Harmonize card layout, input field with visibility toggle for API key, clear validation messages, and "Save & Continue" action.

### 2.3 Settings Screens (`ui/screens/settings/*.kt`)
- [ ] **SettingsHubScreen.kt**: Grouped navigation tiles into categories (Profile & Persona, Intelligence & Engine, Integrations, System & Privacy).
- [ ] **MayaSettingsScreen.kt**: Personality selection (Mira vs Orion), tone slider, custom persona prompt editor.
- [ ] **PersonalSettingsScreen.kt**: User profile details, preferred language picker, nickname.
- [ ] **SecretSettingsScreen.kt**: Secret mode toggle, PIN/Face Lock configuration.
- [ ] **VoiceEngineScreen.kt**:
  - **HARD CONSTRAINT**: Do **NOT** touch any voice logic, audio recorder/player, or client connections in `VoiceEngineScreen.kt`. Restyle only outer card layout, typography, and switch paddings.
- [ ] **AdvancedSettingsScreen.kt**: Model ladder preferences, timeout sliders, cache cleanup button.
- [ ] **ConnectorsSettingsScreen.kt, EmailSettingsScreen.kt, MessagesSettingsScreen.kt, SocialSettingsScreen.kt, OptionalSettingsScreen.kt, BackupSettingsScreen.kt, AboutScreen.kt**:
  - Standardize all screen headers with back arrow and title.
  - Standardize all setting rows with title (14sp medium), description (12sp muted), and trailing widget (switch, chevron, or text value).

---

## 3. DeepSeek-Style Chat UX Implementation Design

### 3.1 Streaming Verification & Root Cause Analysis
Investigation into why streaming responses previously appeared all at once or stuttered revealed four specific bottlenecks:
1. **Scroll Animation Cancelling Loop**:
   In `ChatScreen.kt`, lines 893-897:
   ```kotlin
   val listState = rememberLazyListState()
   LaunchedEffect(messages.size, streamingMessageText, generatingImagePrompt) {
       if (messages.isNotEmpty()) {
           listState.animateScrollToItem(messages.size)
       }
   }
   ```
   Every time an SSE chunk arrives (several times per second), `animateScrollToItem` is launched. Because `animateScrollToItem` takes ~250ms to finish, each subsequent chunk cancels the active animation in progress. This produces visual freezing, UI judder, and prevents fluid downward scrolling.
2. **Full Markdown Re-Parsing on Every Token**:
   In `MarkdownMessage`:
   `val segments = remember(content) { parseMarkdownSegments(content) }`
   `parseMarkdownSegments` executes regular expressions over the entire accumulated text on every single emitted chunk. When an opening code fence ` ``` ` is streamed without the closing fence, the parser flips the rest of the text between raw text and code blocks, causing high recomposition churn.
3. **Project & File Building Text Suppression**:
   In `WebsiteProjectManager.extractStreamingDisplay`:
   When `isProjectOrDownload` is true, as soon as `findFileCutoffIndex` detects `<<<FILE:`, `extractStreamingDisplay` clears the display overview and locks `streamingMessageText` to `"Creating your file..."`. The user sees no streamed tokens at all until the entire request completes and Room inserts the whole message at once.
4. **Proxy Fallback Catch Block**:
   If `generateChatResponseStreaming` catches an error before any chunks arrive (e.g. initial connection latency on model ladder), it silently falls back to non-streaming `generateChatResponse`, which buffers the entire response and dumps it into Room DB all at once.

### 3.2 Solution Architecture for Streaming & Progressive Scrolling
1. **Throttled Smooth Auto-Scroll**:
   Replace continuous `animateScrollToItem` with an auto-scroll manager that:
   - Tracks whether the user is already at the bottom of the list.
   - During streaming, uses `listState.scrollToItem(messages.size)` (instant frame-aligned scroll) or throttles smooth scrolls to a maximum rate of 1 per 100ms.
   - If the user scrolls up to read history during streaming, auto-scroll pauses automatically so their reading position is not hijacked.
2. **Progressive Token Streaming Composables**:
   Separate in-flight streaming rendering from completed message rendering:
   - While streaming: `StreamingMessageView` renders flowing text with a subtle animated cursor (`▎`) without heavyweight markdown parsing overhead.
   - When the stream ends: The completed text is committed to Room DB and parsed into full `MarkdownMessage`.

### 3.3 DeepSeek-Style Thinking State
When Deep Thinking is enabled or when Gemini returns thought tokens:
1. **UI State Representation**:
   - `var isThinking by remember { mutableStateOf(false) }`
   - `var streamingThinkingText by remember { mutableStateOf<String?>(null) }`
   - `var thinkingDurationSeconds by remember { mutableStateOf(0) }`
   - `var isThinkingExpanded by remember { mutableStateOf(true) }`
2. **Visual Design**:
   - **During Thinking**:
     A dedicated pill above the flowing response:
     ```
     [✨ Thinking... (4s) ▾]
     ```
     With an animated shimmering gradient border and a pulsing icon.
     When expanded, it reveals a container with `palette.surfaceVariant.copy(alpha = 0.35f)` background, a `2.dp` cyan vertical border on the left (`palette.primary`), and monospaced italic thought text showing Mira's reasoning or search progress (e.g. `1. Searching Google for latest updates... 2. Analyzing sources...`).
   - **When Thinking Concludes**:
     The accordion automatically collapses into a subtle header:
     ```
     [✨ Thought for 3s ▸]  (or [🌐 Researched 4 sources ▸])
     ```
     Tapping expands the reasoning trace for inspection without cluttering the chat view.
3. **Data Plumbing**:
   - Parse Gemini thinking candidates: when candidate part has `"thought": true`, route text to `streamingThinkingText` rather than main answer text.
   - For models using `<think>...</think>` tags (e.g. DeepSeek-R1), extract text between `<think>` and `</think>` dynamically.

### 3.4 Elimination of AI Message Box (Flowing Text)
In current `ChatScreen.kt` (lines 1052-1060 and 1239-1246), model messages are wrapped in a background card:
```kotlin
// REMOVE THIS BOX WRAPPER:
Box(
    modifier = Modifier
        .clip(RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
        .background(palette.surface)
        .padding(horizontal = 14.dp, vertical = 10.dp)
)
```
**New ChatGPT / DeepSeek Layout**:
- **User Messages**: Sits on the right as a compact rounded bubble (`RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)`), with `palette.surfaceVariant` background and `palette.textPrimary` text.
- **Model Messages**: Sits on the left, full width (`Modifier.fillMaxWidth()`), **NO background card**, **NO surface container**. The text flows cleanly directly onto `palette.background`.
- **Message Action Bar**: Positioned cleanly below the flowing text (Copy, Like, Dislike, Read Aloud, Share, Regenerate) with muted `palette.textSecondary` Fluent icons.

### 3.5 Professional Copyable Code Block (`CodeBlockCard`)
The current code box in `MarkdownMessage` is updated to a professional developer-grade component:
- **Header Bar**:
  - Background `palette.codeHeader` (`#0A0F17`).
  - Left: Clean language label in bold uppercase (`KOTLIN`, `PYTHON`, `HTML`, `TYPESCRIPT`).
  - Right: Action buttons:
    - If web code (`HTML`/`SVG`): "Preview" button with eye icon.
    - Copy button: `ic_fluent_copy_24_regular` with "Copy code" label. On tap, copies code to clipboard and switches to `ic_fluent_checkmark_24_regular` with "Copied!" text in emerald green (`#10B981`) for 2 seconds.
- **Code Container**:
  - Background `palette.codeBackground` (`#121824`).
  - 1dp border `palette.codeBorder` (`#1F2C3F`).
  - Corner radius: `12.dp`.
  - Monospaced typography with line numbers (optional toggle) and horizontal scroll.

### 3.6 Localhost Web Preview Hosting & Download Links
1. **Interactive Localhost Previews**:
   - When the user asks for a website, web app, or explicit preview link (e.g., "build me a portfolio and give preview link"):
     - Mira generates the complete HTML, CSS, and JS files.
     - `WebsiteProjectManager.processAndSaveGeneratedContent` writes them to the project directory: `context.filesDir/projects/<projectName>/`.
     - `LocalProjectServer.startServer(targetDir)` is invoked, spinning up the internal NanoHTTPD server on `http://localhost:8080/`.
     - The chat outputs a prominent clickable preview card:
       ```markdown
       [Preview Website (http://localhost:8080/)](mira://project/<projectName>)
       ```
     - Clicking the card invokes `handleLinkClick`, which verifies `LocalProjectServer` is active and routes directly to `BrowserScreen` loading `http://localhost:8080/`.
2. **Download Links**:
   - When the user asks to "download file" or for a download link:
     - `WebsiteProjectManager` writes the file to `Mira/Files/<fileName>` in external storage.
     - A prominent download card is rendered:
       ```markdown
       [Download <fileName>](mira://file/<fileName>)
       ```
     - Clicking triggers an `Intent.ACTION_VIEW` or `Intent.ACTION_SEND` with `FileProvider` URI, allowing the user to save, open in external editor, or send to other apps.

### 3.7 "Just Code" Output Mode
When a user asks "give me just code", "only code", "just code", "code only", "code only please", "শুধু কোড", or "শুধুমাত্র কোড":
1. **Intent Detection**:
   Add helper `WebsiteProjectManager.isJustCodeRequest(prompt: String): Boolean`.
2. **Strict System Prompt Injection**:
   When detected, append to the system prompt:
   ```
   STRICT CODE-ONLY RULE: The user has requested ONLY code. You must output EXACTLY AND ONLY the markdown code block (```language ... ```). Do NOT include any introductory greetings, bullet points, explanations, advice, or concluding text. Absolutely nothing outside the code block.
   ```
3. **Post-Processing Enforcement**:
   In `processAndSaveGeneratedContent`, if `isJustCodeRequest` was true, extract the first code block and strip any extraneous conversational filler before rendering or saving.

---

## 4. Image-Gen % Badge Fix

### 4.1 Current Layout Issue
In `ChatScreen.kt` (lines 2182-2221):
```kotlin
// Currently centered inside the Column:
Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
    modifier = Modifier.align(Alignment.Center).padding(16.dp)
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = palette.primary.copy(alpha = 0.18f),
        border = BorderStroke(1.dp, palette.primary.copy(alpha = 0.4f))
    ) {
        Text(text = "$progressPercent%", ...)
    }
    Spacer(modifier = Modifier.height(14.dp))
    Text(text = stageCaption, ...)
    Spacer(modifier = Modifier.height(6.dp))
    Text(text = "“$prompt”", ...)
}
```
The percentage badge sits directly above the stage caption in the center of the card, obstructing the focus.

### 4.2 Exact Fix (ChatGPT Reference Alignment)
1. **Center Column**: Keep only the stage caption (`stageCaption`) and prompt quote (`“$prompt”`) in the center of the card.
2. **Move % Badge to Bottom-Right**:
   Position the percentage badge in `BoxScope` using `Modifier.align(Alignment.BottomEnd).padding(14.dp)`.
3. **Styling**:
   - Surface shape: `RoundedCornerShape(16.dp)`.
   - Background: `palette.surfaceVariant.copy(alpha = 0.85f)`.
   - Border: `1.dp` solid `palette.primary.copy(alpha = 0.35f)`.
   - Text: `12.sp`, `FontWeight.Bold`, `palette.primary`.

---

## 5. Admin Panel Investigation & Fix Plan

### 5.1 Investigation Findings

1. **SPA Client Authentication (`App.tsx`)**:
   - File analyzed: `/home/hatch/workspace/mira-admin/src/App.tsx`.
   - Result: `App.tsx` has complete, native client-side Supabase authentication (`supabase.auth.signInWithPassword({ email, password })`, `supabase.auth.getSession()`, `supabase.auth.onAuthStateChange`).
   - When not authenticated, `App.tsx` renders an email/password login form. Once logged in, it manages remote config, licence keys, and macros. It does **not** rely on Cloudflare Access to protect its routes.

2. **Supabase Key Baking in Dist Build**:
   - File analyzed: `/home/hatch/workspace/mira-admin/dist/assets/index-BOjks_Z7.js`.
   - Result: Both `VITE_SUPABASE_URL` (`https://zvnfwlqxdbommtozugzt.supabase.co`) and `VITE_SUPABASE_ANON_KEY` (`eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...`) **are successfully baked** into the production JavaScript bundle.

3. **Root Cause of the 302 Zero Trust Gate**:
   - We queried the Cloudflare API for Account ID `5577ea99b7437d6aeadaf0a840c43ce2`:
     - The account's Zero Trust team domain is `salmandev24.cloudflareaccess.com`.
     - The Pages project in this account is named `mira-admin`, but its Cloudflare Pages assigned domain is `mira-admin-8qg.pages.dev`.
     - The domain `https://mira-admin.pages.dev/` was registered by a **different** Cloudflare account whose Zero Trust organization is `hidden-wind-f2ab.cloudflareaccess.com` (which redirects with `Www-Authenticate: Cloudflare-Access resource_metadata=".../cloudflare-access-protected-resource/"`).
   - Because Cloudflare Pages subdomains are globally allocated across all Cloudflare accounts, when the user or script deployed to project `mira-admin`, Cloudflare assigned the unique suffix `-8qg` (`mira-admin-8qg.pages.dev`).
   - Furthermore, `mira-admin-8qg.pages.dev` currently returns HTTP 500 because the custom python script (`~/workspace/tools/mira-admin-deploy/deploy.py`) had a direct-upload asset manifest mismatch with the Cloudflare Pages Direct Upload v4 API.

4. **Supabase Edge Functions Status**:
   - `mira-gemini` and `gemini-live-relay` return HTTP 401 when accessed without the Supabase anon key header, which is the expected and correct behavior. With auth headers, they are fully functional.

### 5.2 Remediation & Deployment Plan
1. **Deploy using standard Wrangler Pages contract**:
   Instead of the custom multipart script that caused the 500 error, use `wrangler pages deploy`:
   ```bash
   export CLOUDFLARE_API_TOKEN="<surrogate-token>"
   export CLOUDFLARE_ACCOUNT_ID="5577ea99b7437d6aeadaf0a840c43ce2"
   npx wrangler pages deploy /home/hatch/workspace/mira-admin/dist \
       --project-name mira-admin \
       --commit-dirty=true
   ```
2. **Correct Admin URL**:
   The admin panel will be accessible at:
   `https://mira-admin-8qg.pages.dev/`
   Or optionally, add a custom domain (e.g. `admin.ryoaonetsuki.com` or `mira-admin.<custom-domain>`) in the Cloudflare Pages project settings, completely bypassing any default `.pages.dev` namespace collisions.
3. **If `mira-admin.pages.dev` must be reclaimed**:
   If the user has access to the Cloudflare account associated with `hidden-wind-f2ab.cloudflareaccess.com`:
   - Log in to that account.
   - Navigate to Zero Trust > Access > Applications.
   - Locate the application protecting `mira-admin.pages.dev` and delete or disable it, or delete the old Pages project in that account to release the subdomain.

---

## 6. File-by-File Change List for Implementation

| File | Target Composables / Functions | Planned Modifications |
|---|---|---|
| `app/src/main/java/io/github/ryoaonetsuki/mira/ui/theme/Color.kt` | `MiraPalette`, `Palettes` | Add semantic tokens: `surfaceSubtle`, `borderSubtle`, `codeBackground`, `codeHeader`, `codeBorder`, `thinkingBorder`, `thinkingSurface`. |
| `app/src/main/java/io/github/ryoaonetsuki/mira/ui/theme/Type.kt` | `Typography` | Update typography scale with normalized font sizes, line heights, and letter spacings. Add Monospace code style. |
| `app/src/main/java/io/github/ryoaonetsuki/mira/ui/theme/Theme.kt` | `MiraTheme` | Provide new semantic tokens through `LocalMiraPalette`. |
| `app/src/main/java/io/github/ryoaonetsuki/mira/ui/components/CommonUi.kt` | `MiraCard`, `MiraButton` | Polish button padding, ripple feedback, subtle card borders, and loading state spinner. |
| `app/src/main/java/io/github/ryoaonetsuki/mira/ui/components/AppTopBar.kt` | `AppTopBar` | Standardize height (64dp), edge padding (16dp), notification badge styling, and avatar container. |
| `app/src/main/java/io/github/ryoaonetsuki/mira/ui/components/AppDrawer.kt` | `AppDrawer` | Refine active/inactive item styles, dividers, footer styling, and 300dp sheet width. |
| `app/src/main/java/io/github/ryoaonetsuki/mira/ui/screens/HomeScreen.kt` | `HomeScreen`, `HomeBottomNavBar` | **Retain AnimatedOrb signature and call-site intact**. Align weather & date card heights. Polish floating bottom nav bar tabs and active indicator. |
| `app/src/main/java/io/github/ryoaonetsuki/mira/ui/screens/ChatScreen.kt` | `ChatScreen`, `MarkdownMessage`, `ImageGenerationProgressCard` | 1. Remove background Box around AI messages (flowing text).<br>2. Add collapsible Thinking indicator with real-time step overview.<br>3. Fix scroll cancelling loop; introduce throttled progressive scroll.<br>4. Implement new copyable `CodeBlockCard` with Fluent copy/check icons.<br>5. Move % badge in `ImageGenerationProgressCard` to `Alignment.BottomEnd`.<br>6. Support interactive localhost preview links (`mira://project/`). |
| `app/src/main/java/io/github/ryoaonetsuki/mira/projects/WebsiteProjectManager.kt` | `isJustCodeRequest`, `processAndSaveGeneratedContent` | 1. Add `isJustCodeRequest` detection and prompt rule injection.<br>2. Automatically spin up `LocalProjectServer` when preview links are generated.<br>3. Provide clean download link handling for single files. |
| `app/src/main/java/io/github/ryoaonetsuki/mira/network/GeminiRestClient.kt` | `generateChatResponseStreaming` | Parse Gemini candidate parts with `"thought": true` and route thought tokens separately from answer tokens. |
| `app/src/main/java/io/github/ryoaonetsuki/mira/ui/screens/ScanScreen.kt` | `ScanScreen` | Polish camera preview fit, top floating controls, bottom mode switch pill, and shutter button feedback. |
| `app/src/main/java/io/github/ryoaonetsuki/mira/ui/screens/MemoriesScreen.kt` | `MemoriesScreen` | Polish search bar, category chips, card borders, and empty state. |
| `app/src/main/java/io/github/ryoaonetsuki/mira/ui/screens/RulesScreen.kt` | `RulesScreen` | Polish rule cards, switches, and add-rule dialog. |
| `app/src/main/java/io/github/ryoaonetsuki/mira/ui/screens/CodeProjectsScreen.kt` | `CodeProjectsScreen` | Polish project cards, type icons, and preview/download action buttons. |
| `app/src/main/java/io/github/ryoaonetsuki/mira/ui/screens/WhiteboardScreen.kt` | `WhiteboardScreen` | Polish floating tool dock, swatch picker, and undo/redo buttons. |
| `app/src/main/java/io/github/ryoaonetsuki/mira/ui/screens/VibesScreen.kt` | `VibesScreen` | Polish ambient cards, volume sliders, and play/pause buttons. |
| `app/src/main/java/io/github/ryoaonetsuki/mira/ui/screens/ExtensionsScreen.kt` | `ExtensionsScreen` | Polish connector cards and status tags. |
| `app/src/main/java/io/github/ryoaonetsuki/mira/ui/screens/BrowserScreen.kt` | `BrowserScreen` | Polish webview top control bar and linear progress bar. |
| `app/src/main/java/io/github/ryoaonetsuki/mira/ui/screens/NotificationsScreen.kt` | `NotificationsScreen` | Polish grouped section headers, unread dots, and dismiss interactions. |
| `app/src/main/java/io/github/ryoaonetsuki/mira/ui/screens/UpdateScreen.kt` | `UpdateScreen` | Polish version badge, card containers, and update check button. |
| `app/src/main/java/io/github/ryoaonetsuki/mira/ui/screens/SetupScreen.kt` | `SetupScreen` | Polish single-save key input card and action button. |
| `app/src/main/java/io/github/ryoaonetsuki/mira/ui/screens/SplashScreen.kt` | `SplashScreen` | Polish breathing glow animation and version label. |
| `app/src/main/java/io/github/ryoaonetsuki/mira/ui/screens/settings/*.kt` | All settings screens | Harmonize grouped card layouts, Fluent icons, and typography. **Preserve VoiceEngineScreen voice logic untouched**. |
| `tools/mira-admin-deploy/deploy.py` | `main` | Update to deploy via standard `wrangler pages deploy` or fixed API payload to `mira-admin-8qg.pages.dev`. |

---

## 7. Verification Steps & Guardrails

### 7.1 Strict Invariant Checks (Zero Diff Guardrails)
1. **Voice Code Preservation**:
   - Must **NEVER** edit or format any voice engine files:
     - `app/src/main/java/io/github/ryoaonetsuki/mira/voice/GeminiLiveClient.kt`
     - `app/src/main/java/io/github/ryoaonetsuki/mira/voice/RelayLiveClient.kt`
     - `app/src/main/java/io/github/ryoaonetsuki/mira/voice/FallbackVoiceSession.kt`
     - `app/src/main/java/io/github/ryoaonetsuki/mira/voice/AudioRecorder.kt`
     - `app/src/main/java/io/github/ryoaonetsuki/mira/voice/AudioPlayer.kt`
     - `app/src/main/java/io/github/ryoaonetsuki/mira/voice/LiveDiagnosticRunner.kt`
     - `app/src/main/java/io/github/ryoaonetsuki/mira/service/MicService.kt`
     - Voice session logic in `ui/screens/settings/VoiceEngineScreen.kt`.
   - Run verification command:
     `git diff origin/master -- app/src/main/java/io/github/ryoaonetsuki/mira/voice/ app/src/main/java/io/github/ryoaonetsuki/mira/service/MicService.kt`
     Expected output: Empty diff.
2. **AnimatedOrb Signature Preservation**:
   - `AnimatedOrb(state: LiveState, amplitude: Float, orbStyle: String = "Mira Neon", modifier: Modifier = Modifier)`
   - Call sites in `HomeScreen.kt` and `ChatScreen.kt` must remain strictly untouched.
3. **No Round 19 Voice Fix**:
   - Do NOT touch or apply anything from `~/workspace/mira-aider/voice-fix-prompt.md`.

### 7.2 Quality & Content Verification
1. **Zero Placeholder Policy**:
   - Run grep across `app/src/main/java/`:
     `grep -rnE "(Lorem ipsum|demo content|placeholder text|TODO: implement)" app/src/main/java/`
     Expected output: 0 matches.
2. **Fluent UI Icon Audit**:
   - Confirm all icons use `ic_fluent_*` vectors (Regular for base states, Filled for selected/active states).
   - Ensure Vision uses `ic_fluent_camera_24_regular` (never a QR icon).

### 7.3 Build & Test Verification
1. **Kotlin Compilation**:
   - Ensure `compileDebugKotlin` succeeds with 0 errors:
     ```bash
     source ~/workspace/tools/dev-env/env.sh
     gradle compileDebugKotlin
     ```
2. **Unit Tests**:
   - Run all local unit tests:
     ```bash
     gradle testDebugUnitTest
     ```
3. **CI Run Check**:
   - Push commit via `tools/push.py` and inspect status via `python3 tools/gh_actions.py status`.
   - Verify workflow `Build Android APK` completes with `conclusion=success`.

---

## Plan Summary

Round 18 delivers a comprehensive, premium design overhaul across all screens of Mira AI to match 2026 ChatGPT and DeepSeek standards, featuring a refined theme token system, a DeepSeek-style chat UX with visible real-time thinking states, line-by-line progressive streaming, flowing AI messages (eliminating bounding box surfaces), copyable developer-grade code boxes, localhost web preview hosting, download links, and a strict "just code" mode. The home orb and all voice engine subsystems remain strictly untouched under active pause constraints, the image generation percentage badge is repositioned to the bottom-right corner, and the Cloudflare Pages admin panel deployment is diagnosed and directed to its live project subdomain `mira-admin-8qg.pages.dev` with verified Supabase authentication.
