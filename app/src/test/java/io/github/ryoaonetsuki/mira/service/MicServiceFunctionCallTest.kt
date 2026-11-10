package io.github.ryoaonetsuki.mira.service

import ai.pipecat.client.types.Value
import io.github.ryoaonetsuki.mira.automation.agent.AutomationPort
import io.github.ryoaonetsuki.mira.automation.agent.ScreenNode
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

        assertEquals("Should have opened YouTube", listOf("YouTube"), fakePort.openedApps)
        assertEquals("Foreground package should match YouTube", "com.google.android.youtube", fakePort.currentPkg)

        val resultMap = response.value
        val resultStr = (resultMap["result"] as? Value.Str)?.value.orEmpty()
        assertTrue("Result message should indicate success: $resultStr", resultStr.contains("Successfully opened YouTube"))

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
            "type_text",
            "scroll",
            "press_back",
            "press_home",
            "wait",
            "get_screen",
            "web_search",
            "run_connector_command"
        )
        assertEquals("defaultPhoneTools must declare exactly the 10 tools", expected, toolNames)
    }
}
