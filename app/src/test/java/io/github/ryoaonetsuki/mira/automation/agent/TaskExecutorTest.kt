package io.github.ryoaonetsuki.mira.automation.agent

import io.github.ryoaonetsuki.mira.automation.MiraAutomationBridge
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
        var currentPkg: String? = null,
        val actionsPerformed: MutableList<String> = mutableListOf(),
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
        override fun isServiceEnabled(): Boolean = serviceEnabled
        override fun dumpScreen(): List<ScreenNode> = screenNodes
        override fun formatNodes(nodes: List<ScreenNode>): String = "nodes count: ${nodes.size}"
        override fun getCompressedScreenDescription(nodes: List<ScreenNode>, goal: String): String =
            "Screen description for $goal"

        override fun clickText(text: String, nodes: List<ScreenNode>): Boolean {
            actionsPerformed.add("clickText:$text")
            return true
        }

        override fun clickAt(x: Float, y: Float): Boolean {
            actionsPerformed.add("clickAt:$x,$y")
            return true
        }

        override fun typeText(text: String): Boolean {
            actionsPerformed.add("typeText:$text")
            return true
        }

        override fun pressEnter(): Boolean {
            actionsPerformed.add("pressEnter")
            return true
        }

        override fun scroll(direction: String): Boolean {
            actionsPerformed.add("scroll:$direction")
            return true
        }

        override fun swipe(
            startX: Float,
            startY: Float,
            endX: Float,
            endY: Float,
            durationMs: Long
        ): Boolean {
            actionsPerformed.add("swipe:$startX,$startY->$endX,$endY")
            return true
        }

        override fun pressBack(): Boolean {
            actionsPerformed.add("pressBack")
            return true
        }

        override fun pressHome(): Boolean {
            actionsPerformed.add("pressHome")
            return true
        }

        override fun openApp(query: String): String? {
            actionsPerformed.add("openApp:$query")
            return query
        }

        override fun getCurrentPackage(): String? = currentPkg
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
        assertTrue(MiraAutomationBridge.isAutomationIntent("open youtube and play lofi"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("message mom on whatsapp"))
        assertFalse(MiraAutomationBridge.isAutomationIntent("what is the weather today"))
        assertFalse(MiraAutomationBridge.isAutomationIntent("tell me a joke"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("খুলো ইউটিউব"))
    }

    @Test
    fun testIsAutomationIntent_AdditionalCoverage() {
        assertTrue(MiraAutomationBridge.isAutomationIntent("play Shape of You on Spotify"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("launch camera"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("start settings app"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("হোয়াটসঅ্যাপ এ মেসেজ পাঠাও"))
        assertFalse(MiraAutomationBridge.isAutomationIntent("how far is the moon"))
        assertFalse(MiraAutomationBridge.isAutomationIntent(""))
    }

    // =========================================================================
    // 3. TaskExecutor Flow Tests
    // =========================================================================

    @Test
    fun testTaskExecutor_SkillReplayPath_NeverCallsThink() = runBlocking {
        val fakePort = FakeAutomationPort(serviceEnabled = true)
        val skills = SkillMemoryService(InMemorySkillStore())

        // Pre-save a skill
        skills.saveOrUpdate(
            goal = "open camera",
            steps = listOf(TaskStep(action = "open_app", params = mapOf("app" to "camera")))
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

        val result = executor.executeGoal("open camera")

        assertEquals(TaskStatus.SUCCESS, result.status)
        assertFalse("Think lambda must NOT be called on skill replay match", thinkCalled)
        assertTrue(fakePort.actionsPerformed.contains("openApp:camera"))
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

        val result = executor.executeGoal("open youtube")

        assertEquals(TaskStatus.SUCCESS, result.status)
        assertEquals(2, result.stepsUsed)
        assertTrue(fakePort.actionsPerformed.contains("openApp:youtube"))

        // Assert skill was auto-saved
        val savedSkill = skills.findMatch("open youtube")
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
}
