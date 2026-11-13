package io.github.ryoaonetsuki.mira.automation.agent

import io.github.ryoaonetsuki.mira.automation.AppKnowledge
import io.github.ryoaonetsuki.mira.automation.MiraAutomationBridge
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskExecutorTest {

    // --- Fake AutomationPort for pure JVM unit testing ---
    private open class FakeAutomationPort(
        var serviceEnabled: Boolean = true,
        var currentPkg: String? = "io.github.ryoaonetsuki.mira",
        var actionSuccess: Boolean = true,
        var simulateEffect: Boolean = true,
        var requireNodeMatchForClickText: Boolean = false,
        var noEffectClickTexts: Set<String> = emptySet(),
        var screenshotBytes: ByteArray? = byteArrayOf(1, 2, 3),
        val actionsPerformed: MutableList<String> = mutableListOf(),
        // Turn F(a): when > 0, openApp does not flip the foreground package
        // until this many getCurrentPackage() checks have passed — simulates
        // a cold app start (YouTube commonly 2-5s).
        var packageAppearAfterChecks: Int = 0,
        // Turn G: when set, openApp replaces the screen with these nodes
        // instead of mutating it — emptyList() simulates a blank/loading
        // screen after the app opened.
        var openAppScreenNodes: List<ScreenNode>? = null,
        var screenNodes: List<ScreenNode> = listOf(
            ScreenNode(
                index = 0,
                text = "Home",
                contentDescription = "Home button",
                className = "android.widget.Button",
                viewId = "home_btn",
                isClickable = true,
                isEditable = false,
                isScrollable = false,
                isCheckable = false,
                isEnabled = true,
                left = 10,
                top = 10,
                right = 100,
                bottom = 50,
                centerX = 55,
                centerY = 30
            )
        )
    ) : AutomationPort {
        private var mutationTick = 0

        /** Turn F(e): how many screen reads the executor performed. */
        var dumpCount: Int = 0
            private set

        /** Turn F(a): how many foreground-package checks the executor made. */
        var packageCheckCount: Int = 0
            private set

        private var pendingPkg: String? = null
        private var packageChecksSinceOpen: Int = 0

        private fun mutateScreenState(label: String) {
            if (!simulateEffect) return
            mutationTick++
            screenNodes = listOf(
                ScreenNode(
                    index = 0,
                    text = "$label#$mutationTick",
                    contentDescription = label,
                    className = "android.widget.Button",
                    viewId = "btn_$mutationTick",
                    isClickable = true,
                    isEditable = false,
                    isScrollable = false,
                    isCheckable = false,
                    isEnabled = true,
                    left = 10,
                    top = 10,
                    right = 100,
                    bottom = 50,
                    centerX = 55,
                    centerY = 30
                )
            )
        }

        override fun isServiceEnabled(): Boolean = serviceEnabled
        override fun dumpScreen(): List<ScreenNode> = screenNodes
        override fun getScreenElements(): List<ScreenNode> {
            dumpCount++
            return screenNodes
        }
        override fun formatNodes(nodes: List<ScreenNode>): String = "nodes count: ${nodes.size}"
        override fun getCompressedScreenDescription(nodes: List<ScreenNode>, goal: String): String =
            "Screen description for $goal"

        override fun clickText(text: String, nodes: List<ScreenNode>): Boolean {
            if (!actionSuccess) return false
            if (requireNodeMatchForClickText) {
                val found = nodes.any {
                    it.text.contains(text, ignoreCase = true) ||
                        it.contentDescription.contains(text, ignoreCase = true)
                }
                if (!found) return false
            }
            actionsPerformed.add("clickText:$text")
            if (text !in noEffectClickTexts) {
                mutateScreenState("clicked:$text")
            }
            return true
        }

        override fun clickAt(x: Float, y: Float): Boolean {
            if (!actionSuccess) return false
            actionsPerformed.add("clickAt:$x,$y")
            inputFocusedState = true
            mutateScreenState("clickedAt:$x,$y")
            return true
        }

        var inputFocusedState: Boolean = false
        var currentFieldText: String? = null
        var setAttempts: Int = 0
        var failFirstSet: Boolean = false

        override fun isInputFocused(): Boolean = inputFocusedState
        override fun getFocusedText(): String? = currentFieldText

        override fun setTextOnFocused(text: String): Boolean {
            if (!actionSuccess) return false
            setAttempts++
            actionsPerformed.add("setTextOnFocused:$text#$setAttempts")
            if (failFirstSet && setAttempts == 1) {
                currentFieldText = "mismatched_text"
                return true
            }
            currentFieldText = text
            mutateScreenState("setTextOnFocused:$text")
            return true
        }

        override fun typeText(text: String): Boolean {
            if (!actionSuccess) return false
            actionsPerformed.add("typeText:$text")
            mutateScreenState("typed:$text")
            return true
        }

        override fun pressEnter(): Boolean {
            if (!actionSuccess) return false
            actionsPerformed.add("pressEnter")
            mutateScreenState("enter")
            return true
        }

        override fun scroll(direction: String): Boolean {
            if (!actionSuccess) return false
            actionsPerformed.add("scroll:$direction")
            mutateScreenState("scroll:$direction")
            return true
        }

        override fun scroll(direction: String, fraction: Float): Boolean {
            if (!actionSuccess) return false
            actionsPerformed.add("scroll:$direction:$fraction")
            mutateScreenState("scroll:$direction")
            return true
        }

        override fun openUrl(url: String): Boolean {
            if (!actionSuccess) return false
            actionsPerformed.add("openUrl:$url")
            if (simulateEffect) {
                currentPkg = "com.android.chrome"
                mutateScreenState("opened:$url")
            }
            return true
        }

        override fun dialNumber(number: String): Boolean {
            if (!actionSuccess) return false
            actionsPerformed.add("dialNumber:$number")
            if (simulateEffect) {
                currentPkg = "com.google.android.dialer"
                mutateScreenState("dialer:$number")
            }
            return true
        }

        override fun swipe(
            startX: Float,
            startY: Float,
            endX: Float,
            endY: Float,
            durationMs: Long
        ): Boolean {
            if (!actionSuccess) return false
            actionsPerformed.add("swipe:$startX,$startY->$endX,$endY")
            mutateScreenState("swipe")
            return true
        }

        override fun longPress(x: Float, y: Float, durationMs: Long): Boolean {
            if (!actionSuccess) return false
            actionsPerformed.add("longPress:$x:$y")
            mutateScreenState("longPress:$x:$y")
            return true
        }

        override fun pinch(centerX: Float, centerY: Float, zoomIn: Boolean): Boolean {
            if (!actionSuccess) return false
            actionsPerformed.add("pinch:$centerX:$centerY:$zoomIn")
            mutateScreenState("pinch:$centerX:$centerY:$zoomIn")
            return true
        }

        override fun pressBack(): Boolean {
            if (!actionSuccess) return false
            actionsPerformed.add("pressBack")
            mutateScreenState("back")
            return true
        }

        override fun pressHome(): Boolean {
            if (!actionSuccess) return false
            actionsPerformed.add("pressHome")
            if (simulateEffect) {
                currentPkg = "com.android.launcher3"
                mutateScreenState("home")
            }
            return true
        }

        override fun openApp(query: String): String? {
            val trimmed = query.trim()
            if (!actionSuccess || trimmed.isEmpty()) return null
            actionsPerformed.add("openApp:$trimmed")
            val launchedPkg = ScreenAutomationService.resolveKnownAppPackage(trimmed) ?: "com.example.$trimmed"
            if (simulateEffect) {
                // Package appears after packageAppearAfterChecks checks (cold
                // start); the screen itself mutates immediately.
                pendingPkg = launchedPkg
                packageChecksSinceOpen = 0
                if (openAppScreenNodes != null) {
                    screenNodes = openAppScreenNodes!!
                } else {
                    mutateScreenState("opened:$launchedPkg")
                }
            }
            return launchedPkg
        }

        override fun getCurrentPackage(): String? {
            packageCheckCount++
            val pending = pendingPkg
            if (pending != null) {
                packageChecksSinceOpen++
                if (packageChecksSinceOpen > packageAppearAfterChecks) {
                    currentPkg = pending
                    pendingPkg = null
                }
            }
            return currentPkg
        }

        override suspend fun takeScreenshotJpeg(maxWidthPx: Int): ByteArray? = screenshotBytes

        var targetPackageResolver: ((query: String, pkg: String) -> Boolean)? = null
        override fun isTargetPackage(query: String, pkg: String): Boolean =
            targetPackageResolver?.invoke(query, pkg) ?: super.isTargetPackage(query, pkg)
    }

    // --- Fake in-memory SkillStore for pure JVM testing ---
    private class InMemorySkillStore(var data: String? = null) : SkillStore {
        override fun loadRaw(): String? = data
        override fun saveRaw(json: String) {
            data = json
        }
    }

    // =========================================================================
    // 1. Turn G — NO artificial action restrictions (owner override of the
    // Round 22 Jarvis-port safety limits). The AutomationSafety blocklist is
    // removed entirely: payment/delete/banking steps must EXECUTE, with the
    // model's own judgement deciding what cannot be done. These tests prove
    // the executor no longer refuses them.
    // =========================================================================

    @Test
    fun testTurnG_NoRestrictions_BankingAppReplayExecutes() = runBlocking {
        // A recorded skill inside a banking app replays — no package block.
        val fakePort = FakeAutomationPort(
            serviceEnabled = true,
            currentPkg = "com.bkash"
        )
        val skills = SkillMemoryService(InMemorySkillStore())
        val skill = Skill(
            goal = "bkash send",
            steps = listOf(
                TaskStep(action = TaskStep.ACTION_CLICK_TEXT, params = mapOf("text" to "Send Money")),
                TaskStep(action = TaskStep.ACTION_CLICK_TEXT, params = mapOf("text" to "Confirm"))
            )
        )
        val executor = TaskExecutor(automation = fakePort, skills = skills, stepDelayMs = 0)

        val result = executor.replaySkillDirect(skill)
        assertEquals(TaskStatus.SUCCESS, result.status)
        assertTrue(fakePort.actionsPerformed.contains("clickText:Send Money"))
        assertTrue(fakePort.actionsPerformed.contains("clickText:Confirm"))
    }

    // =========================================================================
    // 2. isAutomationIntent Tests
    // =========================================================================

    @Test
    fun testIsAutomationIntent_RequiredPhrases() {
        // Owner/Coordinator verified required phrasings:
        assertTrue(MiraAutomationBridge.isAutomationIntent("YouTube open করো"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("ইউটিউব খোলো"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("open YouTube"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("play a song on YouTube"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("WhatsApp-এ মেসেজ পাঠাও"))

        // Existing required phrases
        assertTrue(MiraAutomationBridge.isAutomationIntent("open youtube and play lofi"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("message mom on whatsapp"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("খুলো ইউটিউব"))
    }

    @Test
    fun testIsAutomationIntent_RomanBengaliAndBengaliPhrasings() {
        // Roman Bengali phrasings
        assertTrue(MiraAutomationBridge.isAutomationIntent("YouTube open koro"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("youtube kholo"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("youtube chalao"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("whatsapp e message pathao"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("whatsapp e text pathao"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("spotify te gaan bajao"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("phone control koro"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("control the phone"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("control my phone"))

        // Additional coverage
        assertTrue(MiraAutomationBridge.isAutomationIntent("play Shape of You on Spotify"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("launch camera"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("start settings app"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("হোয়াটসঅ্যাপ এ মেসেজ পাঠাও"))
    }

    @Test
    fun testIsAutomationIntent_OrdinaryQuestions_ReturnFalse() {
        assertFalse(MiraAutomationBridge.isAutomationIntent("how far is the moon"))
        assertFalse(MiraAutomationBridge.isAutomationIntent("what is the weather today"))
        assertFalse(MiraAutomationBridge.isAutomationIntent("tell me a joke"))
        assertFalse(MiraAutomationBridge.isAutomationIntent("who are you"))
        assertFalse(MiraAutomationBridge.isAutomationIntent("who made you"))
        assertFalse(MiraAutomationBridge.isAutomationIntent(""))
        assertFalse(MiraAutomationBridge.isAutomationIntent("   "))
    }

    // =========================================================================
    // 3. TaskExecutor Flow Tests
    // =========================================================================

    @Test
    fun testTaskExecutor_SkillReplayPath_NeverCallsThink() = runBlocking {
        val fakePort = FakeAutomationPort(serviceEnabled = true)
        val skills = SkillMemoryService(InMemorySkillStore())

        // Pre-save a skill for a goal the direct path does NOT cover (a
        // specific media request, not just "open app" — the direct path wins
        // for plain "open camera", see the Turn F-direct tests below).
        val saved = skills.saveOrUpdate(
            goal = "play lofi on youtube",
            steps = listOf(
                TaskStep(action = "open_app", params = mapOf("app" to "youtube")),
                TaskStep(action = "click_text", params = mapOf("text" to "Search"))
            )
        )

        val executor = TaskExecutor(automation = fakePort, skills = skills, stepDelayMs = 0)
        val result = executor.replaySkillDirect(saved)
        assertEquals(TaskStatus.SUCCESS, result.status)
        assertTrue(fakePort.actionsPerformed.contains("openApp:youtube"))
    }

    @Test
    fun testTaskExecutor_AccessibilityOff_ReturnsFailed() = runBlocking {
        val fakePort = FakeAutomationPort(serviceEnabled = false)
        val skills = SkillMemoryService(InMemorySkillStore())

        val executor = TaskExecutor(
            automation = fakePort,
            skills = skills,
            stepDelayMs = 0
        )
        val skill = Skill(goal = "launch settings", steps = listOf(TaskStep(action = TaskStep.ACTION_OPEN_APP)))
        val result = executor.replaySkillDirect(skill)

        assertEquals(TaskStatus.FAILED, result.status)
        assertEquals(0, result.stepsUsed)
        assertTrue(result.message.contains("Accessibility service is off"))
    }

    // =========================================================================
    // 4. Macro Replay Verification & Truthful Reporting Tests (Part B)
    // =========================================================================

    @Test
    fun testTaskExecutor_ReplaySkill_EmptySteps_FailsTruthfully() = runBlocking {
        val fakePort = FakeAutomationPort(serviceEnabled = true)
        val skills = SkillMemoryService(InMemorySkillStore())

        val emptySkill = Skill(goal = "play youtube song", steps = emptyList())
        val executor = TaskExecutor(
            automation = fakePort,
            skills = skills,
            stepDelayMs = 0
        )

        // Direct replay method
        val directResult = executor.replaySkillDirect(emptySkill)
        assertEquals(TaskStatus.FAILED, directResult.status)
        assertEquals(0, directResult.stepsUsed)
        assertTrue("Message must explain empty steps", directResult.message.contains("no recorded steps"))

        // Replay boolean method
        val ok = executor.replaySkill(emptySkill)
        assertFalse("replaySkill must return false for empty steps", ok)
    }

    @Test
    fun testTaskExecutor_ReplaySkillDirect_ExecutorFailure_ReturnsTruthfulFailureMessage() = runBlocking {
        val fakePort = FakeAutomationPort(serviceEnabled = true, actionSuccess = false)
        val skills = SkillMemoryService(InMemorySkillStore())

        val skill = Skill(
            goal = "open youtube",
            steps = listOf(TaskStep(action = "click_text", params = mapOf("text" to "Search")))
        )

        val executor = TaskExecutor(
            automation = fakePort,
            skills = skills,
            stepDelayMs = 0
        )

        val result = executor.replaySkillDirect(skill)
        assertEquals(TaskStatus.FAILED, result.status)
        assertEquals(1, result.stepsUsed)
        assertTrue(
            "Message must name the failed replay step and reason",
            result.message.contains("Macro replay step 1 failed")
        )
    }

    @Test
    fun testTaskExecutor_ReplaySkillDirect_Success_OnlyWhenStepsActuallyRan() = runBlocking {
        val fakePort = FakeAutomationPort(serviceEnabled = true, actionSuccess = true)
        val skills = SkillMemoryService(InMemorySkillStore())

        val skill = Skill(
            goal = "open and play",
            steps = listOf(
                TaskStep(action = "open_app", params = mapOf("app" to "youtube")),
                TaskStep(action = "click_text", params = mapOf("text" to "Search"))
            )
        )

        val executor = TaskExecutor(
            automation = fakePort,
            skills = skills,
            stepDelayMs = 0
        )

        val result = executor.replaySkillDirect(skill)
        assertEquals(TaskStatus.SUCCESS, result.status)
        assertEquals(2, result.stepsUsed)
        assertTrue(result.message.contains("completed"))
        assertTrue(fakePort.actionsPerformed.contains("openApp:youtube"))
        assertTrue(fakePort.actionsPerformed.contains("clickText:Search"))
    }

    @Test
    fun testTaskExecutor_ReplaySkill_LauncherStep1_ContinuesToStep2() = runBlocking {
        val fakePort = FakeAutomationPort(
            serviceEnabled = true,
            actionSuccess = true,
            currentPkg = "com.android.launcher3"
        )
        val skills = SkillMemoryService(InMemorySkillStore())

        val skill = Skill(
            goal = "yt",
            steps = listOf(
                TaskStep(action = "open_app", params = mapOf("app" to "com.google.android.apps.nexuslauncher")),
                TaskStep(action = "click_text", params = mapOf("text" to "Search"))
            )
        )

        val executor = TaskExecutor(
            automation = fakePort,
            skills = skills,
            stepDelayMs = 0
        )

        val result = executor.replaySkillDirect(skill)
        assertEquals(TaskStatus.SUCCESS, result.status)
        assertEquals(2, result.stepsUsed)
        assertTrue("pressHome should have been called instead of openApp for launcher", fakePort.actionsPerformed.contains("pressHome"))
        assertFalse("openApp should NOT have been called for launcher", fakePort.actionsPerformed.any { it.startsWith("openApp:") && it.contains("launcher") })
        assertTrue("Step 2 clickText should have executed", fakePort.actionsPerformed.contains("clickText:Search"))
    }

    @Test
    fun testTaskExecutor_ReplaySkillDirect_AccessibilityOff_ReturnsFailed() = runBlocking {
        val fakePort = FakeAutomationPort(serviceEnabled = false)
        val skills = SkillMemoryService(InMemorySkillStore())

        val skill = Skill(
            goal = "open and play",
            steps = listOf(TaskStep(action = "open_app", params = mapOf("app" to "youtube")))
        )

        val executor = TaskExecutor(
            automation = fakePort,
            skills = skills,
            stepDelayMs = 0
        )

        val result = executor.replaySkillDirect(skill)
        assertEquals(TaskStatus.FAILED, result.status)
        assertEquals(0, result.stepsUsed)
        assertTrue(result.message.contains("Accessibility service is off"))
    }

    // =========================================================================
    // 5. Macro Distillation / Event Conversion Tests (Jarvis Import)
    // =========================================================================

    @Test
    fun testMiraAccessibilityService_convertEventsToSteps_JarvisImport() {
        // 1. Empty events -> empty steps
        val emptySteps = io.github.ryoaonetsuki.mira.service.MiraAccessibilityService.convertEventsToSteps(emptyList())
        assertTrue("Empty events must distill into empty steps", emptySteps.isEmpty())

        // 2. Events starting in external app inject open_app step first
        val rawEvents = listOf(
            mapOf(
                "type" to "click",
                "label" to "Search",
                "package" to "com.google.android.youtube",
                "x" to 540,
                "y" to 960
            ),
            mapOf(
                "type" to "type",
                "text" to "Lofi hip hop",
                "package" to "com.google.android.youtube"
            )
        )

        val steps = io.github.ryoaonetsuki.mira.service.MiraAccessibilityService.convertEventsToSteps(rawEvents)

        // Expected steps:
        // 0: open_app with app="com.google.android.youtube"
        // 1: click_text with text="Search"
        // 2: type_text with text="Lofi hip hop"
        // 3: press_enter (imported from Jarvis to submit search)
        assertEquals(4, steps.size)
        assertEquals(TaskStep.ACTION_OPEN_APP, steps[0].action)
        assertEquals("com.google.android.youtube", steps[0].stringParam("app"))

        assertEquals(TaskStep.ACTION_CLICK_TEXT, steps[1].action)
        assertEquals("Search", steps[1].stringParam("text"))

        assertEquals(TaskStep.ACTION_TYPE_TEXT, steps[2].action)
        assertEquals("Lofi hip hop", steps[2].stringParam("text"))

        assertEquals(TaskStep.ACTION_PRESS_ENTER, steps[3].action)
    }

    @Test
    fun testMiraAccessibilityService_convertEventsToSteps_emitsNoLauncherOpenAppStep() {
        val rawEvents = listOf(
            mapOf(
                "type" to "window",
                "package" to "com.google.android.apps.nexuslauncher"
            ),
            mapOf(
                "type" to "click",
                "label" to "YouTube",
                "package" to "com.google.android.apps.nexuslauncher",
                "x" to 300,
                "y" to 400
            ),
            mapOf(
                "type" to "window",
                "package" to "com.google.android.youtube"
            ),
            mapOf(
                "type" to "click",
                "label" to "Search",
                "package" to "com.google.android.youtube",
                "x" to 540,
                "y" to 960
            )
        )

        val steps = io.github.ryoaonetsuki.mira.service.MiraAccessibilityService.convertEventsToSteps(rawEvents)

        // Verify: No open_app step for nexuslauncher.
        val openAppSteps = steps.filter { it.action == TaskStep.ACTION_OPEN_APP }
        assertEquals(1, openAppSteps.size)
        assertEquals("com.google.android.youtube", openAppSteps[0].stringParam("app"))

        // The youtube step exists and follows properly
        assertTrue(steps.none { it.action == TaskStep.ACTION_OPEN_APP && it.stringParam("app")?.contains("launcher") == true })
    }

    // =========================================================================
    // 6. Round 25 Fix Turn A — F-01, F-02, F-04, F-07, F-08, F-09, F-14 & Vision
    // =========================================================================

    @Test
    fun testF01_UserTurnTranscriptBuffer_AccumulatesChunksAndDispatchesOncePerTurn() {
        val buffer = io.github.ryoaonetsuki.mira.voice.pipecat.UserTurnTranscriptBuffer()

        val c1 = buffer.appendChunk("YouTube")
        assertEquals("YouTube", c1)
        assertFalse(MiraAutomationBridge.isAutomationIntent(c1))

        val c2 = buffer.appendChunk(" open")
        assertEquals("YouTube open", c2)

        val c3 = buffer.appendChunk(" করো")
        assertEquals("YouTube open করো", c3)

        val completed = buffer.completeTurn(resetBuffer = false)
        assertEquals("YouTube open করো", completed)
        assertTrue(MiraAutomationBridge.isAutomationIntent(completed!!))

        // Duplicate boundary within the same turn must return null (dispatched at most once)
        assertNull(buffer.completeTurn(resetBuffer = true))

        // Next turn starts fresh
        assertEquals("গান", buffer.appendChunk("গান"))
        assertEquals("গান চালাও", buffer.appendChunk(" চালাও"))
        assertEquals("গান চালাও", buffer.completeTurn(resetBuffer = true))
    }

    @Test
    fun testF02_And_F14_BanglaIntentsExtractTargetAppAndNarrowPlayRegex() {
        // F-02: Bangla-script intents and verbs
        assertTrue(MiraAutomationBridge.isAutomationIntent("গান চালাও"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("মিউজিক বাজাও"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("গান প্লে করো"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("গান শোনাও"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("ইউটিউব ওপেন করো"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("ইউটিউব চালু করো"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("স্পটিফাই এ গান বাজাও"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("ক্যামেরা ওপেন করো"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("সেটিংস বন্ধ করো"))

        // F-02: extractTargetApp canonical resolution
        assertEquals("com.google.android.youtube", MiraAutomationBridge.extractTargetApp("ইউটিউব ওপেন করো"))
        assertEquals("com.google.android.youtube", MiraAutomationBridge.extractTargetApp("গান চালাও"))
        assertEquals("com.spotify.music", MiraAutomationBridge.extractTargetApp("স্পটিফাই এ গান বাজাও"))
        assertEquals("com.whatsapp", MiraAutomationBridge.extractTargetApp("হোয়াটসঅ্যাপ খুলো"))
        assertEquals("com.android.chrome", MiraAutomationBridge.extractTargetApp("ক্রোম চালু করো"))
        assertEquals("camera", MiraAutomationBridge.extractTargetApp("ক্যামেরা খুলো"))
        assertEquals("com.android.settings", MiraAutomationBridge.extractTargetApp("সেটিংস ওপেন করো"))

        // F-14: Narrow play regex — conversational "play" questions must NOT match
        assertFalse(MiraAutomationBridge.isAutomationIntent("Can children play outside?"))
        assertFalse(MiraAutomationBridge.isAutomationIntent("How do you play chess?"))
        assertFalse(MiraAutomationBridge.isAutomationIntent("Let's role play a scenario"))
        // Real media play commands still match
        assertTrue(MiraAutomationBridge.isAutomationIntent("play music"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("play some music"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("play a song on YouTube"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("play lofi on music"))
    }



    @Test
    fun testTurnG_OpenAppTargetReached_WrongPackageIsFailure() {
        val step = TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("app" to "whatsapp"))
        // Chrome opening after a WhatsApp request used to count as success
        // (any package change). Only the target app counts now.
        assertFalse(
            "Chrome is not WhatsApp — a wrong package must fail",
            TaskExecutor.openAppTargetReached(step, "io.github.ryoaonetsuki.mira", "com.android.chrome")
        )
        assertFalse(
            "No package change to the wrong app must fail",
            TaskExecutor.openAppTargetReached(step, "com.android.chrome", "com.android.chrome")
        )
        assertTrue(
            "The target package itself counts",
            TaskExecutor.openAppTargetReached(step, "io.github.ryoaonetsuki.mira", "com.whatsapp")
        )
        assertTrue(
            "WhatsApp Business (alias) counts as the target",
            TaskExecutor.openAppTargetReached(step, "io.github.ryoaonetsuki.mira", "com.whatsapp.w4b")
        )
        assertTrue(
            "Already in the target app counts as success",
            TaskExecutor.openAppTargetReached(step, "com.whatsapp", "com.whatsapp")
        )
        val miraStep = TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("app" to "facebook"))
        assertFalse(
            "Landing back on Mira itself is not the target app",
            TaskExecutor.openAppTargetReached(miraStep, "com.facebook.katana", "io.github.ryoaonetsuki.mira")
        )
    }

    @Test
    fun testTurnG_DirectSearch_KnownAppNeverRoutesToWebSearch() {
        val action = MiraAutomationBridge.parseDirectAction("search lofi on whatsapp")
        assertEquals(MiraAutomationBridge.DirectAction.Search("lofi", "com.whatsapp"), action)
        val search = action as MiraAutomationBridge.DirectAction.Search
        assertEquals("lofi", search.query)
        assertEquals("com.whatsapp", search.targetApp)
        val bridge = MiraAutomationBridge(context = null)
        assertTrue(bridge.isDirectGoal("search lofi on whatsapp"))
    }

    @Test
    fun testTurnG_BrowserAndDialerPackageChecks() {
        assertTrue(TaskExecutor.isBrowserPackage("com.android.chrome"))
        assertTrue(TaskExecutor.isBrowserPackage("org.mozilla.firefox"))
        assertFalse(TaskExecutor.isBrowserPackage("com.google.android.youtube"))
        assertFalse(TaskExecutor.isBrowserPackage("com.whatsapp"))
        assertTrue(TaskExecutor.isDialerPackage("com.google.android.dialer"))
        assertTrue(TaskExecutor.isDialerPackage("com.samsung.android.dialer"))
        assertFalse(TaskExecutor.isDialerPackage("com.android.chrome"))
        assertFalse(TaskExecutor.isDialerPackage("com.whatsapp"))
    }

    // =========================================================================
    // 7. Round 25 Fix Turn C — C1, C2, C3, C4 Tests
    // =========================================================================

    @Test
    fun testC2_ReplaySkillDirect_FailedStepFailsTruthfully_AndMarksOutcomeCorrectly() = runBlocking {
        val fakePort = FakeAutomationPort(serviceEnabled = true, actionSuccess = false)
        val skills = SkillMemoryService(InMemorySkillStore())
        val savedSkill = skills.saveOrUpdate(
            goal = "open youtube",
            steps = listOf(TaskStep(action = "click_text", params = mapOf("text" to "StaleVideoTitle")))
        )
        val executor = TaskExecutor(automation = fakePort, skills = skills, stepDelayMs = 0)

        val result = executor.replaySkillDirect(savedSkill)
        assertEquals(TaskStatus.FAILED, result.status)
        assertTrue("Diagnosis must name step 1: ${result.message}", result.message.contains("step 1"))

        // Skill must still exist, consecutiveFailures == 1, successCount == 0
        val updatedSkill = skills.findMatch("open youtube")
        assertNotNull(updatedSkill)
        assertEquals(1, updatedSkill?.consecutiveFailures)
        assertEquals(0, updatedSkill?.successCount)
    }

    @Test
    fun testC2_ReplaySkillDirect_FailedReplay_MarksFailureWithoutDeletingOnFirstFailure() = runBlocking {
        val fakePort = FakeAutomationPort(serviceEnabled = true, actionSuccess = false)
        val skills = SkillMemoryService(InMemorySkillStore())
        val savedSkill = skills.saveOrUpdate(
            goal = "open youtube",
            steps = listOf(TaskStep(action = "click_text", params = mapOf("text" to "StaleVideoTitle")))
        )
        val executor = TaskExecutor(automation = fakePort, skills = skills, stepDelayMs = 0)

        val result = executor.replaySkillDirect(savedSkill)
        assertEquals(TaskStatus.FAILED, result.status)
        assertTrue("Diagnosis must name step 1: ${result.message}", result.message.contains("step 1"))

        // C1 + C2: Skill is NOT deleted after 1 failure; consecutiveFailures is incremented to 1
        val retained = skills.findMatch("open youtube")
        assertNotNull("Skill must not be deleted on a single failed replay", retained)
        assertEquals(1, retained?.consecutiveFailures)
        assertEquals(0, retained?.successCount)
    }

    @Test
    fun testC3_ReplaySkill_RetriesThenSkipsNonCriticalNoEffect_AndContinues() = runBlocking {
        // 3 steps:
        // Step 1: open_app youtube (critical -> has effect)
        // Step 2: click_text StaleFeedItem (non-critical -> dispatches ok, but NO effect on screen)
        // Step 3: click_text Library (non-critical -> has effect)
        // 1 of 3 skipped (<= 50%) -> overall replay SUCCEEDS!
        val fakePort = FakeAutomationPort(
            serviceEnabled = true,
            noEffectClickTexts = setOf("StaleFeedItem")
        )

        val skills = SkillMemoryService(InMemorySkillStore())
        val skill = Skill(
            goal = "yt macro",
            steps = listOf(
                TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("app" to "youtube")),
                TaskStep(action = TaskStep.ACTION_CLICK_TEXT, params = mapOf("text" to "StaleFeedItem")),
                TaskStep(action = TaskStep.ACTION_CLICK_TEXT, params = mapOf("text" to "Library"))
            )
        )
        val executor = TaskExecutor(automation = fakePort, skills = skills, stepDelayMs = 0)

        val ok = executor.replaySkill(skill)
        assertTrue("Replay must succeed when 1 of 3 non-critical steps has no effect and is skipped", ok)
        // StaleFeedItem was tried twice (initial + 1 retry), Library once -> total 3 clickText calls
        val clickCalls = fakePort.actionsPerformed.filter { it.startsWith("clickText:") }
        assertEquals(listOf("clickText:StaleFeedItem", "clickText:StaleFeedItem", "clickText:Library"), clickCalls)
    }

    @Test
    fun testC3_ReplaySkill_CriticalOpenAppNoEffect_Fails() = runBlocking {
        // open_app dispatches non-null but package never changes and remains Mira -> verifyEffect false -> critical step fails
        val fakePort = FakeAutomationPort(
            serviceEnabled = true,
            currentPkg = "io.github.ryoaonetsuki.mira",
            simulateEffect = false
        )
        val skills = SkillMemoryService(InMemorySkillStore())
        val skill = Skill(
            goal = "yt macro",
            steps = listOf(
                TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("app" to "youtube")),
                TaskStep(action = TaskStep.ACTION_CLICK_TEXT, params = mapOf("text" to "Library"))
            )
        )
        val executor = TaskExecutor(automation = fakePort, skills = skills, stepDelayMs = 0)

        val ok = executor.replaySkill(skill)
        assertFalse("Critical open_app step with no effect after retry must fail replay", ok)
    }

    @Test
    fun testC3_ReplaySkill_MoreThanHalfSkipped_Fails() = runBlocking {
        // 3 steps: 1 critical open_app succeeds, 2 non-critical click_text have no effect -> 2/3 (>50%) skipped -> FAILS
        val fakePort = FakeAutomationPort(
            serviceEnabled = true,
            noEffectClickTexts = setOf("Stale1", "Stale2")
        )

        val skills = SkillMemoryService(InMemorySkillStore())
        val skill = Skill(
            goal = "yt macro",
            steps = listOf(
                TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("app" to "youtube")),
                TaskStep(action = TaskStep.ACTION_CLICK_TEXT, params = mapOf("text" to "Stale1")),
                TaskStep(action = TaskStep.ACTION_CLICK_TEXT, params = mapOf("text" to "Stale2"))
            )
        )
        val executor = TaskExecutor(automation = fakePort, skills = skills, stepDelayMs = 0)

        val ok = executor.replaySkill(skill)
        assertFalse("Replay must fail when >50% of steps (2 of 3) are skipped due to no effect", ok)
    }

    @Test
    fun testB5_ClickAtInsideImeArea_ReturnsFalse() = runBlocking {
        val fakePort = object : FakeAutomationPort() {
            override fun isInsideImeArea(x: Int, y: Int): Boolean = true
        }
        val skills = SkillMemoryService(InMemorySkillStore())
        val executor = TaskExecutor(
            automation = fakePort,
            skills = skills,
            stepDelayMs = 0
        )
        val step = TaskStep(action = TaskStep.ACTION_CLICK_AT, params = mapOf("x" to 500, "y" to 1500))
        val skill = Skill(goal = "test", steps = listOf(step))
        val result = executor.replaySkillDirect(skill)
        assertEquals(TaskStatus.FAILED, result.status)
        assertTrue("Result must mention keyboard/tap refused: ${result.message}",
            result.message.contains("keyboard") || result.message.contains("tap refused"))
    }

    @Test
    fun testC3_ClickTextFallbackToRecordedCoordinatesWhenTextGone() = runBlocking {
        // Recorder convertEventsToSteps preserves fallbackX and fallbackY on click_text steps
        val recordedSteps = io.github.ryoaonetsuki.mira.service.MiraAccessibilityService.convertEventsToSteps(
            listOf(
                mapOf(
                    "type" to "click",
                    "label" to "Old Video Title",
                    "x" to 540,
                    "y" to 820,
                    "package" to "com.google.android.youtube"
                )
            )
        )
        assertEquals(2, recordedSteps.size) // open_app + click_text
        val clickStep = recordedSteps[1]
        assertEquals(TaskStep.ACTION_CLICK_TEXT, clickStep.action)
        assertEquals("Old Video Title", clickStep.stringParam("text"))
        assertEquals(540, clickStep.intParam("fallbackX"))
        assertEquals(820, clickStep.intParam("fallbackY"))

        // Replay when "Old Video Title" is NOT in screenNodes -> falls back to clickAt(540, 820)
        val fakePort = FakeAutomationPort(
            serviceEnabled = true,
            requireNodeMatchForClickText = true
        )
        val skills = SkillMemoryService(InMemorySkillStore())
        val skill = Skill(goal = "yt", steps = recordedSteps)
        val executor = TaskExecutor(automation = fakePort, skills = skills, stepDelayMs = 0)

        val result = executor.replaySkillDirect(skill)
        assertEquals(TaskStatus.SUCCESS, result.status)
        assertTrue(
            "Must fall back to clickAt(540.0, 820.0) when label is missing on screen",
            fakePort.actionsPerformed.contains("clickAt:540.0,820.0")
        )
    }

    @Test
    fun testC4_TaskTimeout_ReturnsFailedAndFreesSlot() = runBlocking {
        val fakePort = FakeAutomationPort(serviceEnabled = true)
        val skills = SkillMemoryService(InMemorySkillStore())
        val skill = Skill(
            goal = "slow hanging task",
            steps = listOf(TaskStep(action = TaskStep.ACTION_WAIT, params = mapOf("ms" to 2000)))
        )
        val executor = TaskExecutor(
            automation = fakePort,
            skills = skills,
            stepDelayMs = 0,
            taskTimeoutMs = 40L
        )

        val result = executor.replaySkillDirect(skill)
        assertEquals(TaskStatus.FAILED, result.status)
        assertTrue("Timeout message must say Timed out: ${result.message}", result.message.contains("Timed out"))
        assertFalse("Executor _isRunning must be reset after timeout", executor.isRunning)
    }

    @Test
    fun testC4_BridgeStaleGuardAndConcurrentCallProtection() = runBlocking {
        var fakeNow = 1_000L
        var factoryCalls = 0

        val bridge = MiraAutomationBridge(
            context = null,
            taskTimeoutMs = 1_000L,
            clockMs = { fakeNow },
            executorFactory = {
                factoryCalls++
                TaskExecutor(
                    automation = FakeAutomationPort(serviceEnabled = true),
                    skills = SkillMemoryService(InMemorySkillStore()),
                    stepDelayMs = 0,
                    taskTimeoutMs = 5_000L
                )
            }
        )
        val lofiSkill = Skill(
            goal = "play lofi on youtube",
            steps = listOf(TaskStep(action = TaskStep.ACTION_WAIT, params = mapOf("ms" to 300)))
        )
        val spotifySkill = Skill(
            goal = "open spotify",
            steps = listOf(TaskStep(action = TaskStep.ACTION_WAIT, params = mapOf("ms" to 300)))
        )
        val stuckSkill = Skill(
            goal = "stuck task",
            steps = listOf(TaskStep(action = TaskStep.ACTION_WAIT, params = mapOf("ms" to 5000)))
        )

        // 1. Concurrent executeSkill calls -> only one runs, the other is rejected with runningGoal info
        val d1 = async { bridge.executeSkill(lofiSkill) }
        kotlinx.coroutines.delay(15L)
        assertTrue(bridge.isRunning)
        assertEquals("play lofi on youtube", bridge.runningGoal)
        val d2 = async { bridge.executeSkill(spotifySkill) }

        val r1 = d1.await()
        val r2 = d2.await()
        // The factory is only invoked for an accepted run, so 1 call == max 1 concurrent run
        val maxConcurrentRuns = factoryCalls
        assertEquals(1, maxConcurrentRuns)
        assertEquals(TaskStatus.SUCCESS, r1.status)
        assertEquals(TaskStatus.FAILED, r2.status)
        assertTrue(r2.message.contains("Another task is already running"))
        assertTrue("Rejection message must include the running goal", r2.message.contains("play lofi on youtube"))
        assertFalse(bridge.isRunning)

        // 2. Stale-guard: when a task exceeds taskTimeoutMs without completing, isRunning cancels it and frees the slot
        val hungJob = launch {
            bridge.executeSkill(stuckSkill)
        }
        kotlinx.coroutines.delay(15L)
        assertTrue(bridge.isRunning)

        // Advance clock past the 1000ms bridge timeout
        fakeNow += 2_500L
        assertFalse("Stale-guard must report isRunning == false once taskTimeoutMs is exceeded", bridge.isRunning)
        assertNull(bridge.runningGoal)
        hungJob.join()
    }

    // =========================================================================
    // Round 25 Fix Turn E — Stuck Task Diagnosis & Enforceable Timeouts
    // =========================================================================

    @Test
    fun testE2_TaskTimeout_FailsFastWithPhaseSpecificDiagnosis() = runBlocking {
        val port = FakeAutomationPort(serviceEnabled = true)
        val skills = SkillMemoryService(InMemorySkillStore())
        val skill = Skill(
            goal = "test task timeout diagnosis",
            steps = listOf(TaskStep(action = TaskStep.ACTION_WAIT, params = mapOf("ms" to 2000)))
        )

        val executor = TaskExecutor(
            automation = port,
            skills = skills,
            stepDelayMs = 0L,
            taskTimeoutMs = 120L
        )

        val result = executor.replaySkillDirect(skill)
        assertEquals(TaskStatus.FAILED, result.status)
        assertTrue(
            "Expected acting-phase timeout message but was: ${result.message}",
            result.message.contains("Timed out while executing action") &&
                result.message.contains("replay step 1")
        )
        assertEquals(1, result.stepsUsed)
    }

    @Test
    fun testE3_MacroReplayFailure_CarriesSpecificStepAndPhaseDiagnosis() = runBlocking {
        // App launch never changes package and does not match target -> effect verification fails
        val port = FakeAutomationPort(
            serviceEnabled = true,
            currentPkg = "io.github.ryoaonetsuki.mira",
            simulateEffect = false
        )
        val skills = SkillMemoryService(InMemorySkillStore())

        val skill = Skill(
            id = "macro-1",
            goal = "open youtube",
            steps = listOf(
                TaskStep(
                    action = TaskStep.ACTION_OPEN_APP,
                    params = mapOf("app" to "com.google.android.youtube"),
                    reasoning = "open youtube"
                )
            )
        )

        val executor = TaskExecutor(
            automation = port,
            skills = skills,
            stepDelayMs = 0L,
            taskTimeoutMs = 5_000L
        )

        val result = executor.replaySkillDirect(skill)
        assertEquals(TaskStatus.FAILED, result.status)
        assertTrue(
            "Expected failure diagnosis to name macro replay step 1 and app launch failure but was: ${result.message}",
            result.message.contains("Macro replay step 1 failed") &&
                result.message.contains("failed to open com.google.android.youtube")
        )
        assertEquals(1, result.stepsUsed)
    }

    @Test
    fun testE4_StuckPhaseProgressWarning_EmitsPeriodicDiagnosis() = runBlocking {
        val port = FakeAutomationPort(serviceEnabled = true)
        val skills = SkillMemoryService(InMemorySkillStore())
        val progressMessages = mutableListOf<String>()
        // 3500ms wait: the 1s-tick watcher is guaranteed a tick with >=1s elapsed in the acting phase
        val skill = Skill(
            goal = "test progress warning",
            steps = listOf(TaskStep(action = TaskStep.ACTION_WAIT, params = mapOf("ms" to 3500)))
        )

        val executor = TaskExecutor(
            automation = port,
            skills = skills,
            stepDelayMs = 0L,
            taskTimeoutMs = 10_000L,
            phaseWarningThresholdSec = 1L // 1s threshold for unit test
        )
        executor.onProgress = { _, desc ->
            progressMessages.add(desc)
        }

        executor.replaySkillDirect(skill)
        val hasStillWarning = progressMessages.any { it.contains("Still acting…") }
        assertTrue("Expected progress callback to emit 'Still acting…' warning: $progressMessages", hasStillWarning)
    }

    // =========================================================================
    // Round 25 Fix Turn F — Phone Control Speed + AI-Reply Hang
    // =========================================================================

    @Test
    fun testF_OpenAppPolling_ColdStartSucceeds() = runBlocking {
        // YouTube cold start: the package only appears after several
        // foreground-package checks. The old fixed 1200ms settle + one
        // retry called this a failure; polling must wait it out.
        val fakePort = FakeAutomationPort(
            serviceEnabled = true,
            packageAppearAfterChecks = 3
        )
        val skills = SkillMemoryService(InMemorySkillStore())
        val skill = Skill(
            goal = "yt",
            steps = listOf(
                TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("app" to "youtube")),
                TaskStep(action = TaskStep.ACTION_CLICK_TEXT, params = mapOf("text" to "Search"))
            )
        )
        val executor = TaskExecutor(
            automation = fakePort,
            skills = skills,
            stepDelayMs = 1L,
            openAppPollTimeoutMs = 5_000L,
            openAppPollIntervalMs = 100L
        )

        val result = executor.replaySkillDirect(skill)
        assertEquals(TaskStatus.SUCCESS, result.status)
        assertTrue(fakePort.actionsPerformed.contains("openApp:youtube"))
        assertTrue(fakePort.actionsPerformed.contains("clickText:Search"))
        assertTrue(
            "Replay must have polled the foreground package while the app was launching",
            fakePort.packageCheckCount >= 3
        )
    }

    @Test
    fun testF_OpenAppPolling_AlreadyInTargetPackage_SucceedsImmediately() = runBlocking {
        // Already in the target app counts as success — no waiting.
        val fakePort = FakeAutomationPort(
            serviceEnabled = true,
            currentPkg = "com.google.android.youtube"
        )
        val skills = SkillMemoryService(InMemorySkillStore())
        val skill = Skill(
            goal = "yt",
            steps = listOf(
                TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("app" to "youtube"))
            )
        )
        val executor = TaskExecutor(automation = fakePort, skills = skills, stepDelayMs = 0)

        val result = executor.replaySkillDirect(skill)
        assertEquals(TaskStatus.SUCCESS, result.status)
        assertTrue(
            "Polling must succeed on the first check when already in the target package",
            fakePort.packageCheckCount <= 3
        )
    }

    @Test
    fun testF_Resume_FailedStepFailsTruthfully_WithoutAiFixup() = runBlocking {
        // Replay fails at step 1 (open_app dispatch fails); there is no AI
        // fallback any more — the failure is reported truthfully and the
        // skill is retained.
        val fakePort = FakeAutomationPort(serviceEnabled = true, actionSuccess = false)
        val skills = SkillMemoryService(InMemorySkillStore())
        val saved = skills.saveOrUpdate(
            goal = "yt",
            steps = listOf(
                TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("app" to "youtube")),
                TaskStep(action = TaskStep.ACTION_CLICK_TEXT, params = mapOf("text" to "Library")),
                TaskStep(action = TaskStep.ACTION_CLICK_TEXT, params = mapOf("text" to "Search"))
            )
        )
        val executor = TaskExecutor(automation = fakePort, skills = skills, stepDelayMs = 0)

        val result = executor.replaySkillDirect(saved)
        assertEquals(TaskStatus.FAILED, result.status)
        assertTrue("Diagnosis must name step 1: ${result.message}", result.message.contains("step 1"))
        assertNotNull("Skill must be retained after a failed replay", skills.findMatch("yt"))
    }

    @Test
    fun testF_Replay_ReusesPostDumpAsNextPreDump() = runBlocking {
        // Turn F(e): a 3-step replay used to dump 6x (pre + post per step);
        // the post-verification dump is now the next step's pre-dump.
        val fakePort = FakeAutomationPort(serviceEnabled = true)
        val skills = SkillMemoryService(InMemorySkillStore())
        val skill = Skill(
            goal = "yt",
            steps = listOf(
                TaskStep(action = TaskStep.ACTION_CLICK_TEXT, params = mapOf("text" to "A")),
                TaskStep(action = TaskStep.ACTION_CLICK_TEXT, params = mapOf("text" to "B")),
                TaskStep(action = TaskStep.ACTION_CLICK_TEXT, params = mapOf("text" to "C"))
            )
        )
        val executor = TaskExecutor(automation = fakePort, skills = skills, stepDelayMs = 0)

        val ok = executor.replaySkill(skill)
        assertTrue(ok)
        assertEquals("3-step replay must read the screen 4x (pre + 3 posts), not 6x", 4, fakePort.dumpCount)
    }

    // =========================================================================
    // Round 25 Fix Turn F — Direct fast path (Voice Access model), plan-once,
    // learning loop, voice-path deadline
    // =========================================================================

    @Test
    fun testDirect_OpenFacebook_En_ZeroThink() {
        assertEquals(MiraAutomationBridge.DirectAction.OpenApp("com.facebook.katana"), MiraAutomationBridge.parseDirectAction("open Facebook"))
        assertTrue(MiraAutomationBridge(context = null).isDirectGoal("open Facebook"))
    }

    @Test
    fun testDirect_OpenFacebook_BanglaNatural_ZeroThink() {
        assertEquals(MiraAutomationBridge.DirectAction.OpenApp("com.facebook.katana"), MiraAutomationBridge.parseDirectAction("ফেসবুকটা ওপেন করো তো"))
        assertTrue(MiraAutomationBridge(context = null).isDirectGoal("ফেসবুকটা ওপেন করো তো"))
    }

    @Test
    fun testDirect_Home_Bangla_ZeroThink() {
        assertEquals(MiraAutomationBridge.DirectAction.PressHome, MiraAutomationBridge.parseDirectAction("হোমপেজে আসো"))
        assertTrue(MiraAutomationBridge(context = null).isDirectGoal("হোমপেজে আসো"))
    }

    @Test
    fun testDirect_ScrollDownABit_Bangla_ZeroThink() {
        assertEquals(MiraAutomationBridge.DirectAction.Scroll("down", true), MiraAutomationBridge.parseDirectAction("একটু নিচে স্ক্রল করো তো"))
        assertTrue(MiraAutomationBridge(context = null).isDirectGoal("একটু নিচে স্ক্রল করো তো"))
    }

    @Test
    fun testDirect_GoUpABit_Bangla_ZeroThink() {
        assertEquals(MiraAutomationBridge.DirectAction.Scroll("up", true), MiraAutomationBridge.parseDirectAction("একটু উপরের দিকে যাও"))
        assertTrue(MiraAutomationBridge(context = null).isDirectGoal("একটু উপরের দিকে যাও"))
    }

    @Test
    fun testDirect_TapNumber_ZeroThink() {
        assertEquals(MiraAutomationBridge.DirectAction.TapNumber(3), MiraAutomationBridge.parseDirectAction("tap 3"))
        assertEquals(MiraAutomationBridge.DirectAction.TapNumber(5), MiraAutomationBridge.parseDirectAction("tap number 5"))
        assertEquals(MiraAutomationBridge.DirectAction.TapNumber(3), MiraAutomationBridge.parseDirectAction("tap ৩"))
        assertTrue(MiraAutomationBridge(context = null).isDirectGoal("tap 3"))
    }

    @Test
    fun testDirect_TapLabel_ZeroThink() {
        assertEquals(MiraAutomationBridge.DirectAction.TapLabel("settings"), MiraAutomationBridge.parseDirectAction("tap Settings"))
        assertTrue(MiraAutomationBridge(context = null).isDirectGoal("tap Settings"))
    }

    @Test
    fun testDirect_Search_ZeroThink() {
        assertEquals(MiraAutomationBridge.DirectAction.Search("lofi", "com.google.android.youtube"), MiraAutomationBridge.parseDirectAction("search lofi on youtube"))
        assertTrue(MiraAutomationBridge(context = null).isDirectGoal("search lofi on youtube"))
    }

    @Test
    fun testDirect_Dial_ZeroThink() {
        assertEquals(MiraAutomationBridge.DirectAction.Dial("01712345678"), MiraAutomationBridge.parseDirectAction("call 01712345678"))
        assertTrue(MiraAutomationBridge(context = null).isDirectGoal("call 01712345678"))
    }

    // --- Best-of-all: flutter_automate long_press / pinch ---

    @Test
    fun testTaskStep_LongPressAndPinchAliases() {
        val longPress = TaskStep.tryParse(
            """{"action": "long_click", "params": {"x": 500, "y": 800, "durationMs": 600}}"""
        )
        assertNotNull(longPress)
        assertEquals(TaskStep.ACTION_LONG_PRESS, longPress!!.action)
        assertEquals(500, longPress.intParam("x"))

        val pinchOut = TaskStep.tryParse(
            """{"action": "zoom_out", "params": {"centerX": 540, "centerY": 960}}"""
        )
        assertNotNull(pinchOut)
        assertEquals(TaskStep.ACTION_PINCH, pinchOut!!.action)
        assertEquals("out", pinchOut.stringParam("direction"))

        val pinchIn = TaskStep.tryParse(
            """{"action": "pinch_in", "params": {"centerX": 540, "centerY": 960}}"""
        )
        assertNotNull(pinchIn)
        assertEquals(TaskStep.ACTION_PINCH, pinchIn!!.action)
        assertEquals("in", pinchIn.stringParam("direction"))

        assertTrue(TaskStep.supportedActions.contains(TaskStep.ACTION_LONG_PRESS))
        assertTrue(TaskStep.supportedActions.contains(TaskStep.ACTION_PINCH))
    }

    // --- Best-of-all: flutter_automate UiSelector matching semantics ---

    @Test
    fun testUiNodeMatcher_FindByViewIdAndClass() {
        val nodes = listOf(
            ScreenNode(0, "", "", "android.widget.ImageButton", "com.example.app:id/search_button", isClickable = true, isEditable = false, isScrollable = false, isCheckable = false, isEnabled = true, 0, 0, 10, 10, 5, 5),
            ScreenNode(1, "OK", "", "android.widget.Button", "", isClickable = true, isEditable = false, isScrollable = false, isCheckable = false, isEnabled = true, 0, 0, 10, 10, 5, 5)
        )

        // :id/ suffix rule: short query matches the full resource-id.
        val byId = UiNodeMatcher.findByViewId(nodes, "search_button")
        assertNotNull(byId)
        assertEquals("com.example.app:id/search_button", byId!!.viewId)
        // Exact full id also matches.
        assertNotNull(UiNodeMatcher.findByViewId(nodes, "com.example.app:id/search_button"))
        assertNull(UiNodeMatcher.findByViewId(nodes, "missing_button"))

        // android.widget.<ShortName> rule: "Button" matches android.widget.Button.
        val byClass = UiNodeMatcher.findByClass(nodes, "Button")
        assertNotNull(byClass)
        assertEquals("OK", byClass!!.text)
        assertNull(UiNodeMatcher.findByClass(nodes, "TextView"))

        // findBest falls back to viewId when no visible text matches.
        val best = UiNodeMatcher.findBest(nodes, "search_button")
        assertNotNull(best)
        assertEquals("com.example.app:id/search_button", best!!.viewId)
        assertNull(UiNodeMatcher.findBest(nodes, "nothing here"))
    }

    @Test
    fun testUiNodeMatcher_TappableNumbered() {
        val node1 = ScreenNode(0, "First", "", "android.widget.Button", "", isClickable = true, isEditable = false, isScrollable = false, isCheckable = false, isEnabled = true, 0, 0, 10, 10, 5, 5)
        val node2NonClickable = ScreenNode(1, "Second", "", "android.widget.TextView", "", isClickable = false, isEditable = false, isScrollable = false, isCheckable = false, isEnabled = true, 0, 0, 10, 10, 5, 5)
        val node3Disabled = ScreenNode(2, "Third", "", "android.widget.Button", "", isClickable = true, isEditable = false, isScrollable = false, isCheckable = false, isEnabled = false, 0, 0, 10, 10, 5, 5)
        val node4ZeroWidth = ScreenNode(3, "Fourth", "", "android.widget.Button", "", isClickable = true, isEditable = false, isScrollable = false, isCheckable = false, isEnabled = true, 5, 0, 5, 10, 5, 5)
        val node5ZeroHeight = ScreenNode(4, "Fifth", "", "android.widget.Button", "", isClickable = true, isEditable = false, isScrollable = false, isCheckable = false, isEnabled = true, 0, 5, 10, 5, 5, 5)
        val node6 = ScreenNode(5, "Sixth", "", "android.widget.Button", "", isClickable = true, isEditable = false, isScrollable = false, isCheckable = false, isEnabled = true, 0, 0, 20, 20, 10, 10)
        val node7 = ScreenNode(6, "Seventh", "", "android.widget.Button", "", isClickable = true, isEditable = false, isScrollable = false, isCheckable = false, isEnabled = true, 10, 10, 30, 30, 20, 20)

        val inputNodes = listOf(node1, node2NonClickable, node3Disabled, node4ZeroWidth, node5ZeroHeight, node6, node7)
        val numbered = UiNodeMatcher.tappableNumbered(inputNodes)

        assertEquals(3, numbered.size)
        assertEquals(1, numbered[0].first)
        assertEquals(node1, numbered[0].second)
        assertEquals(2, numbered[1].first)
        assertEquals(node6, numbered[1].second)
        assertEquals(3, numbered[2].first)
        assertEquals(node7, numbered[2].second)
    }

    @Test
    fun testFormatNumberedList() {
        val emptyResult = formatNumberedList(emptyList())
        assertEquals("", emptyResult)

        val nodeText = ScreenNode(
            index = 0,
            text = "Submit",
            contentDescription = "Submit button",
            className = "android.widget.Button",
            viewId = "id/submit",
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            isCheckable = false,
            isEnabled = true,
            left = 0, top = 0, right = 10, bottom = 10, centerX = 5, centerY = 5
        )
        val nodeDesc = ScreenNode(
            index = 1,
            text = "",
            contentDescription = "Close dialog",
            className = "android.widget.ImageView",
            viewId = "id/close",
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            isCheckable = false,
            isEnabled = true,
            left = 10, top = 10, right = 20, bottom = 20, centerX = 15, centerY = 15
        )
        val nodeClass = ScreenNode(
            index = 2,
            text = "   ",
            contentDescription = "",
            className = "android.widget.CheckBox",
            viewId = "id/chk",
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            isCheckable = false,
            isEnabled = true,
            left = 20, top = 20, right = 30, bottom = 30, centerX = 25, centerY = 25
        )

        val numbered = listOf(
            Pair(1, nodeText),
            Pair(2, nodeDesc),
            Pair(3, nodeClass)
        )
        val formatted = formatNumberedList(numbered)
        val expected = "1: Submit\n2: Close dialog\n3: CheckBox"
        assertEquals(expected, formatted)
    }

    // --- Best-of-all: Mika per-app knowledge files ---

    @Test
    fun testAppKnowledge_NoteLookup() {
        val notes = mapOf(
            "automation/knowledge/youtube.md" to "YouTube notes",
            "automation/knowledge/facebook.md" to "Facebook notes"
        )
        val knowledge = AppKnowledge { path -> notes[path] }

        assertEquals("YouTube notes", knowledge.noteForTarget("com.google.android.youtube"))
        assertEquals("YouTube notes", knowledge.noteForTarget("youtube"))
        assertEquals("Facebook notes", knowledge.noteForTarget("com.facebook.katana"))
        assertNull("Unknown apps have no note — never a failure", knowledge.noteForTarget("unknown.app"))
        assertNull(knowledge.noteForTarget(null))
        assertEquals("youtube", AppKnowledge.slugFor("com.google.android.youtube"))
        assertNull(AppKnowledge.slugFor("unknown.app"))
    }

    // --- Turn G Residual Tests (Items 3, 4, 5, 7, 8) ---

    @Test
    fun testSearchPath_PollsForOpenAppBeforeReplay() = runBlocking {
        // Item 3: open_app inside a replay must poll/settle until the target package appears.
        val fakePort = FakeAutomationPort(
            currentPkg = "io.github.ryoaonetsuki.mira",
            packageAppearAfterChecks = 2 // Takes 2 checks before package appears
        )
        val executor = TaskExecutor(
            automation = fakePort,
            skills = SkillMemoryService(InMemorySkillStore()),
            stepDelayMs = 10L,
            openAppPollTimeoutMs = 1000L,
            openAppPollIntervalMs = 10L
        )
        val skill = Skill(
            goal = "search John on WhatsApp",
            steps = listOf(TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("app" to "whatsapp")))
        )

        executor.replaySkillDirect(skill)
        assertTrue("openApp must have been called for whatsapp", fakePort.actionsPerformed.any { it.startsWith("openApp:") && it.contains("whatsapp") })
        assertEquals("com.whatsapp", fakePort.currentPkg)
    }

    @Test
    fun testGoalAwareVerifyEffect_RejectsWrongPackage() {
        val clickStep = TaskStep(action = TaskStep.ACTION_CLICK_TEXT, params = mapOf("text" to "Send"))
        val homeStep = TaskStep(action = TaskStep.ACTION_PRESS_HOME)

        // Goal has target WhatsApp: changing package to Chrome is a WRONG package failure
        val wrongPkgResult = TaskExecutor.verifyEffect(
            step = clickStep,
            prePackage = "com.whatsapp",
            preSignature = 100,
            postPackage = "com.android.chrome",
            postSignature = 200,
            goal = "send message on WhatsApp"
        )
        assertFalse("Wrong package change to Chrome must NOT verify as effect for WhatsApp goal", wrongPkgResult)

        // Staying in same package with node signature change verifies
        val samePkgResult = TaskExecutor.verifyEffect(
            step = clickStep,
            prePackage = "com.whatsapp",
            preSignature = 100,
            postPackage = "com.whatsapp",
            postSignature = 200,
            goal = "send message on WhatsApp"
        )
        assertTrue("Signature change in same package must verify", samePkgResult)

        // WhatsApp Business alias counts as target
        val aliasPkgResult = TaskExecutor.verifyEffect(
            step = clickStep,
            prePackage = "io.github.ryoaonetsuki.mira",
            preSignature = 100,
            postPackage = "com.whatsapp.w4b",
            postSignature = 200,
            goal = "send message on WhatsApp"
        )
        assertTrue("WhatsApp Business alias must count as target", aliasPkgResult)

        // Press home to launcher verifies
        val homeResult = TaskExecutor.verifyEffect(
            step = homeStep,
            prePackage = "com.whatsapp",
            preSignature = 100,
            postPackage = "com.google.android.apps.nexuslauncher",
            postSignature = 200,
            goal = "send message on WhatsApp"
        )
        assertTrue("Press home to launcher package must verify", homeResult)

        // Web goal: browser package verifies
        val webBrowserResult = TaskExecutor.verifyEffect(
            step = clickStep,
            prePackage = "io.github.ryoaonetsuki.mira",
            preSignature = 100,
            postPackage = "com.android.chrome",
            postSignature = 200,
            goal = "search weather on google"
        )
        assertTrue("Browser package must verify for web goal", webBrowserResult)

        // Web goal: non-browser package does not verify
        val webNonBrowserResult = TaskExecutor.verifyEffect(
            step = clickStep,
            prePackage = "io.github.ryoaonetsuki.mira",
            preSignature = 100,
            postPackage = "com.google.android.youtube",
            postSignature = 200,
            goal = "search weather on google"
        )
        assertFalse("Non-browser package must NOT verify for web goal", webNonBrowserResult)
    }

    @Test
    fun testDialStep_CallsDialNumberAndVerifies() = runBlocking {
        assertEquals("dial", TaskStep.ACTION_DIAL)
        assertEquals(TaskStep.ACTION_DIAL, TaskStep.actionDial)
        assertTrue(TaskStep.supportedActions.contains(TaskStep.ACTION_DIAL))

        val fakePort = FakeAutomationPort(
            currentPkg = "io.github.ryoaonetsuki.mira"
        )
        val executor = TaskExecutor(
            automation = fakePort,
            skills = SkillMemoryService(InMemorySkillStore()),
            stepDelayMs = 0L
        )
        val dialStep = TaskStep(action = TaskStep.ACTION_DIAL, params = mapOf("number" to "1234567890"))
        val skill = Skill(
            goal = "dial 1234567890",
            steps = listOf(dialStep)
        )

        val result = executor.replaySkillDirect(skill)
        assertEquals(TaskStatus.SUCCESS, result.status)
        assertTrue("dialNumber must have been called", fakePort.actionsPerformed.contains("dialNumber:1234567890"))
        assertEquals("com.google.android.dialer", fakePort.currentPkg)

        val verified = TaskExecutor.verifyEffect(
            step = dialStep,
            prePackage = "io.github.ryoaonetsuki.mira",
            preSignature = 100,
            postPackage = "com.google.android.dialer",
            postSignature = 200,
            goal = "dial 1234567890"
        )
        assertTrue("Foreground dialer package must verify dial step", verified)

        val notVerified = TaskExecutor.verifyEffect(
            step = dialStep,
            prePackage = "io.github.ryoaonetsuki.mira",
            preSignature = 100,
            postPackage = "com.android.chrome",
            postSignature = 200,
            goal = "dial 1234567890"
        )
        assertFalse("Non-dialer package must NOT verify dial step", notVerified)
    }

    @Test
    fun testDialStep_MissingOrBlankNumberFails() = runBlocking {
        val fakePort = FakeAutomationPort(
            currentPkg = "io.github.ryoaonetsuki.mira"
        )
        val executor = TaskExecutor(
            automation = fakePort,
            skills = SkillMemoryService(InMemorySkillStore()),
            stepDelayMs = 0L
        )
        val emptyStep = TaskStep(action = TaskStep.ACTION_DIAL, params = emptyMap())
        val skill = Skill(
            goal = "dial",
            steps = listOf(emptyStep)
        )
        val result = executor.replaySkillDirect(skill)
        assertEquals(TaskStatus.FAILED, result.status)
        assertFalse("dialNumber must NOT have been called with empty number", fakePort.actionsPerformed.any { it.startsWith("dialNumber") })
    }

    @Test
    fun testVisionFallbackTrigger_NodeSearchFailsCallsVision_NodeSearchSucceedsDoesNotCallVision() = runBlocking {
        var visionLocatorCalledWith: String? = null
        val visionLocator: suspend (String, List<ScreenNode>) -> Pair<Int, Int>? = { target, _ ->
            visionLocatorCalledWith = target
            Pair(450, 120)
        }

        // Case A: Node search fails -> vision fallback is requested and tapped
        val fakePortNoMatch = FakeAutomationPort(
            requireNodeMatchForClickText = true,
            screenNodes = listOf(
                ScreenNode(
                    index = 0,
                    text = "Profile",
                    contentDescription = "Profile tab",
                    className = "android.widget.TextView",
                    viewId = "profile_tab",
                    isClickable = true,
                    isEditable = false,
                    isScrollable = false,
                    isCheckable = false,
                    isEnabled = true,
                    left = 10,
                    top = 10,
                    right = 100,
                    bottom = 50,
                    centerX = 55,
                    centerY = 30
                )
            )
        )
        val executorWithVision = TaskExecutor(
            automation = fakePortNoMatch,
            skills = SkillMemoryService(InMemorySkillStore()),
            stepDelayMs = 0L,
            visionLocator = visionLocator
        )

        val searchStep = TaskStep(action = TaskStep.ACTION_CLICK_TEXT, params = mapOf("text" to "Search Magnifier"))
        val skillNoMatch = Skill(goal = "search facebook", steps = listOf(searchStep))
        val resultA = executorWithVision.replaySkillDirect(skillNoMatch)

        assertEquals(TaskStatus.SUCCESS, resultA.status)
        assertEquals("Search Magnifier", visionLocatorCalledWith)
        assertTrue(
            "clickAt must have been dispatched with vision coordinates (450, 120)",
            fakePortNoMatch.actionsPerformed.contains("clickAt:450.0,120.0")
        )

        // Case B: Node search succeeds -> vision fallback is NOT called
        visionLocatorCalledWith = null
        val fakePortWithMatch = FakeAutomationPort(
            requireNodeMatchForClickText = true,
            screenNodes = listOf(
                ScreenNode(
                    index = 0,
                    text = "Search Magnifier",
                    contentDescription = "Search",
                    className = "android.widget.Button",
                    viewId = "search_btn",
                    isClickable = true,
                    isEditable = false,
                    isScrollable = false,
                    isCheckable = false,
                    isEnabled = true,
                    left = 10,
                    top = 10,
                    right = 100,
                    bottom = 50,
                    centerX = 55,
                    centerY = 30
                )
            )
        )
        val executorDirect = TaskExecutor(
            automation = fakePortWithMatch,
            skills = SkillMemoryService(InMemorySkillStore()),
            stepDelayMs = 0L,
            visionLocator = visionLocator
        )

        val resultB = executorDirect.replaySkillDirect(skillNoMatch)
        assertEquals(TaskStatus.SUCCESS, resultB.status)
        assertNull("Vision locator must NOT be called when node search succeeds", visionLocatorCalledWith)
        assertTrue(
            "clickText must be called directly",
            fakePortWithMatch.actionsPerformed.contains("clickText:Search Magnifier")
        )
        assertFalse(
            "clickAt must NOT be called when node search succeeds",
            fakePortWithMatch.actionsPerformed.any { it.startsWith("clickAt") }
        )
    }

    @Test
    fun testTypeFlow_FocusThenSetThenReadbackAndRetryOnMismatch() = runBlocking {
        // Sub-test A: Normal flow - focus field, wait, set text, read back, enter
        val normalPort = FakeAutomationPort()
        normalPort.inputFocusedState = false
        normalPort.screenNodes = listOf(
            ScreenNode(
                index = 0,
                text = "Search input",
                contentDescription = "Search",
                className = "android.widget.EditText",
                viewId = "search_src_text",
                isClickable = true,
                isEditable = true,
                isScrollable = false,
                isCheckable = false,
                isEnabled = true,
                left = 50,
                top = 50,
                right = 500,
                bottom = 150,
                centerX = 275,
                centerY = 100
            )
        )

        val normalResult = TaskExecutor.executeTypeFlow(
            automation = normalPort,
            text = "salman",
            targetQuery = "Search input",
            pressEnter = true,
            pollTimeoutMs = 0L
        )

        assertTrue("executeTypeFlow must succeed", normalResult.success)
        assertEquals("salman", normalResult.textTyped)
        assertTrue("Must tap to focus editable field", normalPort.actionsPerformed.contains("clickAt:275.0,100.0"))
        assertTrue("Must set text on focused field", normalPort.actionsPerformed.any { it.startsWith("setTextOnFocused:salman") })
        assertTrue("Must press enter after typing", normalPort.actionsPerformed.contains("pressEnter"))
        assertEquals("Readback must equal typed text", "salman", normalPort.getFocusedText())
        assertEquals("Should set once when readback matches immediately", 1, normalPort.setAttempts)

        // Sub-test B: Readback mismatch triggers retry
        val retryPort = FakeAutomationPort()
        retryPort.inputFocusedState = true // already focused
        retryPort.failFirstSet = true

        val retryResult = TaskExecutor.executeTypeFlow(
            automation = retryPort,
            text = "android dev",
            pressEnter = false,
            pollTimeoutMs = 0L
        )

        assertTrue("executeTypeFlow must succeed after retry", retryResult.success)
        assertEquals("android dev", retryResult.textTyped)
        assertEquals("Must retry setting text once upon readback mismatch", 2, retryPort.setAttempts)
        assertEquals("Final text must match intended text after retry", "android dev", retryPort.getFocusedText())
        assertFalse("Must NOT press enter when pressEnter is false", retryPort.actionsPerformed.contains("pressEnter"))
    }

    @Test
    fun testScrollGuard_FiveConsecutiveScrollsAllExecuteWithoutFailFast() = runBlocking {
        // In content feeds (Facebook, YouTube), headers dominate node dumps,
        // so simulateEffect is false (node signature does not change across scrolls).
        val feedPort = FakeAutomationPort(
            currentPkg = "com.facebook.katana",
            simulateEffect = false
        )
        val executor = TaskExecutor(
            automation = feedPort,
            skills = SkillMemoryService(InMemorySkillStore()),
            stepDelayMs = 0L
        )

        val fiveScrollSteps = (1..5).map {
            TaskStep(action = TaskStep.ACTION_SCROLL, params = mapOf("direction" to "down"))
        }
        val feedScrollSkill = Skill(
            goal = "browse facebook feed",
            steps = fiveScrollSteps
        )

        val result = executor.replaySkillDirect(feedScrollSkill)
        assertEquals("Skill with 5 consecutive scrolls must SUCCEED", TaskStatus.SUCCESS, result.status)
        val scrollDispatches = feedPort.actionsPerformed.filter { it == "scroll:down" }
        assertEquals("All 5 scroll actions must execute without being aborted by loop guard", 5, scrollDispatches.size)

        // A-M3 (Fix Round 2): a scroll with no signature change and no frame
        // evidence is NOT verified — the old "identical signature still verifies"
        // assertion is superseded. Replay still succeeds (it forces scroll
        // effectVerified=true), but direct verifyEffect is now honest.
        for (i in 1..5) {
            val verifiedNoEvidence = TaskExecutor.verifyEffect(
                step = TaskStep(action = TaskStep.ACTION_SCROLL, params = mapOf("direction" to "down")),
                prePackage = "com.facebook.katana",
                preSignature = 12345,
                postPackage = "com.facebook.katana",
                postSignature = 12345, // identical signature hash in feed!
                goal = "browse facebook feed",
                targetApp = "com.facebook.katana",
                frameChanged = null
            )
            assertFalse("Scroll with identical signature and no frame evidence must NOT verify", verifiedNoEvidence)
            val verifiedWithFrame = TaskExecutor.verifyEffect(
                step = TaskStep(action = TaskStep.ACTION_SCROLL, params = mapOf("direction" to "down")),
                prePackage = "com.facebook.katana",
                preSignature = 12345,
                postPackage = "com.facebook.katana",
                postSignature = 12345, // identical signature hash in feed!
                goal = "browse facebook feed",
                targetApp = "com.facebook.katana",
                frameChanged = true
            )
            assertTrue("Scroll with pixel frame change verifies even with identical signature", verifiedWithFrame)
        }
    }

    @Test
    fun testAppKnowledge_FacebookAndYoutubeContainSearchFlowAndScrollNotes() {
        val knowledge = AppKnowledge()

        // 1. Facebook note
        val fbNote = knowledge.noteForTarget("com.facebook.katana")
        assertNotNull("Facebook knowledge note must load", fbNote)
        assertTrue("Facebook note must mention top bar search icon", fbNote!!.contains("top bar", ignoreCase = true))
        assertTrue("Facebook note must mention vision coordinates for icon-only magnifier", fbNote.contains("vision coordinates", ignoreCase = true))
        assertTrue("Facebook note must explain screen after tapping (search field + keyboard)", fbNote.contains("keyboard", ignoreCase = true))
        assertTrue("Facebook note must state typing needs field focused first", fbNote.contains("focused first", ignoreCase = true))
        assertTrue("Facebook note must verify text read back", fbNote.contains("read back", ignoreCase = true))
        assertTrue("Facebook note must state feed scrolls are normal and not loop failure", fbNote.contains("not a loop failure", ignoreCase = true))

        // 2. YouTube note
        val ytNote = knowledge.noteForTarget("com.google.android.youtube")
        assertNotNull("YouTube knowledge note must load", ytNote)
        assertTrue("YouTube note must mention magnifier icon in top bar", ytNote!!.contains("magnifier", ignoreCase = true))
        assertTrue("YouTube note must mention vision coordinates fallback", ytNote.contains("vision coordinates", ignoreCase = true))
        assertTrue("YouTube note must mention search field and keyboard", ytNote.contains("keyboard", ignoreCase = true))
        assertTrue("YouTube note must mention focus before typing", ytNote.contains("focused first", ignoreCase = true))
        assertTrue("YouTube note must state video/feed scrolls are normal and not loop failure", ytNote.contains("not a loop failure", ignoreCase = true))

        // 3. Shared search flow note
        val searchNote = knowledge.searchFlowNote()
        assertNotNull("Search flow note must load", searchNote)
        assertTrue("Search note must mention magnifier with no text label", searchNote!!.contains("magnifier", ignoreCase = true))
        assertTrue("Search note must mention vision coordinates", searchNote.contains("vision coordinates", ignoreCase = true))
        assertTrue("Search note must mention read back to verify", searchNote.contains("read back", ignoreCase = true))
        assertTrue("Search note must mention feed scrolls are not a loop failure", searchNote.contains("not a loop failure", ignoreCase = true))

        // 4. Summary for prompt
        val summary = knowledge.summaryForPrompt()
        assertTrue("Summary must include search flow", summary.contains("Cross-App Search Flow", ignoreCase = true))
        assertTrue("Summary must include Facebook notes", summary.contains("Facebook Notes", ignoreCase = true))
        assertTrue("Summary must include YouTube notes", summary.contains("YouTube Notes", ignoreCase = true))
    }

    // =========================================================================
    // 5. Round 3 Tests: matchesTarget, isTargetPackage, isWebGoal, Vision Reason
    // =========================================================================

    @Test
    fun testMatchesTarget_PlayStoreAndEverydayApps() {
        assertTrue(TaskExecutor.matchesTarget("Play Store", "com.android.vending"))
        assertTrue(TaskExecutor.matchesTarget("play store", "com.android.vending"))
        assertTrue(TaskExecutor.matchesTarget("প্লে স্টোর", "com.android.vending"))
        assertTrue(TaskExecutor.matchesTarget("google play store", "com.android.vending"))

        assertTrue(TaskExecutor.matchesTarget("Photos", "com.google.android.apps.photos"))
        assertTrue(TaskExecutor.matchesTarget("Drive", "com.google.android.apps.docs"))
        assertTrue(TaskExecutor.matchesTarget("Files", "com.google.android.apps.nbu.files"))
        assertTrue(TaskExecutor.matchesTarget("Clock", "com.google.android.deskclock"))
        assertTrue(TaskExecutor.matchesTarget("Calculator", "com.google.android.calculator"))
        assertTrue(TaskExecutor.matchesTarget("Calendar", "com.google.android.calendar"))
        assertTrue(TaskExecutor.matchesTarget("Contacts", "com.google.android.contacts"))
        assertTrue(TaskExecutor.matchesTarget("Keep", "com.google.android.keep"))
        assertTrue(TaskExecutor.matchesTarget("Play Games", "com.google.android.play.games"))
    }

    @Test
    fun testOpenAppTargetReached_and_VerifyEffect_withIsTargetPackageFallback() {
        val query = "Custom Enterprise App"
        val pkg = "com.unlisted.enterprise.suite"

        // Without port lookup or known aliases, matchesTarget returns false
        assertFalse(TaskExecutor.matchesTarget(query, pkg))

        val step = TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("app" to query))

        // Without port, target is not reached
        val reachedWithoutPort = TaskExecutor.openAppTargetReached(step, "io.github.ryoaonetsuki.mira", pkg, port = null)
        assertFalse(reachedWithoutPort)

        // With port resolving the real package via PackageManager label fallback:
        val port = FakeAutomationPort(currentPkg = pkg)
        port.targetPackageResolver = { targetQuery, targetPkg ->
            targetQuery == query && targetPkg == pkg
        }

        val reachedWithPort = TaskExecutor.openAppTargetReached(step, "io.github.ryoaonetsuki.mira", pkg, port = port)
        assertTrue("Must recognize app opened via port.isTargetPackage label fallback", reachedWithPort)

        val verified = TaskExecutor.verifyEffect(
            step = step,
            prePackage = "io.github.ryoaonetsuki.mira",
            preSignature = 100,
            postPackage = pkg,
            postSignature = 200,
            goal = "open $query",
            targetApp = query,
            port = port
        )
        assertTrue("verifyEffect must succeed when port.isTargetPackage verifies the package", verified)
    }

    @Test
    fun testIsWebGoal_differentiatesWebSearchFromInAppSearchAndButtons() {
        assertTrue(TaskExecutor.isWebGoal("https://google.com"))
        assertTrue(TaskExecutor.isWebGoal("search google for weather"))
        assertTrue(TaskExecutor.isWebGoal("search the web for kotlin documentation"))
        // Plain "search ... on google" with no app target is a web goal too
        // (regression: the button-word guard must not swallow plain searches).
        assertTrue(TaskExecutor.isWebGoal("search weather on google"))

        // In-app actions containing search words or button words must NOT be web goals
        assertFalse(TaskExecutor.isWebGoal("click search button"))
        assertFalse(TaskExecutor.isWebGoal("search বাটনে click করো"))
        assertFalse(TaskExecutor.isWebGoal("tap the search button please"))
        assertFalse(TaskExecutor.isWebGoal("search for apps in play store"))
    }

    @Test
    fun testVisionFailureReason_propagation() = runBlocking {
        val fakePort = FakeAutomationPort(
            currentPkg = "com.android.vending",
            actionSuccess = false,
            simulateEffect = false
        )
        val executor = TaskExecutor(
            automation = fakePort,
            skills = SkillMemoryService(InMemorySkillStore()),
            stepDelayMs = 0L,
            visionLocator = { query, _ ->
                // Simulate locator failing to find coordinates
                null
            }
        )

        val coords = executor.resolveVisionCoordinates("search button", fakePort.screenNodes)
        assertNull(coords)
        assertNotNull(executor.lastVisionFailureReason)
        assertTrue(
            "lastVisionFailureReason must mention query",
            executor.lastVisionFailureReason!!.contains("search button")
        )

        // Verify click_text failure propagates the vision failure reason into lastActionError.
        // (_performAction is private, so exercise it through the public replay path.)
        val skill = Skill(
            goal = "tap search button",
            steps = listOf(TaskStep(action = TaskStep.ACTION_CLICK_TEXT, params = mapOf("text" to "search button")))
        )
        val result = executor.replaySkillDirect(skill)
        assertEquals(TaskStatus.FAILED, result.status)
        // The vision failure reason must reach the caller through the public result
        // message (this is what the Live model speaks back to the user).
        assertTrue(
            "result message must contain vision failure reason",
            result.message.contains("search button")
        )
        assertTrue(
            "result message must name the failed vision locator",
            result.message.contains("custom vision locator returned null")
        )
    }

    @Test
    fun testShouldAttachScreenshot_Condition4_ScreenSignatureChanged_TargetAbsent_ReturnsTrue() {
        val labeledNode = ScreenNode(
            index = 0,
            text = "Home",
            contentDescription = "",
            className = "android.widget.Button",
            viewId = "home",
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            isCheckable = false,
            isEnabled = true,
            left = 0, top = 0, right = 100, bottom = 100, centerX = 50, centerY = 50
        )
        val nodes = listOf(labeledNode)

        // Screen signature changed + target absent from new nodes -> true
        val result = TaskExecutor.shouldAttachScreenshot(
            nodes = nodes,
            lastStepHadNoEffect = false,
            lastStepWasOpenApp = false,
            screenSignatureChanged = true,
            targetFoundInNodes = false
        )
        assertTrue("Condition 4 must trigger when signature changed and target is absent", result)
    }

    @Test
    fun testShouldAttachScreenshot_UnchangedScreen_ExactMatchPresent_ReturnsFalse() {
        val labeledNode = ScreenNode(
            index = 0,
            text = "Search",
            contentDescription = "Search button",
            className = "android.widget.Button",
            viewId = "search",
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            isCheckable = false,
            isEnabled = true,
            left = 0, top = 0, right = 100, bottom = 100, centerX = 50, centerY = 50
        )
        val nodes = listOf(labeledNode)

        // Unchanged screen + exact match present -> false
        val result = TaskExecutor.shouldAttachScreenshot(
            nodes = nodes,
            lastStepHadNoEffect = false,
            lastStepWasOpenApp = false,
            screenSignatureChanged = false,
            targetFoundInNodes = true
        )
        assertFalse("Unchanged screen with exact match present must return false", result)
    }

    @Test
    fun testShouldAttachScreenshot_ExistingConditionsStillPass() {
        val labeledNode = ScreenNode(
            index = 0,
            text = "Button",
            contentDescription = "",
            className = "android.widget.Button",
            viewId = "btn",
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            isCheckable = false,
            isEnabled = true,
            left = 0, top = 0, right = 100, bottom = 100, centerX = 50, centerY = 50
        )
        // (1) lastStepWasOpenApp
        assertTrue("Condition 1 open_app must return true", TaskExecutor.shouldAttachScreenshot(listOf(labeledNode), false, lastStepWasOpenApp = true))

        // (2) empty nodes
        assertTrue("Condition 2 empty nodes must return true", TaskExecutor.shouldAttachScreenshot(emptyList(), false, false))

        // (3) lastStepHadNoEffect with unlabeled clickables > 50%
        val unlabeledNode = ScreenNode(
            index = 1,
            text = "",
            contentDescription = "",
            className = "android.widget.ImageButton",
            viewId = "icon",
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            isCheckable = false,
            isEnabled = true,
            left = 0, top = 0, right = 100, bottom = 100, centerX = 50, centerY = 50
        )
        assertTrue(
            "Condition 3 failed step with majority unlabeled nodes must return true",
            TaskExecutor.shouldAttachScreenshot(listOf(unlabeledNode), lastStepHadNoEffect = true, lastStepWasOpenApp = false)
        )
    }

    @Test
    fun testReplaySkillFrom_CarriedDumpDiscardedWhenNextTargetAbsentOrAmbiguous() = runBlocking {
        var dumpCallCount = 0
        val fakePort = object : FakeAutomationPort() {
            override fun dumpScreen(): List<ScreenNode> {
                dumpCallCount++
                return screenNodes
            }
        }
        val memory = SkillMemoryService(InMemorySkillStore())
        val executor = TaskExecutor(
            automation = fakePort,
            skills = memory,
            stepDelayMs = 0L
        )

        // Step 1: click Home.
        // Step 2: click AbsentTarget (target absent from carried nodes).
        val skill = Skill(
            goal = "two-step test",
            steps = listOf(
                TaskStep(action = TaskStep.ACTION_CLICK_TEXT, params = mapOf("text" to "Home")),
                TaskStep(action = TaskStep.ACTION_CLICK_TEXT, params = mapOf("text" to "AbsentTarget"))
            )
        )

        // When AbsentTarget is absent from carried nodes, carried dump is discarded and a fresh dump is performed.
        val initialDumpCalls = dumpCallCount
        executor.replaySkillFrom(skill, startIndex = 0)
        assertTrue("Carried dump must be discarded and screen re-dumped when target is absent", dumpCallCount > initialDumpCalls)
    }

    // =========================================================================
    // waitForText tests
    // =========================================================================

    @Test
    fun testWaitForText_FoundOnFirstPoll() = runBlocking {
        val fakePort = FakeAutomationPort(
            screenNodes = listOf(
                ScreenNode(
                    index = 0, text = "Install", contentDescription = "",
                    className = "android.widget.Button", viewId = "install_btn",
                    isClickable = true, isEditable = false, isScrollable = false,
                    isCheckable = false, isEnabled = true,
                    left = 0, top = 0, right = 100, bottom = 50, centerX = 50, centerY = 25
                )
            )
        )
        val result = TaskExecutor.waitForText(automation = fakePort, text = "Install", timeoutMs = 5000L)
        assertTrue("Should find text on first poll", result.found)
        assertTrue("elapsedMs should be in range 0..2000, was ${result.elapsedMs}", result.elapsedMs in 0L..2000L)
        assertEquals(1, result.nodes.size)
        assertEquals("Install", result.nodes.first().text)
    }

    @Test
    fun testWaitForText_FoundOnLaterPoll() = runBlocking {
        val fakePort = FakeAutomationPort(
            screenNodes = listOf(
                ScreenNode(
                    index = 0, text = "Loading", contentDescription = "",
                    className = "android.widget.TextView", viewId = "loading",
                    isClickable = false, isEditable = false, isScrollable = false,
                    isCheckable = false, isEnabled = true,
                    left = 0, top = 0, right = 100, bottom = 50, centerX = 50, centerY = 25
                )
            )
        )
        val job = async {
            TaskExecutor.waitForText(automation = fakePort, text = "Installed", timeoutMs = 5000L, pollIntervalMs = 50L)
        }
        delay(100L)
        fakePort.screenNodes = listOf(
            ScreenNode(
                index = 0, text = "Installed", contentDescription = "",
                className = "android.widget.Button", viewId = "installed_btn",
                isClickable = true, isEditable = false, isScrollable = false,
                isCheckable = false, isEnabled = true,
                left = 0, top = 0, right = 100, bottom = 50, centerX = 50, centerY = 25
            )
        )
        val result = job.await()
        assertTrue("Should find text on later poll", result.found)
        assertTrue("elapsedMs should be >= 50ms, was ${result.elapsedMs}", result.elapsedMs >= 50L)
        assertEquals("Installed", result.nodes.first().text)
    }

    @Test
    fun testWaitForText_NotFound_Timeout() = runBlocking {
        val fakePort = FakeAutomationPort(
            screenNodes = listOf(
                ScreenNode(
                    index = 0, text = "Loading", contentDescription = "",
                    className = "android.widget.TextView", viewId = "loading",
                    isClickable = false, isEditable = false, isScrollable = false,
                    isCheckable = false, isEnabled = true,
                    left = 0, top = 0, right = 100, bottom = 50, centerX = 50, centerY = 25
                )
            )
        )
        val start = System.currentTimeMillis()
        val result = TaskExecutor.waitForText(automation = fakePort, text = "Never", timeoutMs = 150L)
        val elapsed = System.currentTimeMillis() - start
        assertFalse("Should not find text that never appears", result.found)
        assertTrue("elapsedMs should be >= 150ms, was ${result.elapsedMs}", result.elapsedMs >= 150L)
        assertTrue("elapsedMs should be < 1000ms, was ${result.elapsedMs}", result.elapsedMs < 1000L)
    }

    @Test
    fun testWaitForText_EmptyNodes_NotFound() = runBlocking {
        val fakePort = FakeAutomationPort(screenNodes = emptyList())
        val result = TaskExecutor.waitForText(automation = fakePort, text = "Anything", timeoutMs = 150L)
        assertFalse("Should not find text in empty node list", result.found)
        assertTrue("Nodes should be empty", result.nodes.isEmpty())
    }

    // --- A-M6: 4-arg clickText must try coordinates before text search ---

    @Test
    fun testClickTextFourArg_CoordinatesFirst_When2ArgReturnsFalse() {
        val fakePort = FakeAutomationPort(
            actionSuccess = true,
            requireNodeMatchForClickText = true,
            screenNodes = listOf(
                ScreenNode(
                    index = 0,
                    text = "Other",
                    contentDescription = "Other button",
                    className = "android.widget.Button",
                    viewId = "other_btn",
                    isClickable = true,
                    isEditable = false,
                    isScrollable = false,
                    isCheckable = false,
                    isEnabled = true,
                    left = 10,
                    top = 10,
                    right = 100,
                    bottom = 50,
                    centerX = 55,
                    centerY = 30
                )
            )
        )

        // 4-arg default method: coordinates first, 2-arg text search only as fallback
        val result = fakePort.clickText("Search", fakePort.screenNodes, 500, 800)

        assertTrue(result)
        assertTrue(fakePort.actionsPerformed.contains("clickAt:500.0,800.0"))
        assertFalse(fakePort.actionsPerformed.any { it.startsWith("clickText:") })
    }

    @Test
    fun testClickTextFourArg_FallsBackTo2Arg_WhenCoordinatesAreNull() {
        val fakePort = FakeAutomationPort(
            actionSuccess = true,
            screenNodes = listOf(
                ScreenNode(
                    index = 0,
                    text = "Search",
                    contentDescription = "Search button",
                    className = "android.widget.Button",
                    viewId = "search_btn",
                    isClickable = true,
                    isEditable = false,
                    isScrollable = false,
                    isCheckable = false,
                    isEnabled = true,
                    left = 10,
                    top = 10,
                    right = 100,
                    bottom = 50,
                    centerX = 55,
                    centerY = 30
                )
            )
        )

        val result = fakePort.clickText("Search", fakePort.screenNodes, null, null)

        assertTrue(result)
        assertTrue(fakePort.actionsPerformed.contains("clickText:Search"))
    }

    // =========================================================================
    // Round 2 Run 3 — A-M3, A-M5/A-M18, A-M7 verification tests
    // =========================================================================

    @Test
    fun testVerifyEffect_Scroll_SamePackage_RequiresRealChange() {
        // Same package, same signature, no frame change -> false (A-M3)
        assertFalse(
            TaskExecutor.verifyEffect(
                step = TaskStep(action = TaskStep.ACTION_SCROLL, params = mapOf("direction" to "down")),
                prePackage = "com.facebook.katana",
                preSignature = 12345,
                postPackage = "com.facebook.katana",
                postSignature = 12345,
                goal = "browse facebook feed",
                targetApp = "com.facebook.katana",
                frameChanged = null
            )
        )
        // Same package, same signature, frameChanged = true -> true
        assertTrue(
            TaskExecutor.verifyEffect(
                step = TaskStep(action = TaskStep.ACTION_SCROLL, params = mapOf("direction" to "down")),
                prePackage = "com.facebook.katana",
                preSignature = 12345,
                postPackage = "com.facebook.katana",
                postSignature = 12345,
                goal = "browse facebook feed",
                targetApp = "com.facebook.katana",
                frameChanged = true
            )
        )
        // Same package, changed signature -> true
        assertTrue(
            TaskExecutor.verifyEffect(
                step = TaskStep(action = TaskStep.ACTION_SCROLL, params = mapOf("direction" to "down")),
                prePackage = "com.facebook.katana",
                preSignature = 12345,
                postPackage = "com.facebook.katana",
                postSignature = 99999,
                goal = "browse facebook feed",
                targetApp = "com.facebook.katana",
                frameChanged = null
            )
        )
    }

    @Test
    fun testVerifyEffect_Click_UnknownTarget_WrongPackageIsFailure() {
        val clickStep = TaskStep(action = TaskStep.ACTION_CLICK_TEXT, params = mapOf("text" to "Send"))

        // prePackage is a known app, postPackage is a different app, target null, goal not web -> false (A-M5/A-M18)
        assertFalse(
            TaskExecutor.verifyEffect(
                step = clickStep,
                prePackage = "com.facebook.katana",
                preSignature = 100,
                postPackage = "com.android.chrome",
                postSignature = 200,
                goal = "send message on facebook",
                targetApp = null
            )
        )

        // Same packages -> true
        assertTrue(
            TaskExecutor.verifyEffect(
                step = clickStep,
                prePackage = "com.facebook.katana",
                preSignature = 100,
                postPackage = "com.facebook.katana",
                postSignature = 200,
                goal = "send message on facebook",
                targetApp = null
            )
        )

        // prePackage is a launcher package -> true (allowed)
        assertTrue(
            TaskExecutor.verifyEffect(
                step = clickStep,
                prePackage = "com.android.launcher3",
                preSignature = 100,
                postPackage = "com.android.chrome",
                postSignature = 200,
                goal = "send message on facebook",
                targetApp = null
            )
        )
    }

    @Test
    fun testStaleTap_ClickAt_ReturnsFailure() = runBlocking {
        val fakePort = FakeAutomationPort(
            serviceEnabled = true,
            screenNodes = listOf(
                ScreenNode(
                    index = 0,
                    text = "Home",
                    contentDescription = "Home button",
                    className = "android.widget.Button",
                    viewId = "home_btn",
                    isClickable = true,
                    isEditable = false,
                    isScrollable = false,
                    isCheckable = false,
                    isEnabled = true,
                    left = 10,
                    top = 10,
                    right = 100,
                    bottom = 50,
                    centerX = 55,
                    centerY = 30
                )
            )
        )
        val skills = SkillMemoryService(InMemorySkillStore())
        val step = TaskStep(action = TaskStep.ACTION_CLICK_AT, params = mapOf("x" to 500, "y" to 1500))
        val skill = Skill(goal = "test stale tap", steps = listOf(step))
        val executor = TaskExecutor(automation = fakePort, skills = skills, stepDelayMs = 0)

        val result = executor.replaySkillDirect(skill)
        assertEquals(TaskStatus.FAILED, result.status)
        assertTrue("Result message must mention stale tap failure: ${result.message}",
            result.message.contains("Stale recorded coordinate") || result.message.contains("tap skipped"))
    }
}
