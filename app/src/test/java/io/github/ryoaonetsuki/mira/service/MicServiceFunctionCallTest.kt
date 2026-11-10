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

        override fun isServiceEnabled(): Boolean = true

        override fun dumpScreen(): List<ScreenNode> = testNodes

        override fun formatNodes(nodes: List<ScreenNode>): String =
            nodes.joinToString("\n") { "${it.className}: ${it.text}" }

        override fun getCompressedScreenDescription(nodes: List<ScreenNode>, goal: String): String =
            "Screen for $goal: ${nodes.joinToString(", ") { it.text.ifBlank { it.contentDescription } }}"

        override fun clickText(text: String, nodes: List<ScreenNode>): Boolean = true

        override fun clickAt(x: Float, y: Float): Boolean = true

        override fun typeText(text: String): Boolean = true

        override fun pressEnter(): Boolean = true

        override fun scroll(direction: String): Boolean = true

        override fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): Boolean = true

        override fun pressBack(): Boolean = true

        override fun pressHome(): Boolean = true

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
}
