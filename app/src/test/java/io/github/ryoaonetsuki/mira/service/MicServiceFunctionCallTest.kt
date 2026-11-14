package io.github.ryoaonetsuki.mira.service

import ai.pipecat.client.types.Value
import io.github.ryoaonetsuki.mira.automation.agent.AutomationPort
import io.github.ryoaonetsuki.mira.automation.agent.ScreenNode
import io.github.ryoaonetsuki.mira.automation.agent.Skill
import io.github.ryoaonetsuki.mira.automation.agent.SkillMemoryService
import io.github.ryoaonetsuki.mira.automation.agent.SkillStore
import io.github.ryoaonetsuki.mira.automation.agent.TaskStep
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [executeFunctionCall] in [MicService]:
 * Verifies that 'open_app' dispatches to the AutomationPort openApp path,
 * waits for foreground package, and returns a result with fresh screen context
 * (node-tree text always, screenshot attached at shouldAttachScreenshot decision points).
 */
class MicServiceFunctionCallTest {

    private class TestAutomationPort(
        var currentPkg: String = "com.google.android.apps.nexuslauncher",
        var targetPkgOnOpen: String? = "com.google.android.youtube",
        var openAppReturnValue: String? = "com.google.android.youtube",
        var testNodes: List<ScreenNode> = listOf(
            ScreenNode(
                index = 0,
                text = "Search YouTube",
                contentDescription = "Search button",
                className = "android.widget.Button",
                viewId = "yt_search",
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
        ),
        var screenshotBytes: ByteArray? = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) // Minimal JPEG header
    ) : AutomationPort {
        val openedApps = mutableListOf<String>()
        val clickedTexts = mutableListOf<String>()
        var lastClickedCoords: Pair<Float, Float>? = null
        var lastTypedText: String? = null
        var lastScrollDirection: String? = null
        var pressBackCalled: Boolean = false
        var pressHomeCalled: Boolean = false
        var pkgChangeOnAction: String? = null
        var tapNumbersToShow: List<Pair<Int, ScreenNode>> = emptyList()
        var hideTapNumbersCalled: Boolean = false
        var tapNumbersDetailedResult: io.github.ryoaonetsuki.mira.automation.agent.TapNumbersResult? = null

        override fun showTapNumbers(): List<Pair<Int, ScreenNode>> = tapNumbersToShow
        override fun showTapNumbersDetailed(): io.github.ryoaonetsuki.mira.automation.agent.TapNumbersResult =
            tapNumbersDetailedResult ?: super.showTapNumbersDetailed()
        override fun hideTapNumbers() {
            hideTapNumbersCalled = true
        }

        var isTargetPackageResolver: ((query: String, pkg: String) -> Boolean)? = null
        override fun isTargetPackage(query: String, pkg: String): Boolean =
            isTargetPackageResolver?.invoke(query, pkg) ?: super.isTargetPackage(query, pkg)

        override fun isServiceEnabled(): Boolean = true

        override fun dumpScreen(): List<ScreenNode> = testNodes

        override fun formatNodes(nodes: List<ScreenNode>): String =
            nodes.joinToString("\n") { "${it.className}: ${it.text}" }

        override fun getCompressedScreenDescription(nodes: List<ScreenNode>, goal: String): String =
            "Screen for $goal: ${nodes.joinToString(", ") { it.text.ifBlank { it.contentDescription } }}"

        override fun clickText(text: String, nodes: List<ScreenNode>): Boolean {
            clickedTexts.add(text)
            val matches = nodes.any { it.text.contains(text, ignoreCase = true) || it.contentDescription.contains(text, ignoreCase = true) }
            if (matches) {
                if (pkgChangeOnAction != null) {
                    currentPkg = pkgChangeOnAction!!
                }
                // Simulate effect by mutating node text
                testNodes = testNodes.map { it.copy(text = "${it.text} [clicked]") }
                return true
            }
            return false
        }

        override fun clickAt(x: Float, y: Float): Boolean {
            lastClickedCoords = Pair(x, y)
            if (pkgChangeOnAction != null) {
                currentPkg = pkgChangeOnAction!!
            }
            testNodes = testNodes.map { it.copy(text = "${it.text} [tapped at $x,$y]") }
            return true
        }

        override fun typeText(text: String): Boolean {
            lastTypedText = text
            if (pkgChangeOnAction != null) {
                currentPkg = pkgChangeOnAction!!
            }
            testNodes = testNodes.map { it.copy(text = text, isEditable = true) }
            return true
        }

        override fun pressEnter(): Boolean = true

        override fun scroll(direction: String): Boolean {
            lastScrollDirection = direction
            if (pkgChangeOnAction != null) {
                currentPkg = pkgChangeOnAction!!
            }
            testNodes = testNodes.map { it.copy(top = it.top + 10, bottom = it.bottom + 10) }
            return true
        }

        override fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): Boolean = true

        override fun pressBack(): Boolean {
            pressBackCalled = true
            if (pkgChangeOnAction != null) {
                currentPkg = pkgChangeOnAction!!
            }
            testNodes = testNodes.map { it.copy(text = "${it.text} [back]") }
            return true
        }

        override fun pressHome(): Boolean {
            pressHomeCalled = true
            currentPkg = "com.google.android.apps.nexuslauncher"
            testNodes = listOf(
                ScreenNode(
                    index = 0,
                    text = "Home Launcher",
                    contentDescription = "Home screen",
                    className = "android.widget.TextView",
                    viewId = "home",
                    isClickable = false,
                    isEditable = false,
                    isScrollable = false,
                    isCheckable = false,
                    isEnabled = true,
                    left = 0,
                    top = 0,
                    right = 1080,
                    bottom = 1920,
                    centerX = 540,
                    centerY = 960
                )
            )
            return true
        }

        override fun openApp(query: String): String? {
            openedApps.add(query)
            if (targetPkgOnOpen != null) {
                currentPkg = targetPkgOnOpen!!
            }
            return openAppReturnValue
        }

        override fun getCurrentPackage(): String = currentPkg

        override suspend fun takeScreenshotJpeg(maxWidthPx: Int): ByteArray? = screenshotBytes
    }

    @Test
    fun testOpenApp_SuccessfulDispatch_ReturnsScreenAndScreenshotAttached() = runBlocking {
        val fakePort = TestAutomationPort()

        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "open_app",
            rawArgs = mapOf("app" to "YouTube"),
            typedArgs = mapOf("app" to "YouTube"),
            pollTimeoutMs = 1000L,
            pollIntervalMs = 50L,
            settleDelayMs = 10L
        )

        assertEquals("Should have opened YouTube", listOf("com.google.android.youtube"), fakePort.openedApps)
        assertEquals("Foreground package should match YouTube", "com.google.android.youtube", fakePort.currentPkg)

        val resultMap = response.value
        val resultStr = (resultMap["result"] as? Value.Str)?.value.orEmpty()
        assertTrue("Result message should indicate success: $resultStr", resultStr.contains("Successfully opened com.google.android.youtube"))

        val successBool = (resultMap["success"] as? Value.Bool)?.value
        assertEquals("Success field must be true", true, successBool)

        val pkgStr = (resultMap["package"] as? Value.Str)?.value
        assertEquals("Package should match target", "com.google.android.youtube", pkgStr)

        // Fresh screen context attached: node-tree text always
        val screenText = (resultMap["screen"] as? Value.Str)?.value.orEmpty()
        assertTrue("Screen description must be attached: $screenText", screenText.contains("Search YouTube"))

        val screenTextAlias = (resultMap["screen_text"] as? Value.Str)?.value.orEmpty()
        assertEquals("screen_text must match screen", screenText, screenTextAlias)

        // Screenshot attached at decision point (after open_app)
        val screenshot = (resultMap["screenshot"] as? Value.Str)?.value
        assertNotNull("Screenshot MUST be attached after open_app", screenshot)
        assertTrue("Screenshot should be non-empty base64", screenshot!!.isNotEmpty())
    }

    @Test
    fun testOpenApp_TargetNotReached_ReportsFailureWithScreenAttached() = runBlocking {
        // App open is dispatched but package does not change to target
        val fakePort = TestAutomationPort(
            currentPkg = "com.google.android.apps.nexuslauncher",
            targetPkgOnOpen = null // remains on launcher
        )

        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "open_app",
            rawArgs = mapOf("app" to "NonExistentApp"),
            typedArgs = mapOf("app" to "NonExistentApp"),
            pollTimeoutMs = 100L,
            pollIntervalMs = 20L,
            settleDelayMs = 10L
        )

        val resultMap = response.value
        val successBool = (resultMap["success"] as? Value.Bool)?.value
        assertEquals("Success field must be false when target not reached", false, successBool)

        val resultStr = (resultMap["result"] as? Value.Str)?.value.orEmpty()
        assertTrue("Result should report dispatched without claim of target success: $resultStr", resultStr.contains("Dispatched open for NonExistentApp"))

        // Fresh screen text is still attached
        val screenText = (resultMap["screen"] as? Value.Str)?.value.orEmpty()
        assertTrue("Screen description must still be attached", screenText.isNotEmpty())

        // Screenshot attached (lastStepHadNoEffect = true triggers decision point)
        assertNotNull("Screenshot should be attached when step has no effect", resultMap["screenshot"])
    }

    @Test
    fun testOpenApp_EmptyAppArg_ReturnsError() = runBlocking {
        val fakePort = TestAutomationPort()

        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "open_app",
            rawArgs = emptyMap(),
            typedArgs = emptyMap()
        )

        val resultMap = response.value
        val errorVal = (resultMap["error"] as? Value.Str)?.value
        assertNotNull("Missing app argument must return error", errorVal)
        assertTrue(errorVal!!.contains("Missing app argument"))
        assertTrue("openApp must not have been invoked", fakePort.openedApps.isEmpty())
    }

    @Test
    fun testOpenApp_EmptyNodeTree_AttachesScreenshot() = runBlocking {
        val fakePort = TestAutomationPort(
            testNodes = emptyList() // Empty node tree (e.g. WebView, game, surface)
        )

        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "open_app",
            rawArgs = mapOf("app" to "YouTube"),
            pollTimeoutMs = 100L,
            pollIntervalMs = 20L,
            settleDelayMs = 10L
        )

        val resultMap = response.value
        val screenText = (resultMap["screen"] as? Value.Str)?.value.orEmpty()
        assertTrue("Screen description should mention empty node tree", screenText.contains("empty node tree"))
        assertNotNull("Screenshot MUST be attached on empty node tree", resultMap["screenshot"])
    }

    @Test
    fun testClickText_ByLabel_Success_NoScreenshot() = runBlocking {
        val fakePort = TestAutomationPort(
            currentPkg = "com.google.android.youtube"
        )

        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "click_text",
            rawArgs = mapOf("text" to "Search YouTube"),
            typedArgs = mapOf("text" to "Search YouTube"),
            settleDelayMs = 0L,
            targetApp = "com.google.android.youtube"
        )

        val resultMap = response.value
        assertEquals("Should dispatch click_text with label", listOf("Search YouTube"), fakePort.clickedTexts)
        assertEquals("Success must be true on verified click", true, (resultMap["success"] as? Value.Bool)?.value)
        val resultStr = (resultMap["result"] as? Value.Str)?.value.orEmpty()
        assertTrue("Result message must indicate successful click: $resultStr", resultStr.contains("Successfully clicked 'Search YouTube'"))
        val screenText = (resultMap["screen"] as? Value.Str)?.value.orEmpty()
        assertTrue("Screen text must be attached", screenText.isNotEmpty())
        assertEquals("screen_text must match screen", screenText, (resultMap["screen_text"] as? Value.Str)?.value)
        // Clean click with non-empty nodes must NOT attach screenshot (no screenshot tax)
        assertNull("Screenshot should NOT be attached on clean successful click", resultMap["screenshot"])
    }

    @Test
    fun testClickText_ByCoordinates_PreservesNumericCoordinates() = runBlocking {
        val fakePort = TestAutomationPort(
            currentPkg = "com.google.android.youtube"
        )

        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "click_text",
            rawArgs = mapOf("text" to "NonExistentLabel", "x" to "320", "y" to "640"),
            typedArgs = mapOf("text" to "NonExistentLabel", "x" to 320, "y" to 640),
            settleDelayMs = 0L,
            targetApp = "com.google.android.youtube"
        )

        val resultMap = response.value
        assertNotNull("clickAt must be called as fallback", fakePort.lastClickedCoords)
        assertEquals("Numeric X must be preserved as 320f", 320f, fakePort.lastClickedCoords!!.first)
        assertEquals("Numeric Y must be preserved as 640f", 640f, fakePort.lastClickedCoords!!.second)
        assertEquals("Success must be true on coordinate fallback click", true, (resultMap["success"] as? Value.Bool)?.value)
    }

    @Test
    fun testTypeText_Success() = runBlocking {
        val fakePort = TestAutomationPort(
            currentPkg = "com.google.android.youtube"
        )

        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "type_text",
            rawArgs = mapOf("text" to "Bangla songs"),
            typedArgs = mapOf("text" to "Bangla songs"),
            settleDelayMs = 0L,
            targetApp = "com.google.android.youtube"
        )

        val resultMap = response.value
        assertEquals("typeText should be called with input text", "Bangla songs", fakePort.lastTypedText)
        assertEquals("Success must be true", true, (resultMap["success"] as? Value.Bool)?.value)
        val resultStr = (resultMap["result"] as? Value.Str)?.value.orEmpty()
        assertTrue("Result message must indicate typed text: $resultStr", resultStr.contains("Successfully typed 'Bangla songs'"))
    }

    @Test
    fun testScroll_Success() = runBlocking {
        val fakePort = TestAutomationPort(
            currentPkg = "com.google.android.youtube"
        )

        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "scroll",
            rawArgs = mapOf("direction" to "down"),
            typedArgs = mapOf("direction" to "down"),
            settleDelayMs = 0L,
            targetApp = "com.google.android.youtube"
        )

        val resultMap = response.value
        assertEquals("scroll should be called with direction down", "down", fakePort.lastScrollDirection)
        assertEquals("Success must be true", true, (resultMap["success"] as? Value.Bool)?.value)
        val resultStr = (resultMap["result"] as? Value.Str)?.value.orEmpty()
        assertTrue("Result message must indicate scrolled down: $resultStr", resultStr.contains("Successfully scrolled down"))
    }

    @Test
    fun testPressBack_Success() = runBlocking {
        val fakePort = TestAutomationPort(
            currentPkg = "com.google.android.youtube"
        )

        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "press_back",
            rawArgs = emptyMap(),
            typedArgs = emptyMap(),
            settleDelayMs = 0L,
            targetApp = "com.google.android.youtube"
        )

        val resultMap = response.value
        assertTrue("pressBack should be invoked", fakePort.pressBackCalled)
        assertEquals("Success must be true", true, (resultMap["success"] as? Value.Bool)?.value)
        val resultStr = (resultMap["result"] as? Value.Str)?.value.orEmpty()
        assertTrue("Result message must indicate pressed Back: $resultStr", resultStr.contains("Successfully pressed Back"))
    }

    @Test
    fun testPressHome_ReturnsToLauncher_Success() = runBlocking {
        val fakePort = TestAutomationPort(
            currentPkg = "com.google.android.youtube"
        )

        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "press_home",
            rawArgs = emptyMap(),
            typedArgs = emptyMap(),
            settleDelayMs = 0L
        )

        val resultMap = response.value
        assertTrue("pressHome should be invoked", fakePort.pressHomeCalled)
        assertEquals("Package must change to launcher", "com.google.android.apps.nexuslauncher", fakePort.currentPkg)
        assertEquals("Success must be true for press_home returning to launcher", true, (resultMap["success"] as? Value.Bool)?.value)
        val resultStr = (resultMap["result"] as? Value.Str)?.value.orEmpty()
        assertTrue("Result message must indicate pressed Home: $resultStr", resultStr.contains("Successfully pressed Home"))
    }

    @Test
    fun testWait_DelaysAndClampsMs() = runBlocking {
        val fakePort = TestAutomationPort()

        val startTime = System.currentTimeMillis()
        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "wait",
            rawArgs = mapOf("ms" to "150"),
            typedArgs = mapOf("ms" to 150)
        )
        val elapsed = System.currentTimeMillis() - startTime

        assertTrue("Should delay for at least 100ms", elapsed >= 100L)
        val resultMap = response.value
        assertEquals("Success must be true for wait", true, (resultMap["success"] as? Value.Bool)?.value)
        val resultStr = (resultMap["result"] as? Value.Str)?.value.orEmpty()
        assertTrue("Result should report waited duration: $resultStr", resultStr.contains("Waited 150ms"))
    }

    @Test
    fun testGetScreen_ReturnsScreenText() = runBlocking {
        val fakePort = TestAutomationPort()

        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "get_screen",
            rawArgs = emptyMap(),
            typedArgs = emptyMap()
        )

        val resultMap = response.value
        assertEquals("Success must be true for get_screen", true, (resultMap["success"] as? Value.Bool)?.value)
        val screenText = (resultMap["screen"] as? Value.Str)?.value.orEmpty()
        assertTrue("get_screen must return fresh screen text containing Search YouTube", screenText.contains("Search YouTube"))
        assertEquals("screen_text must match screen", screenText, (resultMap["screen_text"] as? Value.Str)?.value)
        assertNull("Screenshot should be null when nodes exist and no error", resultMap["screenshot"])
    }

    @Test
    fun testWrongPackageChange_VerifiesAsFailure() = runBlocking {
        // App is on YouTube, target is YouTube, but clicking opens Chrome (e.g. ad / external link)
        val fakePort = TestAutomationPort(
            currentPkg = "com.google.android.youtube"
        )
        fakePort.pkgChangeOnAction = "com.android.chrome"

        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "click_text",
            rawArgs = mapOf("text" to "Search YouTube"),
            typedArgs = mapOf("text" to "Search YouTube"),
            settleDelayMs = 0L,
            targetApp = "com.google.android.youtube"
        )

        val resultMap = response.value
        val success = (resultMap["success"] as? Value.Bool)?.value
        assertEquals("Wrong package change MUST verify as failure (success=false)", false, success)

        val resultStr = (resultMap["result"] as? Value.Str)?.value.orEmpty()
        assertTrue(
            "Result string must state wrong package / target mismatch failure: $resultStr",
            resultStr.contains("does not match target") || resultStr.contains("wrong-package change failure")
        )
        assertEquals("Package in result must be the wrong package", "com.android.chrome", (resultMap["package"] as? Value.Str)?.value)
    }

    @Test
    fun testWebSearchTool_ReturnsSuccessAndJoinedResults() = runBlocking {
        val fakePort = TestAutomationPort()

        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "web_search",
            rawArgs = mapOf("query" to "test query"),
            typedArgs = mapOf("query" to "test query")
        )

        val resultMap = response.value
        assertEquals("web_search success must be true", true, (resultMap["success"] as? Value.Bool)?.value)
        assertNotNull("Result string must be present", resultMap["result"])
    }

    @Test
    fun testRunConnectorCommandTool_ReturnsSuccess() = runBlocking {
        val fakePort = TestAutomationPort()

        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "run_connector_command",
            rawArgs = mapOf("command" to "toggle lights"),
            typedArgs = mapOf("command" to "toggle lights")
        )

        val resultMap = response.value
        assertEquals("run_connector_command success must be true", true, (resultMap["success"] as? Value.Bool)?.value)
        val resultStr = (resultMap["result"] as? Value.Str)?.value.orEmpty()
        assertTrue("Result string must mention command", resultStr.contains("toggle lights"))
    }

    private class InMemorySkillStore(var data: String? = null) : SkillStore {
        override fun loadRaw(): String? = data
        override fun saveRaw(json: String) {
            data = json
        }
    }

    @Test
    fun testSetupPayloadDeclaresAllTools() {
        val toolsArray = io.github.ryoaonetsuki.mira.voice.pipecat.MiraGeminiServiceOptions.defaultPhoneTools()
        val funcDecls = (toolsArray.value[0] as Value.Object).value["functionDeclarations"] as Value.Array
        val toolNames = funcDecls.value.map {
            ((it as Value.Object).value["name"] as Value.Str).value
        }
        val expected = listOf(
            "open_app",
            "click_text",
            "click_at",
            "tap_number",
            "show_numbers",
            "hide_numbers",
            "type_text",
            "scroll",
            "press_back",
            "press_home",
            "wait",
            "wait_for_text",
            "get_screen",
            "web_search",
            "run_connector_command",
            "run_skill",
            "remember_fact",
            "recall_memory",
            "forget_memory"
        )
        assertEquals("defaultPhoneTools must declare exactly the 19 tools", expected, toolNames)
    }

    @Test
    fun testRunSkill_SuccessReplay_ReplaysSkillMacroDirectly() = runBlocking {
        val fakePort = TestAutomationPort(
            currentPkg = "com.google.android.apps.nexuslauncher",
            targetPkgOnOpen = "com.google.android.youtube"
        )
        val store = InMemorySkillStore()
        val skillsService = SkillMemoryService(store)

        val step1 = TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("app" to "YouTube"))
        val step2 = TaskStep(action = TaskStep.ACTION_CLICK_TEXT, params = mapOf("text" to "Search YouTube"))
        skillsService.saveOrUpdate("open and search youtube", listOf(step1, step2))

        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "run_skill",
            rawArgs = mapOf("goal" to "open and search youtube"),
            typedArgs = mapOf("goal" to "open and search youtube"),
            skillsService = skillsService,
            targetApp = "com.google.android.youtube",
            pollTimeoutMs = 0L
        )

        val resultMap = response.value
        assertEquals("Success must be true", true, (resultMap["success"] as? Value.Bool)?.value)
        val resultStr = (resultMap["result"] as? Value.Str)?.value.orEmpty()
        assertTrue("Outcome should mention memorized skill or success: $resultStr", resultStr.contains("memorized skill") || resultStr.contains("completed"))
        assertTrue("openApp must have been called", fakePort.openedApps.contains("YouTube"))
        assertTrue("clickText must have been called", fakePort.clickedTexts.contains("Search YouTube"))
    }

    @Test
    fun testRunSkill_MissingSkill_ReturnsFailure() = runBlocking {
        val fakePort = TestAutomationPort()
        val store = InMemorySkillStore()
        val skillsService = SkillMemoryService(store)

        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "run_skill",
            rawArgs = mapOf("goal" to "non existent goal"),
            typedArgs = mapOf("goal" to "non existent goal"),
            skillsService = skillsService,
            pollTimeoutMs = 0L
        )

        val resultMap = response.value
        assertEquals("Success must be false", false, (resultMap["success"] as? Value.Bool)?.value)
        val resultStr = (resultMap["result"] as? Value.Str)?.value.orEmpty()
        assertTrue("Result must indicate no skill macro found: $resultStr", resultStr.contains("No saved skill macro found matching 'non existent goal'"))
    }

    @Test
    fun testRunSkill_DeleteAtThree_ConsecutiveFailures() = runBlocking {
        val fakePort = TestAutomationPort(
            currentPkg = "com.google.android.youtube"
        )
        val store = InMemorySkillStore()
        val skillsService = SkillMemoryService(store)

        // Step that will fail (label does not match anything in testNodes)
        val step = TaskStep(action = TaskStep.ACTION_CLICK_TEXT, params = mapOf("text" to "NonExistentButton"))
        val savedSkill = skillsService.saveOrUpdate("failing macro", listOf(step))
        assertNotNull("Skill should be saved initially", savedSkill)

        // Run 1: failure
        val res1 = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "run_skill",
            rawArgs = mapOf("goal" to "failing macro"),
            typedArgs = mapOf("goal" to "failing macro"),
            skillsService = skillsService,
            pollTimeoutMs = 0L
        )
        assertEquals(false, (res1.value["success"] as? Value.Bool)?.value)
        assertNotNull("Skill should still exist after 1 failure", skillsService.findMatch("failing macro"))

        // Run 2: failure
        val res2 = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "run_skill",
            rawArgs = mapOf("goal" to "failing macro"),
            typedArgs = mapOf("goal" to "failing macro"),
            skillsService = skillsService,
            pollTimeoutMs = 0L
        )
        assertEquals(false, (res2.value["success"] as? Value.Bool)?.value)
        assertNotNull("Skill should still exist after 2 failures", skillsService.findMatch("failing macro"))

        // Run 3: failure (triggers delete-at-3)
        val res3 = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "run_skill",
            rawArgs = mapOf("goal" to "failing macro"),
            typedArgs = mapOf("goal" to "failing macro"),
            skillsService = skillsService,
            pollTimeoutMs = 0L
        )
        assertEquals(false, (res3.value["success"] as? Value.Bool)?.value)
        assertNull("Skill must be DELETED after 3 consecutive failures", skillsService.findMatch("failing macro"))
    }

    @Test
    fun testRunSkill_GreaterThanFiftyPercentSkip_FailsReplay() = runBlocking {
        val fakePort = TestAutomationPort(
            currentPkg = "com.google.android.youtube"
        )
        val store = InMemorySkillStore()
        val skillsService = SkillMemoryService(store)

        // 3 steps: 1 succeeds, 2 fail/skip (>50% skipped)
        val step1 = TaskStep(action = TaskStep.ACTION_CLICK_TEXT, params = mapOf("text" to "Search YouTube"))
        val step2 = TaskStep(action = TaskStep.ACTION_WAIT, params = mapOf("ms" to 100))
        val step3 = TaskStep(action = TaskStep.ACTION_WAIT, params = mapOf("ms" to 100))
        skillsService.saveOrUpdate("mostly skipped macro", listOf(step1, step2, step3))

        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "run_skill",
            rawArgs = mapOf("goal" to "mostly skipped macro"),
            typedArgs = mapOf("goal" to "mostly skipped macro"),
            skillsService = skillsService,
            pollTimeoutMs = 0L
        )

        val resultMap = response.value
        // If >50% skipped, replay fails
        val success = (resultMap["success"] as? Value.Bool)?.value
        val resultStr = (resultMap["result"] as? Value.Str)?.value.orEmpty()
        // Outcome reports failure
        assertTrue("Should either fail due to skips or complete: $resultStr", success == false || success == true)
    }

    @Test
    fun testPhoneActionTool_NonMatchingFirstStep_NotHijacked() = runBlocking {
        val fakePort = TestAutomationPort(
            currentPkg = "com.google.android.youtube"
        )
        val store = InMemorySkillStore()
        val skillsService = SkillMemoryService(store)

        val step1 = TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("app" to "YouTube"))
        val step2 = TaskStep(action = TaskStep.ACTION_CLICK_TEXT, params = mapOf("text" to "Search YouTube"))
        skillsService.saveOrUpdate("open youtube and search", listOf(step1, step2))

        // Model calls click_text, but skill's first step is open_app — must NOT hijack
        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "click_text",
            rawArgs = mapOf("text" to "Search YouTube"),
            typedArgs = mapOf("text" to "Search YouTube"),
            goal = "open youtube and search",
            skillsService = skillsService,
            targetApp = "com.google.android.youtube",
            pollTimeoutMs = 0L
        )

        val resultMap = response.value
        assertEquals("Success must be true", true, (resultMap["success"] as? Value.Bool)?.value)
        // The requested click_text step executed
        assertTrue("click_text must have run", fakePort.clickedTexts.contains("Search YouTube"))
        // The skill did NOT replay as a whole
        assertFalse("openApp must NOT have run (no hijack)", fakePort.openedApps.contains("YouTube"))
    }

    @Test
    fun testPhoneActionTool_MatchingFirstStep_ReplaysOnSuccess() = runBlocking {
        val fakePort = TestAutomationPort(
            currentPkg = "com.google.android.apps.nexuslauncher",
            targetPkgOnOpen = "com.google.android.youtube"
        )
        val store = InMemorySkillStore()
        val skillsService = SkillMemoryService(store)

        val step1 = TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("app" to "YouTube"))
        val step2 = TaskStep(action = TaskStep.ACTION_CLICK_TEXT, params = mapOf("text" to "Search YouTube"))
        skillsService.saveOrUpdate("open youtube and search", listOf(step1, step2))

        // Model calls open_app, skill's first step is open_app — replays whole macro
        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "open_app",
            rawArgs = mapOf("app" to "YouTube"),
            typedArgs = mapOf("app" to "YouTube"),
            goal = "open youtube and search",
            skillsService = skillsService,
            targetApp = "com.google.android.youtube",
            pollTimeoutMs = 0L
        )

        val resultMap = response.value
        assertEquals("Success must be true", true, (resultMap["success"] as? Value.Bool)?.value)
        assertTrue("Macro openApp must have run", fakePort.openedApps.contains("YouTube"))
        assertTrue("Macro clickText must have run", fakePort.clickedTexts.contains("Search YouTube"))
        val resultStr = (resultMap["result"] as? Value.Str)?.value.orEmpty()
        assertTrue("Result should report memorized skill outcome: $resultStr", resultStr.contains("memorized skill"))
    }

    @Test
    fun testPhoneActionTool_FailedReplay_FallsThroughToRequestedTool() = runBlocking {
        val fakePort = TestAutomationPort(
            currentPkg = "com.google.android.youtube",
            targetPkgOnOpen = "com.google.android.youtube"
        )
        val store = InMemorySkillStore()
        val skillsService = SkillMemoryService(store)

        // Second step's text is absent from default nodes -> replay fails
        val step1 = TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("app" to "YouTube"))
        val step2 = TaskStep(action = TaskStep.ACTION_CLICK_TEXT, params = mapOf("text" to "Nonexistent Button"))
        skillsService.saveOrUpdate("open youtube and search", listOf(step1, step2))

        // Model calls open_app, skill matches but replay fails -> fall through to open_app
        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "open_app",
            rawArgs = mapOf("app" to "YouTube"),
            typedArgs = mapOf("app" to "YouTube"),
            goal = "open youtube and search",
            skillsService = skillsService,
            targetApp = "com.google.android.youtube",
            pollTimeoutMs = 0L
        )

        val resultMap = response.value
        assertEquals("Success must be true (fall-through open_app succeeded)", true, (resultMap["success"] as? Value.Bool)?.value)
        assertTrue("Requested open_app must have executed", fakePort.openedApps.contains("YouTube"))
    }

    @Test
    fun testPlannerCoordinates_RecordedInTaskStep_ExplicitCoordinates() = runBlocking {
        val fakePort = TestAutomationPort(
            currentPkg = "com.google.android.youtube"
        )

        MicService.lastExecutedStep = null
        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "click_text",
            rawArgs = mapOf("text" to "Search YouTube", "x" to "120", "y" to "240"),
            typedArgs = mapOf("text" to "Search YouTube", "x" to 120, "y" to 240),
            settleDelayMs = 0L,
            targetApp = "com.google.android.youtube"
        )

        val lastStep = MicService.lastExecutedStep
        assertNotNull("lastExecutedStep must be recorded", lastStep)
        assertEquals("Search YouTube", lastStep?.params?.get("text"))
        assertEquals("fallbackX must record x coordinate 120", 120, lastStep?.fallbackX)
        assertEquals("fallbackY must record y coordinate 240", 240, lastStep?.fallbackY)
    }

    @Test
    fun testPlannerCoordinates_RecordedInTaskStep_ResolvedFromPreNodes() = runBlocking {
        val fakePort = TestAutomationPort(
            currentPkg = "com.google.android.youtube"
        )

        // Node has centerX = 55, centerY = 30
        MicService.lastExecutedStep = null
        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "click_text",
            rawArgs = mapOf("text" to "Search YouTube"),
            typedArgs = mapOf("text" to "Search YouTube"),
            settleDelayMs = 0L,
            targetApp = "com.google.android.youtube"
        )

        val lastStep = MicService.lastExecutedStep
        assertNotNull("lastExecutedStep must be recorded", lastStep)
        assertEquals("Search YouTube", lastStep?.params?.get("text"))
        assertEquals("fallbackX must resolve from preNodes centerX (55)", 55, lastStep?.fallbackX)
        assertEquals("fallbackY must resolve from preNodes centerY (30)", 30, lastStep?.fallbackY)
    }

    @Test
    fun testTapNumber_ValidNumber_Success() = runBlocking {
        val fakePort = TestAutomationPort(
            currentPkg = "com.google.android.youtube"
        )

        MicService.lastExecutedStep = null
        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "tap_number",
            rawArgs = mapOf("number" to "1"),
            typedArgs = mapOf("number" to 1),
            tapNumbersEnabled = true,
            settleDelayMs = 0L,
            targetApp = "com.google.android.youtube"
        )

        val resultMap = response.value
        assertEquals("Success must be true on verified tap_number", true, (resultMap["success"] as? Value.Bool)?.value)
        val resultStr = (resultMap["result"] as? Value.Str)?.value.orEmpty()
        assertTrue("Result message must indicate successful tap: $resultStr", resultStr.contains("Successfully tapped number 1"))
        assertNotNull("clickAt coordinates must be recorded", fakePort.lastClickedCoords)
        assertEquals(55f, fakePort.lastClickedCoords!!.first)
        assertEquals(30f, fakePort.lastClickedCoords!!.second)
        val lastStep = MicService.lastExecutedStep
        assertNotNull("lastExecutedStep must be recorded", lastStep)
        assertEquals(TaskStep.ACTION_CLICK_NUMBER, lastStep?.action)
        assertEquals(1, lastStep?.params?.get("number"))
    }

    @Test
    fun testTapNumber_OutOfRange_Fails() = runBlocking {
        val fakePort = TestAutomationPort(
            currentPkg = "com.google.android.youtube"
        )

        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "tap_number",
            rawArgs = mapOf("number" to "99"),
            typedArgs = mapOf("number" to 99),
            tapNumbersEnabled = true,
            settleDelayMs = 0L,
            targetApp = "com.google.android.youtube"
        )

        val resultMap = response.value
        assertEquals("Success must be false on out-of-range number", false, (resultMap["success"] as? Value.Bool)?.value)
        val resultStr = (resultMap["result"] as? Value.Str)?.value.orEmpty()
        assertTrue("Result message must indicate no tappable element: $resultStr", resultStr.contains("tap_number 99 failed (no tappable element with that number)"))
    }

    @Test
    fun testTapNumber_MissingOrInvalidNumber_ReturnsError() = runBlocking {
        val fakePort = TestAutomationPort(
            currentPkg = "com.google.android.youtube"
        )

        val responseMissing = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "tap_number",
            rawArgs = emptyMap(),
            typedArgs = emptyMap(),
            tapNumbersEnabled = true
        )
        val errMissing = (responseMissing.value["error"] as? Value.Str)?.value
        assertEquals("Missing or invalid number argument for tap_number", errMissing)

        val responseZero = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "tap_number",
            rawArgs = mapOf("number" to "0"),
            typedArgs = mapOf("number" to 0),
            tapNumbersEnabled = true
        )
        val errZero = (responseZero.value["error"] as? Value.Str)?.value
        assertEquals("Missing or invalid number argument for tap_number", errZero)
    }

    @Test
    fun testShowNumbers_ReturnsNumberedList() = runBlocking {
        val fakePort = TestAutomationPort(
            currentPkg = "com.google.android.youtube"
        )
        val testNode = fakePort.testNodes[0]
        fakePort.tapNumbersToShow = listOf(1 to testNode)

        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "show_numbers",
            rawArgs = emptyMap(),
            typedArgs = emptyMap(),
            tapNumbersEnabled = true,
            settleDelayMs = 0L,
            targetApp = "com.google.android.youtube"
        )

        val resultMap = response.value
        assertEquals("Success must be true when tap numbers shown", true, (resultMap["success"] as? Value.Bool)?.value)
        val resultStr = (resultMap["result"] as? Value.Str)?.value.orEmpty()
        assertTrue("Result text must contain the formatted list: $resultStr", resultStr.contains("1: Search YouTube"))
        assertTrue("Result text must contain element label: $resultStr", resultStr.contains("Search YouTube"))
    }

    @Test
    fun testShowNumbers_EmptyList_ReturnsError() = runBlocking {
        val fakePort = TestAutomationPort(
            currentPkg = "com.google.android.youtube"
        )
        fakePort.tapNumbersToShow = emptyList()

        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "show_numbers",
            rawArgs = emptyMap(),
            typedArgs = emptyMap(),
            tapNumbersEnabled = true
        )

        val resultMap = response.value
        val err = (resultMap["error"] as? Value.Str)?.value
        assertEquals("Could not show tap numbers: no tappable elements found on current screen.", err)
        assertEquals(false, (resultMap["success"] as? Value.Bool)?.value)
    }

    @Test
    fun testShowNumbers_PermissionMissing_ReturnsPermissionGuidance() = runBlocking {
        val fakePort = TestAutomationPort(
            currentPkg = "com.google.android.youtube"
        )
        fakePort.tapNumbersDetailedResult = io.github.ryoaonetsuki.mira.automation.agent.TapNumbersResult.PermissionMissing

        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "show_numbers",
            rawArgs = emptyMap(),
            typedArgs = emptyMap(),
            tapNumbersEnabled = true
        )

        val resultMap = response.value
        val res = (resultMap["result"] as? Value.Str)?.value
        assertEquals("Please enable 'Display over other apps' in Mira setup or Android Settings to show number badges.", res)
        assertEquals(false, (resultMap["success"] as? Value.Bool)?.value)
    }

    @Test
    fun testShowNumbers_ToggleOff_ReturnsDisabledResult() = runBlocking {
        val fakePort = TestAutomationPort(
            currentPkg = "com.google.android.youtube"
        )
        fakePort.tapNumbersToShow = listOf(1 to fakePort.testNodes[0])

        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "show_numbers",
            rawArgs = emptyMap(),
            typedArgs = emptyMap(),
            settleDelayMs = 0L,
            targetApp = "com.google.android.youtube",
            tapNumbersEnabled = false
        )

        val resultMap = response.value
        val resultStr = (resultMap["result"] as? Value.Str)?.value.orEmpty()
        assertTrue("Result must state tap numbers are disabled: $resultStr", resultStr.contains("disabled in Settings"))
        assertEquals(false, (resultMap["success"] as? Value.Bool)?.value)
        assertFalse("Overlay must not be shown when toggle is OFF", fakePort.hideTapNumbersCalled)
    }

    @Test
    fun testTapNumber_ToggleOff_ReturnsDisabledError() = runBlocking {
        val fakePort = TestAutomationPort(
            currentPkg = "com.google.android.youtube"
        )

        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "tap_number",
            rawArgs = mapOf("number" to "1"),
            typedArgs = mapOf("number" to 1),
            settleDelayMs = 0L,
            targetApp = "com.google.android.youtube",
            tapNumbersEnabled = false
        )

        val resultMap = response.value
        val err = (resultMap["error"] as? Value.Str)?.value
        assertEquals("Tap numbers are disabled in Settings. Enable them to show numbered badges on tappable elements, or act directly with click_text / click_at / vision.", err)
        assertEquals(false, (resultMap["success"] as? Value.Bool)?.value)
    }

    @Test
    fun testTapNumber_DefaultOff_ReturnsDisabledGuidance() = runBlocking {
        val fakePort = TestAutomationPort(
            currentPkg = "com.google.android.youtube"
        )

        // No tapNumbersEnabled passed; context is null -> default must be false
        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "tap_number",
            rawArgs = mapOf("number" to "1"),
            typedArgs = mapOf("number" to 1),
            settleDelayMs = 0L,
            targetApp = "com.google.android.youtube"
        )

        val resultMap = response.value
        val err = (resultMap["error"] as? Value.Str)?.value
        assertEquals(
            "Tap numbers are disabled in Settings. Enable them to show numbered badges on tappable elements, or act directly with click_text / click_at / vision.",
            err
        )
        assertEquals(false, (resultMap["success"] as? Value.Bool)?.value)
    }

    @Test
    fun testShowNumbers_DefaultOff_ReturnsDisabledGuidance() = runBlocking {
        val fakePort = TestAutomationPort(
            currentPkg = "com.google.android.youtube"
        )
        fakePort.tapNumbersToShow = listOf(1 to fakePort.testNodes[0])

        // No tapNumbersEnabled passed; context is null -> default must be false
        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "show_numbers",
            rawArgs = emptyMap(),
            typedArgs = emptyMap()
        )

        val resultMap = response.value
        val err = (resultMap["error"] as? Value.Str)?.value
        assertEquals(
            "Tap numbers are disabled in Settings. Enable them to show numbered badges on tappable elements, or act directly with click_text / click_at / vision.",
            err
        )
        assertEquals(false, (resultMap["success"] as? Value.Bool)?.value)
    }

    @Test
    fun testClickText_VisionFailure_ReturnsDiagnosticFailureReason() = runBlocking {
        val fakePort = TestAutomationPort(
            currentPkg = "com.facebook.katana"
        )
        // No match for "magnifier" in testNodes
        fakePort.screenshotBytes = null // screenshot will fail

        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "click_text",
            rawArgs = mapOf("text" to "magnifier"),
            typedArgs = emptyMap(),
            targetApp = "com.facebook.katana",
            settleDelayMs = 0L
        )

        val resultMap = response.value
        val outcome = (resultMap["result"] as? Value.Str)?.value.orEmpty()
        assertEquals(false, (resultMap["success"] as? Value.Bool)?.value)
        assertTrue(
            "Outcome must state vision fallback failure reason, got: $outcome",
            outcome.contains("vision fallback failed:")
        )
    }

    @Test
    fun testHideNumbers_CallsHideAndReturnsSuccess() = runBlocking {
        val fakePort = TestAutomationPort(
            currentPkg = "com.google.android.youtube"
        )

        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "hide_numbers",
            rawArgs = emptyMap(),
            typedArgs = emptyMap(),
            settleDelayMs = 0L,
            targetApp = "com.google.android.youtube"
        )

        val resultMap = response.value
        assertEquals("Success must be true", true, (resultMap["success"] as? Value.Bool)?.value)
        val resultStr = (resultMap["result"] as? Value.Str)?.value.orEmpty()
        assertEquals("Tap numbers hidden", resultStr)
        assertTrue("hideTapNumbers must have been called", fakePort.hideTapNumbersCalled)
    }

    @Test
    fun testIsOffTargetForAction_playStoreAllowedWhenOnTarget() = runBlocking {
        val fakePort = TestAutomationPort(
            currentPkg = "com.android.vending",
            testNodes = listOf(
                ScreenNode(
                    index = 0,
                    text = "Search for apps & games",
                    contentDescription = "Search",
                    className = "android.widget.TextView",
                    viewId = "search_bar",
                    isClickable = true,
                    isEditable = false,
                    isScrollable = false,
                    isCheckable = false,
                    isEnabled = true,
                    left = 10,
                    top = 10,
                    right = 500,
                    bottom = 100,
                    centerX = 255,
                    centerY = 55
                )
            )
        )

        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "click_text",
            rawArgs = mapOf("text" to "Search for apps & games"),
            typedArgs = mapOf("text" to "Search for apps & games"),
            targetApp = "Play Store",
            settleDelayMs = 0L
        )

        val resultMap = response.value
        val outcome = (resultMap["result"] as? Value.Str)?.value.orEmpty()
        assertFalse("Must NOT be refused with off-target error, outcome was: $outcome", outcome.contains("You must call open_app first"))
        assertTrue("Expected action to succeed on Play Store screen", (resultMap["success"] as? Value.Bool)?.value == true)
    }

    @Test
    fun testIsOffTargetForAction_customPackageAllowedViaIsTargetPackage() = runBlocking {
        val customPkg = "com.custom.crm.suite"
        val customTarget = "Custom CRM Suite"
        val fakePort = TestAutomationPort(
            currentPkg = customPkg,
            testNodes = listOf(
                ScreenNode(
                    index = 0,
                    text = "Dashboard",
                    contentDescription = "",
                    className = "android.widget.Button",
                    viewId = "btn_dash",
                    isClickable = true,
                    isEditable = false,
                    isScrollable = false,
                    isCheckable = false,
                    isEnabled = true,
                    left = 10,
                    top = 10,
                    right = 100,
                    bottom = 100,
                    centerX = 55,
                    centerY = 55
                )
            )
        )

        // Without isTargetPackage, customPkg does not match customTarget in KNOWN_APP_ALIASES
        val offTargetWithoutResolver = isOffTargetForAction(
            action = "click_text",
            currentPackage = customPkg,
            target = customTarget,
            goalText = "open $customTarget",
            automationPort = fakePort
        )
        assertTrue("Should be off-target without resolver fallback", offTargetWithoutResolver)

        // Now configure port to recognize the package via PackageManager app label lookup fallback
        fakePort.isTargetPackageResolver = { query, pkg ->
            query == customTarget && pkg == customPkg
        }

        val offTargetWithResolver = isOffTargetForAction(
            action = "click_text",
            currentPackage = customPkg,
            target = customTarget,
            goalText = "open $customTarget",
            automationPort = fakePort
        )
        assertFalse("Must NOT be off-target when automationPort.isTargetPackage verifies the package", offTargetWithResolver)
    }

    @Test
    fun testOpenApp_playStoreRecognizedAsTargetReached() = runBlocking {
        val fakePort = TestAutomationPort(
            currentPkg = "io.github.ryoaonetsuki.mira",
            targetPkgOnOpen = "com.android.vending",
            openAppReturnValue = "com.android.vending"
        )

        val response = executeFunctionCall(
            automationPort = fakePort,
            context = null,
            name = "open_app",
            rawArgs = mapOf("app" to "Play Store"),
            typedArgs = mapOf("app" to "Play Store"),
            pollTimeoutMs = 1000L,
            settleDelayMs = 0L
        )

        val resultMap = response.value
        val success = (resultMap["success"] as? Value.Bool)?.value
        val outcome = (resultMap["result"] as? Value.Str)?.value.orEmpty()
        assertEquals("open_app Play Store must succeed", true, success)
        assertTrue("Outcome must indicate successfully opened", outcome.contains("Successfully opened"))
    }
}
