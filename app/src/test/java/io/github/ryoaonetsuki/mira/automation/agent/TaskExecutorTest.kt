package io.github.ryoaonetsuki.mira.automation.agent

import io.github.ryoaonetsuki.mira.automation.MiraAutomationBridge
import kotlinx.coroutines.async
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
    private class FakeAutomationPort(
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
            mutateScreenState("clickedAt:$x,$y")
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
                mutateScreenState("opened:$launchedPkg")
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
    }

    // --- Fake in-memory SkillStore for pure JVM testing ---
    private class InMemorySkillStore(var data: String? = null) : SkillStore {
        override fun loadRaw(): String? = data
        override fun saveRaw(json: String) {
            data = json
        }
    }

    // =========================================================================
    // 1. AutomationSafety Tests
    // =========================================================================

    @Test
    fun testAutomationSafety_BlockedWords() {
        val payStep = TaskStep(action = "click_text", params = mapOf("text" to "pay"))
        assertNotNull(AutomationSafety.isBlocked(payStep, null))

        val purchaseStep = TaskStep(action = "click_text", params = mapOf("text" to "confirm purchase"))
        assertNotNull(AutomationSafety.isBlocked(purchaseStep, null))

        val deleteStep = TaskStep(action = "click_text", params = mapOf("text" to "delete account"))
        assertNotNull(AutomationSafety.isBlocked(deleteStep, null))

        // Bangla blocked words
        val paymentBangla = TaskStep(action = "click_text", params = mapOf("text" to "পেমেন্ট সম্পন্ন করুন"))
        assertNotNull(AutomationSafety.isBlocked(paymentBangla, null))

        val buyBangla = TaskStep(action = "click_text", params = mapOf("text" to "এখনই কিনুন"))
        assertNotNull(AutomationSafety.isBlocked(buyBangla, null))

        val deleteBangla1 = TaskStep(action = "click_text", params = mapOf("text" to "মুছুন"))
        assertNotNull(AutomationSafety.isBlocked(deleteBangla1, null))

        val deleteBangla2 = TaskStep(action = "click_text", params = mapOf("text" to "ডিলিট করুন"))
        assertNotNull(AutomationSafety.isBlocked(deleteBangla2, null))

        // "play song on youtube" must NOT be blocked
        val safePlayStep = TaskStep(action = "click_text", params = mapOf("text" to "play song on youtube"))
        assertNull(AutomationSafety.isBlocked(safePlayStep, null))
    }

    @Test
    fun testAutomationSafety_BankingPackage() {
        val clickStep = TaskStep(action = "click_text", params = mapOf("text" to "Send Money"))
        val bkashBlocked = AutomationSafety.isBlocked(clickStep, "com.bkash")
        assertNotNull(bkashBlocked)

        val backStep = TaskStep(action = "press_back")
        val bkashBackAllowed = AutomationSafety.isBlocked(backStep, "com.bkash")
        assertNull(bkashBackAllowed)

        val homeStep = TaskStep(action = "press_home")
        val nagadHomeAllowed = AutomationSafety.isBlocked(homeStep, "com.nagad")
        assertNull(nagadHomeAllowed)

        val waitStep = TaskStep(action = "wait", params = mapOf("ms" to 1000))
        val rocketWaitAllowed = AutomationSafety.isBlocked(waitStep, "com.rocket")
        assertNull(rocketWaitAllowed)

        val doneStep = TaskStep(action = "done")
        val walletDoneAllowed = AutomationSafety.isBlocked(doneStep, "com.google.android.apps.walletnfcrel")
        assertNull(walletDoneAllowed)

        // Unknown package allows normal clicks
        val normalAllowed = AutomationSafety.isBlocked(clickStep, "com.example.newsapp")
        assertNull(normalAllowed)
    }

    @Test
    fun testAutomationSafety_ConfirmAndSensitiveActions() {
        val confirmPay = TaskStep(action = "click_text", params = mapOf("text" to "Confirm and Pay"))
        assertNotNull(AutomationSafety.isBlocked(confirmPay, null))

        val confirmPurchase = TaskStep(action = "click_text", params = mapOf("text" to "Confirm purchase now"))
        assertNotNull(AutomationSafety.isBlocked(confirmPurchase, null))

        val confirmDelete = TaskStep(action = "click_text", params = mapOf("text" to "Confirm delete"))
        assertNotNull(AutomationSafety.isBlocked(confirmDelete, null))
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
        skills.saveOrUpdate(
            goal = "play lofi on youtube",
            steps = listOf(
                TaskStep(action = "open_app", params = mapOf("app" to "youtube")),
                TaskStep(action = "click_text", params = mapOf("text" to "Search"))
            )
        )

        var thinkCalled = false
        val executor = TaskExecutor(
            automation = fakePort,
            skills = skills,
            think = { _, _, _ ->
                thinkCalled = true
                """{"action": "done", "is_complete": true}"""
            },
            stepDelayMs = 0
        )

        val result = executor.executeGoal("play lofi on youtube")

        assertEquals(TaskStatus.SUCCESS, result.status)
        assertFalse("Think lambda must NOT be called on skill replay match", thinkCalled)
        assertTrue(fakePort.actionsPerformed.contains("openApp:youtube"))
        assertTrue(fakePort.actionsPerformed.contains("clickText:Search"))
        assertEquals("Task completed via memorized skill", result.message)
    }

    @Test
    fun testTaskExecutor_AiLoop_SuccessAndAutoSavesSkill() = runBlocking {
        val fakePort = FakeAutomationPort(serviceEnabled = true)
        val skills = SkillMemoryService(InMemorySkillStore())

        var thinkTurn = 0
        val executor = TaskExecutor(
            automation = fakePort,
            skills = skills,
            think = { _, _, _ ->
                thinkTurn++
                if (thinkTurn == 1) {
                    """{"action": "open_app", "params": {"app": "youtube"}, "reasoning": "Open YouTube app"}"""
                } else {
                    """{"action": "done", "params": {}, "reasoning": "Goal completed", "is_complete": true}"""
                }
            },
            stepDelayMs = 0
        )

        val result = executor.executeGoal("open youtube and play lofi")

        assertEquals(TaskStatus.SUCCESS, result.status)
        assertEquals(2, result.stepsUsed)
        assertTrue(fakePort.actionsPerformed.contains("openApp:youtube"))

        // Assert skill was auto-saved
        val savedSkill = skills.findMatch("open youtube and play lofi")
        assertNotNull("Skill must be auto-saved after successful AI loop", savedSkill)
        assertEquals(1, savedSkill?.steps?.size)
        assertEquals("open_app", savedSkill?.steps?.first()?.action)
    }

    @Test
    fun testTaskExecutor_CancelMidRun() = runBlocking {
        val fakePort = FakeAutomationPort(serviceEnabled = true)
        val skills = SkillMemoryService(InMemorySkillStore())

        lateinit var executor: TaskExecutor
        executor = TaskExecutor(
            automation = fakePort,
            skills = skills,
            think = { _, _, _ ->
                executor.cancel()
                """{"action": "wait", "params": {"ms": 100}}"""
            },
            stepDelayMs = 0
        )

        val result = executor.executeGoal("do something then cancel")

        assertEquals(TaskStatus.CANCELLED, result.status)
        assertTrue(result.message.contains("cancelled"))
        assertFalse(executor.isRunning)
    }

    @Test
    fun testTaskExecutor_MaxStepsExceeded_ReturnsFailed() = runBlocking {
        val fakePort = FakeAutomationPort(serviceEnabled = true)
        val skills = SkillMemoryService(InMemorySkillStore())

        val executor = TaskExecutor(
            automation = fakePort,
            skills = skills,
            think = { _, _, _ ->
                """{"action": "wait", "params": {"ms": 100}}"""
            },
            maxSteps = 3,
            stepDelayMs = 0
        )

        val result = executor.executeGoal("infinite waiting task")

        assertEquals(TaskStatus.FAILED, result.status)
        assertEquals(3, result.stepsUsed)
        assertTrue(result.message.contains("maximum of 3 steps"))
    }

    @Test
    fun testTaskExecutor_SafetyBlock_ReturnsFailedWithMessage() = runBlocking {
        val fakePort = FakeAutomationPort(serviceEnabled = true)
        val skills = SkillMemoryService(InMemorySkillStore())

        val executor = TaskExecutor(
            automation = fakePort,
            skills = skills,
            think = { _, _, _ ->
                """{"action": "click_text", "params": {"text": "Pay $99.99 Now"}}"""
            },
            stepDelayMs = 0
        )

        val result = executor.executeGoal("buy item online")

        assertEquals(TaskStatus.FAILED, result.status)
        assertTrue(result.message.contains("Blocked for safety"))
        assertTrue(result.message.contains("ask me first"))
        assertFalse(fakePort.actionsPerformed.any { it.contains("clickText") })
    }

    @Test
    fun testTaskExecutor_AccessibilityOff_ReturnsFailed() = runBlocking {
        val fakePort = FakeAutomationPort(serviceEnabled = false)
        val skills = SkillMemoryService(InMemorySkillStore())

        val executor = TaskExecutor(
            automation = fakePort,
            skills = skills,
            think = { _, _, _ -> """{"action": "done"}""" },
            stepDelayMs = 0
        )

        val result = executor.executeGoal("launch settings")

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
            think = null,
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
    fun testTaskExecutor_ExecuteGoal_StaleOrEmptySkillMatch_FallsThroughToAiLoop() = runBlocking {
        val fakePort = FakeAutomationPort(serviceEnabled = true)
        // Stored skill with 0 steps (representing legacy or empty macro recorded on phone)
        val store = InMemorySkillStore("""[{"id":"empty-skill","goal":"play lofi song on youtube","steps":[]}]""")
        val skills = SkillMemoryService(store)

        var thinkTurns = 0
        val executor = TaskExecutor(
            automation = fakePort,
            skills = skills,
            think = { _, _, _ ->
                thinkTurns++
                if (thinkTurns == 1) {
                    """{"action": "open_app", "params": {"app": "youtube"}}"""
                } else {
                    """{"action": "done", "is_complete": true}"""
                }
            },
            stepDelayMs = 0
        )

        val result = executor.executeGoal("play lofi song on youtube")
        assertEquals("F-08: Stale/empty skill must fall through to AI loop and succeed", TaskStatus.SUCCESS, result.status)
        assertTrue(thinkTurns >= 2)
        assertTrue(fakePort.actionsPerformed.contains("openApp:youtube"))
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
            think = null,
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
            think = null,
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
            think = null,
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
    fun testF04_PrematureDoneRejected_AndSingleStepIsCompleteExecutesFirst() = runBlocking {
        // 1. ACTION_DONE on step 1 when executedSteps is empty must NOT return SUCCESS
        val fakePort1 = FakeAutomationPort(serviceEnabled = true)
        val skills1 = SkillMemoryService(InMemorySkillStore())
        val executor1 = TaskExecutor(
            automation = fakePort1,
            skills = skills1,
            think = { _, _, _ -> """{"action": "done", "is_complete": true}""" },
            maxSteps = 2,
            stepDelayMs = 0
        )
        val res1 = executor1.executeGoal("open youtube and play lofi")
        assertEquals(TaskStatus.FAILED, res1.status)
        assertTrue(fakePort1.actionsPerformed.isEmpty())

        // 2. Non-done action with is_complete=true executes and verifies effect FIRST before honouring isComplete
        val fakePort2 = FakeAutomationPort(serviceEnabled = true, simulateEffect = true)
        val skills2 = SkillMemoryService(InMemorySkillStore())
        val executor2 = TaskExecutor(
            automation = fakePort2,
            skills = skills2,
            think = { _, _, _ ->
                """{"action": "open_app", "params": {"app": "youtube"}, "is_complete": true}"""
            },
            maxSteps = 2,
            stepDelayMs = 0
        )
        val res2 = executor2.executeGoal("open youtube and play lofi")
        assertEquals(TaskStatus.SUCCESS, res2.status)
        assertEquals(listOf("openApp:youtube"), fakePort2.actionsPerformed)
    }

    @Test
    fun testF07_NoEffect_RecordedAndPreventsFalseSuccess() = runBlocking {
        // Action returns true from dispatch, but screen/package never changes (simulateEffect = false)
        val fakePort = FakeAutomationPort(serviceEnabled = true, simulateEffect = false)
        val skills = SkillMemoryService(InMemorySkillStore())
        val seenHistories = mutableListOf<List<Pair<String, String>>>()

        var turn = 0
        val executor = TaskExecutor(
            automation = fakePort,
            skills = skills,
            think = { _, _, history ->
                seenHistories.add(history.toList())
                turn++
                if (turn == 1) {
                    """{"action": "click_text", "params": {"text": "Home"}, "is_complete": true}"""
                } else {
                    """{"action": "done", "is_complete": true}"""
                }
            },
            maxSteps = 2,
            stepDelayMs = 0
        )

        val result = executor.executeGoal("tap home and show me the feed")
        assertEquals("Must fail when no action had a verified screen effect", TaskStatus.FAILED, result.status)
        assertTrue(
            "History must record [NO_EFFECT] so LLM knows the action had no effect",
            seenHistories.last().any { it.second.contains("[NO_EFFECT]") }
        )
    }

    @Test
    fun testF09_EmptyScreenOnStep1_CallsThinkInsteadOfStalling() = runBlocking {
        val fakePort = FakeAutomationPort(
            serviceEnabled = true,
            screenNodes = emptyList(),
            simulateEffect = true
        )
        val skills = SkillMemoryService(InMemorySkillStore())
        val seenScreenDescriptions = mutableListOf<String>()

        var turn = 0
        val executor = TaskExecutor(
            automation = fakePort,
            skills = skills,
            think = { _, screenDesc, _ ->
                seenScreenDescriptions.add(screenDesc)
                turn++
                if (turn == 1) {
                    """{"action": "open_app", "params": {"app": "youtube"}, "is_complete": true}"""
                } else {
                    """{"action": "done", "is_complete": true}"""
                }
            },
            maxSteps = 2,
            stepDelayMs = 0
        )

        val result = executor.executeGoal("ইউটিউব খুলে গান চালাও")
        assertEquals(TaskStatus.SUCCESS, result.status)
        assertTrue(
            "Step 1 empty screen must pass launch hint to think",
            seenScreenDescriptions.first().contains("Screen appears empty (use open_app to launch the target app)")
        )
        assertTrue(fakePort.actionsPerformed.contains("openApp:youtube"))
    }

    @Test
    fun testScreenSeeing_ShouldAttachScreenshot_AndVisionRouting() = runBlocking {
        val fakePort = FakeAutomationPort(serviceEnabled = true)
        val skills = SkillMemoryService(InMemorySkillStore())
        var visionCalled = false

        val executor = TaskExecutor(
            automation = fakePort,
            skills = skills,
            think = { _, _, _ -> """{"action": "done", "is_complete": true}""" },
            thinkWithVision = { _, _, _, bytes ->
                visionCalled = bytes.isNotEmpty()
                """{"action": "open_app", "params": {"app": "youtube"}, "is_complete": true}"""
            },
            stepDelayMs = 0
        )

        // Turn F(d): a screenshot is paid ONLY when the previous step had no
        // effect AND the text description is insufficient.
        // 1. Empty nodes + previous step had effect -> false (step-1 hint text suffices)
        assertFalse(executor.shouldAttachScreenshot(emptyList(), lastStepHadNoEffect = false))
        // 2. Empty nodes + previous step had NO effect -> true
        assertTrue(executor.shouldAttachScreenshot(emptyList(), lastStepHadNoEffect = true))

        // 3. >50% clickable nodes unlabeled + previous step had effect -> false
        val unlabeledNodes = listOf(
            ScreenNode(0, "", "", "android.view.View", "", isClickable = true, isEditable = false, isScrollable = false, isCheckable = false, isEnabled = true, 0, 0, 10, 10, 5, 5),
            ScreenNode(1, "", "", "android.view.View", "", isClickable = true, isEditable = false, isScrollable = false, isCheckable = false, isEnabled = true, 0, 0, 10, 10, 5, 5),
            ScreenNode(2, "Search", "", "android.widget.Button", "", isClickable = true, isEditable = false, isScrollable = false, isCheckable = false, isEnabled = true, 0, 0, 10, 10, 5, 5)
        )
        assertFalse(executor.shouldAttachScreenshot(unlabeledNodes, lastStepHadNoEffect = false))
        // 4. >50% clickable nodes unlabeled + previous step had NO effect -> true
        assertTrue(executor.shouldAttachScreenshot(unlabeledNodes, lastStepHadNoEffect = true))

        // 5. Labeled nodes + previous step had effect -> false
        assertFalse(executor.shouldAttachScreenshot(fakePort.screenNodes, lastStepHadNoEffect = false))
        // 6. Labeled nodes + previous step had NO effect -> false (text is sufficient)
        assertFalse(executor.shouldAttachScreenshot(fakePort.screenNodes, lastStepHadNoEffect = true))

        // 7. End-to-end: unlabeled screen starts TEXT-ONLY; only after a
        // no-effect step does the next think route through thinkWithVision.
        val visionPort = FakeAutomationPort(
            serviceEnabled = true,
            noEffectClickTexts = setOf("Home"),
            screenNodes = unlabeledNodes
        )
        val visionSkills = SkillMemoryService(InMemorySkillStore())
        var visionThinkTurns = 0
        val visionExecutor = TaskExecutor(
            automation = visionPort,
            skills = visionSkills,
            think = { _, _, _ ->
                visionThinkTurns++
                """{"action": "click_text", "params": {"text": "Home"}}"""
            },
            thinkWithVision = { _, _, _, bytes ->
                visionCalled = bytes.isNotEmpty()
                """{"action": "open_app", "params": {"app": "youtube"}, "is_complete": true}"""
            },
            stepDelayMs = 0
        )
        val result = visionExecutor.executeGoal("open youtube and play lofi")
        assertEquals(TaskStatus.SUCCESS, result.status)
        assertEquals("First AI step on an unlabeled screen must use text-only think", 1, visionThinkTurns)
        assertTrue("thinkWithVision must be invoked on the step after a no-effect step on an unlabeled screen", visionCalled)
    }

    // =========================================================================
    // 7. Round 25 Fix Turn C — C1, C2, C3, C4 Tests
    // =========================================================================

    @Test
    fun testC2_ReplaySkillDirect_ResumesWithAiForFailedStepOnly_AndMarksOutcomeCorrectly() = runBlocking {
        // Turn F(b): replay fails on the stale click; the AI fixes ONLY that
        // step (open_app) and replay RESUMES with the remaining steps —
        // the goal is never restarted from scratch via the AI loop.
        val fakePort = FakeAutomationPort(serviceEnabled = true, actionSuccess = false)
        val skills = SkillMemoryService(InMemorySkillStore())
        val savedSkill = skills.saveOrUpdate(
            goal = "open youtube",
            steps = listOf(TaskStep(action = "click_text", params = mapOf("text" to "StaleVideoTitle")))
        )

        var thinkTurn = 0
        val executor = TaskExecutor(
            automation = fakePort,
            skills = skills,
            think = { _, _, _ ->
                thinkTurn++
                fakePort.actionSuccess = true
                if (thinkTurn == 1) {
                    """{"action": "open_app", "params": {"app": "youtube"}}"""
                } else {
                    """{"action": "done", "is_complete": true}"""
                }
            },
            stepDelayMs = 0
        )

        val result = executor.replaySkillDirect(savedSkill)
        assertEquals(TaskStatus.SUCCESS, result.status)
        assertTrue("Message must state it completed via AI fallback", result.message.contains("AI fallback"))
        assertEquals("Resume must fix the failed step with exactly one AI turn (no full AI-loop restart)", 1, thinkTurn)

        // Skill must still exist, consecutiveFailures == 0, successCount == 1, and steps refreshed
        val updatedSkill = skills.findMatch("open youtube")
        assertNotNull(updatedSkill)
        assertEquals(0, updatedSkill?.consecutiveFailures)
        assertEquals(1, updatedSkill?.successCount)
        assertEquals(TaskStep.ACTION_OPEN_APP, updatedSkill?.steps?.first()?.action)
    }

    @Test
    fun testC2_ReplaySkillDirect_BothReplayAndAiFallbackFail_MarksFailureWithoutDeletingOnFirstFailure() = runBlocking {
        val fakePort = FakeAutomationPort(serviceEnabled = true, actionSuccess = false)
        val skills = SkillMemoryService(InMemorySkillStore())
        val savedSkill = skills.saveOrUpdate(
            goal = "open youtube",
            steps = listOf(TaskStep(action = "click_text", params = mapOf("text" to "StaleVideoTitle")))
        )

        var thinkTurn = 0
        val executor = TaskExecutor(
            automation = fakePort,
            skills = skills,
            think = { _, _, _ ->
                thinkTurn++
                """{"action": "click_text", "params": {"text": "StillFailing"}}"""
            },
            maxSteps = 2,
            stepDelayMs = 0
        )

        val result = executor.replaySkillDirect(savedSkill)
        assertEquals(TaskStatus.FAILED, result.status)
        assertTrue(result.message.contains("AI fallback"))
        assertEquals(
            "Resume must spend exactly ${TaskExecutor.AI_RESUME_MAX_ATTEMPTS} AI turns on the failed step, then stop",
            TaskExecutor.AI_RESUME_MAX_ATTEMPTS,
            thinkTurn
        )

        // C1 + C2: Skill is NOT deleted after 1 failure; consecutiveFailures is incremented to 1
        val retained = skills.findMatch("open youtube")
        assertNotNull("Skill must not be deleted on a single failed replay+fallback", retained)
        assertEquals(1, retained?.consecutiveFailures)
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

        val executor = TaskExecutor(
            automation = fakePort,
            skills = skills,
            think = { _, _, _ ->
                kotlinx.coroutines.delay(500L)
                """{"action": "done", "is_complete": true}"""
            },
            stepDelayMs = 0,
            taskTimeoutMs = 40L
        )

        val result = executor.executeGoal("slow hanging task")
        assertEquals(TaskStatus.FAILED, result.status)
        assertTrue(
            "Timeout message must name the phase and step instead of a generic timeout",
            result.message.contains("Timed out while waiting for the AI reply") &&
                result.message.contains("step 1")
        )
        assertFalse("Executor _isRunning must be reset after timeout", executor.isRunning)
    }

    @Test
    fun testC4_BridgeStaleGuardAndConcurrentCallProtection() = runBlocking {
        var fakeNow = 1_000L
        var activeRuns = 0
        var maxConcurrentRuns = 0

        val bridge = MiraAutomationBridge(
            context = null,
            taskTimeoutMs = 1_000L,
            clockMs = { fakeNow },
            executorFactory = {
                val fakePort = FakeAutomationPort(serviceEnabled = true)
                val skills = SkillMemoryService(InMemorySkillStore())
                TaskExecutor(
                    automation = fakePort,
                    skills = skills,
                    think = { _, _, _ ->
                        activeRuns++
                        if (activeRuns > maxConcurrentRuns) maxConcurrentRuns = activeRuns
                        try {
                            kotlinx.coroutines.delay(80L)
                        } finally {
                            activeRuns--
                        }
                        """{"action": "open_app", "params": {"app": "youtube"}, "is_complete": true}"""
                    },
                    stepDelayMs = 0,
                    taskTimeoutMs = 5_000L
                )
            }
        )

        // 1. Concurrent executeGoal calls -> only one runs, the other is rejected with runningGoal info
        val d1 = async { bridge.executeGoal("play lofi on youtube") }
        kotlinx.coroutines.delay(15L)
        assertTrue(bridge.isRunning)
        assertEquals("play lofi on youtube", bridge.runningGoal)
        val d2 = async { bridge.executeGoal("open spotify") }

        val r1 = d1.await()
        val r2 = d2.await()
        assertEquals(1, maxConcurrentRuns)
        assertEquals(TaskStatus.SUCCESS, r1.status)
        assertEquals(TaskStatus.FAILED, r2.status)
        assertTrue(r2.message.contains("Another task is already running"))
        assertTrue("Rejection message must include the running goal", r2.message.contains("play lofi on youtube"))
        assertFalse(bridge.isRunning)

        // 2. Stale-guard: when a task exceeds taskTimeoutMs without completing, isRunning cancels it and frees the slot
        val hungJob = launch {
            bridge.executeGoal("stuck task")
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
    fun testE1_ThinkCallRespectsInjectedDeadline_FailsFastWithDiagnosis() = runBlocking {
        val port = FakeAutomationPort(serviceEnabled = true)
        val skills = SkillMemoryService(InMemorySkillStore())

        val executor = TaskExecutor(
            automation = port,
            skills = skills,
            think = { _, _, _ ->
                kotlinx.coroutines.delay(500L) // Exceeds 60ms think timeout
                """{"action": "done", "params": {}, "is_complete": true}"""
            },
            stepDelayMs = 0L,
            thinkTimeoutMs = 60L,
            taskTimeoutMs = 10_000L
        )

        val result = executor.executeGoal("test think deadline")
        assertEquals(TaskStatus.FAILED, result.status)
        assertTrue(
            "Expected message to name AI reply timeout but was: ${result.message}",
            result.message.contains("Timed out while waiting for the AI reply") &&
                result.message.contains("think call exceeded")
        )
        assertEquals(1, result.stepsUsed)
        assertNotNull(executor.lastDiagnosis)
        assertTrue(executor.lastDiagnosis!!.contains("Timed out while waiting for the AI reply"))
    }

    @Test
    fun testE2_TaskTimeout_FailsFastWithPhaseSpecificDiagnosis() = runBlocking {
        val port = FakeAutomationPort(serviceEnabled = true)
        val skills = SkillMemoryService(InMemorySkillStore())

        val executor = TaskExecutor(
            automation = port,
            skills = skills,
            think = { _, _, _ ->
                kotlinx.coroutines.delay(2000L) // Will be aborted by taskTimeoutMs = 120ms
                """{"action": "done", "params": {}, "is_complete": true}"""
            },
            stepDelayMs = 0L,
            thinkTimeoutMs = 10_000L,
            taskTimeoutMs = 120L
        )

        val result = executor.executeGoal("test task timeout diagnosis")
        assertEquals(TaskStatus.FAILED, result.status)
        assertTrue(
            "Expected message to name thinking phase or AI reply timeout but was: ${result.message}",
            result.message.contains("thinking") || result.message.contains("AI reply")
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

        val executor = TaskExecutor(
            automation = port,
            skills = skills,
            think = { _, _, _ ->
                kotlinx.coroutines.delay(1200L) // Runs longer than 1s warning threshold
                """{"action": "open_app", "params": {"app": "youtube"}, "is_complete": true}"""
            },
            stepDelayMs = 0L,
            thinkTimeoutMs = 5000L,
            taskTimeoutMs = 10_000L,
            phaseWarningThresholdSec = 1L // 1s threshold for unit test
        )
        executor.onProgress = { _, desc ->
            progressMessages.add(desc)
        }

        executor.executeGoal("test progress warning")
        val hasStillWarning = progressMessages.any { it.contains("Still thinking…") }
        assertTrue("Expected progress callback to emit 'Still thinking…' warning", hasStillWarning)
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
    fun testF_Resume_FailedStepFixedByAi_ContinuesRemainingSteps() = runBlocking {
        // Replay fails at step 1 (open_app dispatch fails); the AI fixes
        // ONLY step 1, then replay RESUMES with steps 2-3 — the goal is
        // never restarted from scratch via the AI loop.
        val fakePort = FakeAutomationPort(serviceEnabled = true, actionSuccess = false)
        val skills = SkillMemoryService(InMemorySkillStore())
        val skill = Skill(
            goal = "yt",
            steps = listOf(
                TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("app" to "youtube")),
                TaskStep(action = TaskStep.ACTION_CLICK_TEXT, params = mapOf("text" to "Library")),
                TaskStep(action = TaskStep.ACTION_CLICK_TEXT, params = mapOf("text" to "Search"))
            )
        )

        var thinkTurn = 0
        val executor = TaskExecutor(
            automation = fakePort,
            skills = skills,
            think = { _, _, _ ->
                thinkTurn++
                fakePort.actionSuccess = true
                """{"action": "open_app", "params": {"app": "youtube"}}"""
            },
            stepDelayMs = 0
        )

        val result = executor.replaySkillDirect(skill)
        assertEquals(TaskStatus.SUCCESS, result.status)
        assertTrue("Message must state it completed via AI fallback", result.message.contains("AI fallback"))
        assertEquals("Resume must fix the failed step with exactly one AI turn", 1, thinkTurn)
        assertEquals(
            "AI-fixed step 1, then the remaining recorded steps replay in order",
            listOf("openApp:youtube", "clickText:Library", "clickText:Search"),
            fakePort.actionsPerformed
        )
    }

    @Test
    fun testF_Resume_HungThink_FailsAtDeadline() = runBlocking {
        // Turn F(g): a hung think call on the replay-fallback path must fail
        // at the think deadline (~45s in production, 1s here), not hang
        // until the 120s task timeout.
        val fakePort = FakeAutomationPort(serviceEnabled = true, actionSuccess = false)
        val skills = SkillMemoryService(InMemorySkillStore())
        val skill = Skill(
            goal = "yt",
            steps = listOf(
                TaskStep(action = TaskStep.ACTION_CLICK_TEXT, params = mapOf("text" to "Search"))
            )
        )
        val executor = TaskExecutor(
            automation = fakePort,
            skills = skills,
            think = { _, _, _ ->
                kotlinx.coroutines.delay(5_000L) // Far beyond the 100ms think deadline
                """{"action": "done", "is_complete": true}"""
            },
            stepDelayMs = 0L,
            thinkTimeoutMs = 1_000L,
            taskTimeoutMs = 10_000L
        )

        val startMs = System.currentTimeMillis()
        val result = executor.replaySkillDirect(skill)
        val elapsedMs = System.currentTimeMillis() - startMs

        assertEquals(TaskStatus.FAILED, result.status)
        assertTrue(
            "Failure must name the AI-reply timeout but was: ${result.message}",
            result.message.contains("Timed out while waiting for the AI reply")
        )
        assertTrue("Hung think must fail at the deadline, not run the full task timeout (was ${elapsedMs}ms)", elapsedMs < 3_000L)
        assertNotNull(executor.lastDiagnosis)
        assertTrue(executor.lastDiagnosis!!.contains("Timed out while waiting for the AI reply"))
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

    @Test
    fun testF_RunAiLoop_ReusesVerifyDump_NextObserve() = runBlocking {
        // Turn F(e): the AI loop used to dump twice per step (observe +
        // verify); the verification dump is now the next step's observe.
        val fakePort = FakeAutomationPort(serviceEnabled = true)
        val skills = SkillMemoryService(InMemorySkillStore())
        var thinkTurn = 0
        val executor = TaskExecutor(
            automation = fakePort,
            skills = skills,
            think = { _, _, _ ->
                thinkTurn++
                if (thinkTurn == 1) {
                    """{"action": "open_app", "params": {"app": "youtube"}}"""
                } else {
                    """{"action": "done", "is_complete": true}"""
                }
            },
            stepDelayMs = 0
        )

        val result = executor.executeGoal("open youtube and play lofi")
        assertEquals(TaskStatus.SUCCESS, result.status)
        assertEquals(2, result.stepsUsed)
        assertEquals("2-step AI loop must read the screen 2x (observe + verify), not 3x", 2, fakePort.dumpCount)
    }

    // =========================================================================
    // Round 25 Fix Turn F — Direct fast path (Voice Access model), plan-once,
    // learning loop, voice-path deadline
    // =========================================================================

    private fun directExecutor(
        fakePort: FakeAutomationPort,
        skills: SkillMemoryService,
        thinkCalls: MutableList<String>,
        planCalls: MutableList<String> = mutableListOf()
    ): TaskExecutor = TaskExecutor(
        automation = fakePort,
        skills = skills,
        think = { _, _, _ ->
            thinkCalls.add("think")
            """{"action": "done", "is_complete": true}"""
        },
        plan = { _, _ ->
            planCalls.add("plan")
            """{"steps": []}"""
        },
        stepDelayMs = 0
    )

    @Test
    fun testDirect_OpenFacebook_En_ZeroThink() = runBlocking {
        val fakePort = FakeAutomationPort(serviceEnabled = true)
        val skills = SkillMemoryService(InMemorySkillStore())
        val thinkCalls = mutableListOf<String>()
        val executor = directExecutor(fakePort, skills, thinkCalls)

        val result = executor.executeGoal("open Facebook")

        assertEquals(TaskStatus.SUCCESS, result.status)
        assertEquals(1, result.stepsUsed)
        assertTrue("Direct path must make zero think calls", thinkCalls.isEmpty())
        assertTrue(fakePort.actionsPerformed.contains("openApp:com.facebook.katana"))
    }

    @Test
    fun testDirect_OpenFacebook_BanglaNatural_ZeroThink() = runBlocking {
        val fakePort = FakeAutomationPort(serviceEnabled = true)
        val skills = SkillMemoryService(InMemorySkillStore())
        val thinkCalls = mutableListOf<String>()
        val executor = directExecutor(fakePort, skills, thinkCalls)

        // Natural Bangla with particle ("টা"), filler ("তো") and word order.
        val result = executor.executeGoal("ফেসবুকটা ওপেন করো তো")

        assertEquals(TaskStatus.SUCCESS, result.status)
        assertEquals(1, result.stepsUsed)
        assertTrue("Direct path must make zero think calls", thinkCalls.isEmpty())
        assertTrue(fakePort.actionsPerformed.contains("openApp:com.facebook.katana"))
    }

    @Test
    fun testDirect_Home_Bangla_ZeroThink() = runBlocking {
        val fakePort = FakeAutomationPort(serviceEnabled = true)
        val skills = SkillMemoryService(InMemorySkillStore())
        val thinkCalls = mutableListOf<String>()
        val executor = directExecutor(fakePort, skills, thinkCalls)

        val result = executor.executeGoal("হোমপেজে আসো")

        assertEquals(TaskStatus.SUCCESS, result.status)
        assertTrue("Direct path must make zero think calls", thinkCalls.isEmpty())
        assertTrue(fakePort.actionsPerformed.contains("pressHome"))
    }

    @Test
    fun testDirect_ScrollDownABit_Bangla_ZeroThink() = runBlocking {
        val fakePort = FakeAutomationPort(serviceEnabled = true)
        val skills = SkillMemoryService(InMemorySkillStore())
        val thinkCalls = mutableListOf<String>()
        val executor = directExecutor(fakePort, skills, thinkCalls)

        val result = executor.executeGoal("একটু নিচে স্ক্রল করো তো")

        assertEquals(TaskStatus.SUCCESS, result.status)
        assertTrue("Direct path must make zero think calls", thinkCalls.isEmpty())
        assertTrue(
            "A 'bit' scroll must use the small fraction (0.15)",
            fakePort.actionsPerformed.contains("scroll:down:0.15")
        )
    }

    @Test
    fun testDirect_GoUpABit_Bangla_ZeroThink() = runBlocking {
        val fakePort = FakeAutomationPort(serviceEnabled = true)
        val skills = SkillMemoryService(InMemorySkillStore())
        val thinkCalls = mutableListOf<String>()
        val executor = directExecutor(fakePort, skills, thinkCalls)

        val result = executor.executeGoal("একটু উপরের দিকে যাও")

        assertEquals(TaskStatus.SUCCESS, result.status)
        assertTrue("Direct path must make zero think calls", thinkCalls.isEmpty())
        assertTrue(fakePort.actionsPerformed.contains("scroll:up:0.15"))
    }

    @Test
    fun testDirect_TapLabel_ZeroThink() = runBlocking {
        val settingsNode = ScreenNode(
            index = 0, text = "Settings", contentDescription = "Settings",
            className = "android.widget.TextView", viewId = "settings",
            isClickable = true, isEditable = false, isScrollable = false,
            isCheckable = false, isEnabled = true,
            left = 10, top = 10, right = 100, bottom = 50, centerX = 55, centerY = 30
        )
        val fakePort = FakeAutomationPort(
            serviceEnabled = true,
            screenNodes = listOf(settingsNode),
            requireNodeMatchForClickText = true
        )
        val skills = SkillMemoryService(InMemorySkillStore())
        val thinkCalls = mutableListOf<String>()
        val executor = directExecutor(fakePort, skills, thinkCalls)

        val result = executor.executeGoal("tap Settings")

        assertEquals(TaskStatus.SUCCESS, result.status)
        assertTrue("Direct path must make zero think calls", thinkCalls.isEmpty())
        assertTrue(fakePort.actionsPerformed.contains("clickText:Settings"))
    }

    @Test
    fun testDirect_Search_ZeroThink() = runBlocking {
        val fakePort = FakeAutomationPort(serviceEnabled = true)
        val skills = SkillMemoryService(InMemorySkillStore())
        val thinkCalls = mutableListOf<String>()
        val executor = directExecutor(fakePort, skills, thinkCalls)

        val result = executor.executeGoal("search lofi on youtube")

        assertEquals(TaskStatus.SUCCESS, result.status)
        assertTrue("Direct path must make zero think calls", thinkCalls.isEmpty())
        assertTrue(
            fakePort.actionsPerformed.any { it.startsWith("openUrl:https://www.youtube.com/results?search_query=lofi") }
        )
    }

    @Test
    fun testDirect_Dial_ZeroThink() = runBlocking {
        val fakePort = FakeAutomationPort(serviceEnabled = true)
        val skills = SkillMemoryService(InMemorySkillStore())
        val thinkCalls = mutableListOf<String>()
        val executor = directExecutor(fakePort, skills, thinkCalls)

        val result = executor.executeGoal("call 01712345678")

        assertEquals(TaskStatus.SUCCESS, result.status)
        assertTrue("Direct path must make zero think calls", thinkCalls.isEmpty())
        assertTrue(fakePort.actionsPerformed.contains("dialNumber:01712345678"))
    }

    @Test
    fun testDirect_NonDirectGoal_FallsThroughToAiLoop() = runBlocking {
        val fakePort = FakeAutomationPort(serviceEnabled = true)
        val skills = SkillMemoryService(InMemorySkillStore())
        var thinkTurn = 0
        val executor = TaskExecutor(
            automation = fakePort,
            skills = skills,
            think = { _, _, _ ->
                thinkTurn++
                """{"action": "open_app", "params": {"app": "whatsapp"}, "is_complete": true}"""
            },
            stepDelayMs = 0
        )

        // "message mom on whatsapp" needs more than opening the app — the
        // direct path must refuse it so the AI loop can do the real work.
        val result = executor.executeGoal("message mom on whatsapp")

        assertEquals(TaskStatus.SUCCESS, result.status)
        assertTrue("AI loop must run for non-direct goals", thinkTurn >= 1)
        assertTrue(fakePort.actionsPerformed.contains("openApp:whatsapp"))
    }

    @Test
    fun testDirect_OpenOnlyGoal_AiLoopReturnsImmediatelyAfterOpenApp() = runBlocking {
        val fakePort = FakeAutomationPort(serviceEnabled = true)
        val skills = SkillMemoryService(InMemorySkillStore())
        var thinkTurn = 0
        val executor = TaskExecutor(
            automation = fakePort,
            skills = skills,
            think = { _, _, _ ->
                thinkTurn++
                // No is_complete flag: the model keeps "thinking" instead of
                // saying done. The loop must still stop after the open.
                """{"action": "open_app", "params": {"app": "mybankapp"}}"""
            },
            stepDelayMs = 0
        )

        // Unknown app (not in the local tables) so the direct path cannot
        // take it — the AI loop must still reach DONE in one step.
        val result = executor.executeGoal("open mybankapp")

        assertEquals(TaskStatus.SUCCESS, result.status)
        assertEquals("AI loop must stop after the open_app succeeds", 1, result.stepsUsed)
        assertEquals("Exactly one think call — no second round on the opened app", 1, thinkTurn)
    }

    @Test
    fun testPlanOnce_SavesSkill_AndSecondInvocationIsInstant() = runBlocking {
        val fakePort = FakeAutomationPort(serviceEnabled = true)
        val skills = SkillMemoryService(InMemorySkillStore())
        var planCalls = 0
        var thinkTurns = 0
        val executor = TaskExecutor(
            automation = fakePort,
            skills = skills,
            think = { _, _, _ ->
                thinkTurns++
                """{"action": "done", "is_complete": true}"""
            },
            plan = { _, _ ->
                planCalls++
                """{"steps": [{"action": "open_app", "params": {"app": "youtube"}}, {"action": "click_text", "params": {"text": "Search"}}]}"""
            },
            stepDelayMs = 0
        )

        // First time: one planning call, zero per-step think, skill saved.
        val first = executor.executeGoal("open youtube and play lofi")
        assertEquals(TaskStatus.SUCCESS, first.status)
        assertEquals("Exactly one planning call on first invocation", 1, planCalls)
        assertEquals("Zero per-step think calls on the plan-once path", 0, thinkTurns)
        val saved = skills.findMatch("open youtube and play lofi")
        assertNotNull("Planned list must be saved as a Skill immediately", saved)
        assertEquals(2, saved?.steps?.size)

        // Second time: the saved list replays directly — no plan call, no think.
        val second = executor.executeGoal("open youtube and play lofi")
        assertEquals(TaskStatus.SUCCESS, second.status)
        assertEquals("No second planning call — the saved list replays directly", 1, planCalls)
        assertEquals("No think calls on the replay path either", 0, thinkTurns)
        assertEquals(
            listOf("openApp:youtube", "clickText:Search", "openApp:youtube", "clickText:Search"),
            fakePort.actionsPerformed
        )
    }

    @Test
    fun testPlanOnce_UnparsablePlan_FallsBackToAiLoop() = runBlocking {
        val fakePort = FakeAutomationPort(serviceEnabled = true)
        val skills = SkillMemoryService(InMemorySkillStore())
        var thinkTurn = 0
        val executor = TaskExecutor(
            automation = fakePort,
            skills = skills,
            think = { _, _, _ ->
                thinkTurn++
                if (thinkTurn == 1) {
                    """{"action": "open_app", "params": {"app": "youtube"}}"""
                } else {
                    """{"action": "done", "is_complete": true}"""
                }
            },
            plan = { _, _ -> "this is not json at all" },
            stepDelayMs = 0
        )

        val result = executor.executeGoal("open youtube and play lofi")

        assertEquals(TaskStatus.SUCCESS, result.status)
        assertTrue("AI loop must run when the plan is unusable", thinkTurn >= 1)
    }

    @Test
    fun testPlanOnce_DonePlan_ReturnsSuccessImmediately() = runBlocking {
        val fakePort = FakeAutomationPort(serviceEnabled = true)
        val skills = SkillMemoryService(InMemorySkillStore())
        var thinkTurn = 0
        val executor = TaskExecutor(
            automation = fakePort,
            skills = skills,
            think = { _, _, _ ->
                thinkTurn++
                """{"action": "done", "is_complete": true}"""
            },
            plan = { _, _ -> """{"done": true}""" },
            stepDelayMs = 0
        )

        val result = executor.executeGoal("open youtube and play lofi")

        assertEquals(TaskStatus.SUCCESS, result.status)
        assertEquals(0, result.stepsUsed)
        assertEquals("No think calls when the plan says already done", 0, thinkTurn)
        assertTrue(fakePort.actionsPerformed.isEmpty())
    }

    @Test
    fun testParseAiPlan_Formats() {
        val executor = TaskExecutor(
            automation = FakeAutomationPort(),
            skills = SkillMemoryService(InMemorySkillStore()),
            stepDelayMs = 0
        )

        val list = executor.parseAiPlan(
            """{"steps": [{"action": "open_app", "params": {"app": "youtube"}}, {"action": "click_text", "params": {"text": "Search"}}]}"""
        )
        assertNotNull(list)
        assertFalse(list!!.done)
        assertEquals(2, list.steps.size)
        assertEquals("open_app", list.steps[0].action)
        assertEquals("click_text", list.steps[1].action)

        val done = executor.parseAiPlan("""{"done": true}""")
        assertNotNull(done)
        assertTrue(done!!.done)

        val bare = executor.parseAiPlan("""[{"action": "back"}]""")
        assertNotNull(bare)
        assertEquals("press_back", bare!!.steps.first().action)

        val fenced = executor.parseAiPlan(
            "```json\n{\"steps\": [{\"action\": \"open_app\", \"params\": {\"app\": \"youtube\"}}]}\n```"
        )
        assertNotNull(fenced)
        assertEquals(1, fenced!!.steps.size)

        assertNull(executor.parseAiPlan("this is not json at all"))
        assertNull(executor.parseAiPlan("""{"steps": []}"""))
        assertNull(executor.parseAiPlan(""))
    }

    @Test
    fun testBridge_HungThink_VoicePathFailsFast_AndSlotClears() = runBlocking {
        val bridge = MiraAutomationBridge(
            context = null,
            taskTimeoutMs = 5_000L,
            executorFactory = {
                val fakePort = FakeAutomationPort(serviceEnabled = true)
                val skills = SkillMemoryService(InMemorySkillStore())
                TaskExecutor(
                    automation = fakePort,
                    skills = skills,
                    think = { _, _, _ ->
                        kotlinx.coroutines.delay(30_000L) // hangs far past every deadline
                        """{"action": "done", "is_complete": true}"""
                    },
                    stepDelayMs = 0,
                    taskTimeoutMs = 500L,
                    thinkTimeoutMs = 200L
                )
            }
        )

        // This is the voice path: MicService calls bridge.executeGoal. A hung
        // think must fail at the ~200ms think deadline (not the 120s task
        // timeout), and the executor slot must clear so the next command can run.
        val start = System.currentTimeMillis()
        val result = bridge.executeGoal("test hung voice goal")
        val elapsed = System.currentTimeMillis() - start

        assertEquals(TaskStatus.FAILED, result.status)
        assertTrue(
            "Hung think on the voice path must fail fast (took ${elapsed}ms)",
            elapsed < 5_000L
        )
        assertFalse("Executor slot must clear after the timeout", bridge.isRunning)
    }
}
