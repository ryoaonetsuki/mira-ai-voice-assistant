package io.github.ryoaonetsuki.mira.automation.agent

import io.github.ryoaonetsuki.mira.network.VisionElement
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VisionFirstPerceptionTest {

    private class TestAutomationPort(
        var screenNodes: List<ScreenNode> = emptyList(),
        var currentPkg: String = "com.example.app",
        var dimensions: Pair<Int, Int> = Pair(1080, 2400)
    ) : AutomationPort {
        override fun isServiceEnabled(): Boolean = true
        override fun dumpScreen(): List<ScreenNode> = screenNodes
        override fun formatNodes(nodes: List<ScreenNode>): String = nodes.joinToString("\n") { it.text }
        override fun getCompressedScreenDescription(nodes: List<ScreenNode>, goal: String): String = "Screen description"
        override fun clickText(text: String, nodes: List<ScreenNode>): Boolean = true
        override fun clickAt(x: Float, y: Float): Boolean = true
        override fun typeText(text: String): Boolean = true
        override fun pressEnter(): Boolean = true
        override fun scroll(direction: String): Boolean = true
        override fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): Boolean = true
        override fun pressBack(): Boolean = true
        override fun pressHome(): Boolean = true
        override fun openApp(query: String): String? = query
        override fun getCurrentPackage(): String? = currentPkg
        override fun getScreenDimensions(): Pair<Int, Int>? = dimensions
        override suspend fun takeScreenshotJpeg(maxWidthPx: Int): ByteArray? = byteArrayOf(1, 2, 3, 4)
    }

    private fun createNode(
        index: Int,
        text: String,
        desc: String = "",
        role: String = "android.widget.Button",
        isClickable: Boolean = true,
        left: Int = 100,
        top: Int = 100,
        right: Int = 200,
        bottom: Int = 200,
        viewId: String = ""
    ): ScreenNode = ScreenNode(
        index = index,
        text = text,
        contentDescription = desc,
        className = role,
        viewId = viewId,
        isClickable = isClickable,
        isEditable = false,
        isScrollable = false,
        isCheckable = false,
        isEnabled = true,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        centerX = (left + right) / 2,
        centerY = (top + bottom) / 2
    )

    // --- (1) findTarget Decision Rule Tests ---

    @Test
    fun testFindTarget_ExactUniqueNodeMatch_ReturnsNodesExact_WithNoVisionCall() = runBlocking {
        var visionCallCount = 0
        val port = TestAutomationPort(
            screenNodes = listOf(
                createNode(0, "Settings", "Open settings"),
                createNode(1, "Profile", "Open profile")
            )
        )

        val perceptionEngine = VisionFirstPerception(
            automationPort = port,
            apiKeyProvider = { "fake_key" },
            visionDescribe = { _, _, _, _, _, _ ->
                visionCallCount++
                Result.success(emptyList())
            }
        )

        val perception = perceptionEngine.perceive("open settings", target = "Settings")
        val resolution = perceptionEngine.findTarget(perception, "Settings", "open settings")

        assertEquals(TargetSource.NODES_EXACT, resolution.source)
        assertEquals(150, resolution.x)
        assertEquals(150, resolution.y)
        assertEquals("Fake vision must NEVER be invoked for exact unique node match", 0, visionCallCount)
    }

    @Test
    fun testFindTarget_NoNodeMatch_CallsVisionAndChoosesElement() = runBlocking {
        var visionCalled = false
        val port = TestAutomationPort(screenNodes = emptyList())

        val visionElement = VisionElement("Search", "button", 540, 200, "search button")
        val perceptionEngine = VisionFirstPerception(
            automationPort = port,
            apiKeyProvider = { "fake_key" },
            visionDescribe = { _, _, _, _, _, _ ->
                visionCalled = true
                Result.success(listOf(visionElement))
            }
        )

        val perception = perceptionEngine.perceive("search", target = "Search")
        val resolution = perceptionEngine.findTarget(perception, "Search", "search")

        assertTrue("Vision must be called when no nodes match", visionCalled)
        assertEquals(TargetSource.VISION, resolution.source)
        assertEquals(540, resolution.x)
        assertEquals(200, resolution.y)
        assertEquals("Search", resolution.matchedVisionElement?.text)
    }

    @Test
    fun testFindTarget_AmbiguousNodeMatches_VisionDecides() = runBlocking {
        var visionCalled = false
        // Two "Search" nodes on screen (e.g. top search bar and bottom search tab)
        val port = TestAutomationPort(
            screenNodes = listOf(
                createNode(0, "Search", "Top search bar", top = 50, bottom = 150),
                createNode(1, "Search", "Bottom search tab", top = 2200, bottom = 2350)
            )
        )

        val chosenVisionElement = VisionElement("Search", "tab", 540, 2275, "bottom search tab")
        val perceptionEngine = VisionFirstPerception(
            automationPort = port,
            apiKeyProvider = { "fake_key" },
            visionDescribe = { _, _, _, _, _, _ ->
                visionCalled = true
                Result.success(listOf(chosenVisionElement))
            }
        )

        val perception = perceptionEngine.perceive("search tab", target = "Search tab")
        val resolution = perceptionEngine.findTarget(perception, "Search tab", "search tab")

        assertTrue("Vision must be called when multiple node candidates match", visionCalled)
        assertEquals(TargetSource.VISION, resolution.source)
        assertEquals(540, resolution.x)
        assertEquals(2275, resolution.y)
    }

    @Test
    fun testFindTarget_IconOnlyNode_VisionDecides() = runBlocking {
        var visionCalled = false
        // Unlabeled icon-only button (blank text and contentDescription)
        val port = TestAutomationPort(
            screenNodes = listOf(
                createNode(0, "", "", role = "android.widget.ImageButton", left = 900, top = 50, right = 1000, bottom = 150)
            )
        )

        val magnifierElement = VisionElement("magnifier search icon", "icon", 950, 100, "open search")
        val perceptionEngine = VisionFirstPerception(
            automationPort = port,
            apiKeyProvider = { "fake_key" },
            visionDescribe = { _, _, _, _, _, _ ->
                visionCalled = true
                Result.success(listOf(magnifierElement))
            }
        )

        val perception = perceptionEngine.perceive("search", target = "search")
        val resolution = perceptionEngine.findTarget(perception, "search", "search")

        assertTrue("Vision must be called when node candidate is icon-only/unlabeled", visionCalled)
        assertEquals(TargetSource.VISION, resolution.source)
        assertEquals(950, resolution.x)
        assertEquals(100, resolution.y)
    }

    @Test
    fun testFindTarget_VisionFailure_ReturnsNodesFallbackWithReason() = runBlocking {
        val fallbackNode = createNode(0, "Search", "Search", left = 200, top = 200, right = 400, bottom = 400)
        // Two candidates to force vision path
        val secondNode = createNode(1, "Search", "Search 2", left = 200, top = 500, right = 400, bottom = 700)
        val port = TestAutomationPort(screenNodes = listOf(fallbackNode, secondNode))

        val perceptionEngine = VisionFirstPerception(
            automationPort = port,
            apiKeyProvider = { "fake_key" },
            visionDescribe = { _, _, _, _, _, _ ->
                Result.failure(IllegalStateException("vision timed out after 8s"))
            }
        )

        val perception = perceptionEngine.perceive("search", target = "search")
        val resolution = perceptionEngine.findTarget(perception, "search", "search")

        assertEquals(TargetSource.NODES_FALLBACK, resolution.source)
        assertNotNull(resolution.failureReason)
        assertTrue("Failure reason must mention timeout or failure", resolution.failureReason!!.contains("vision timed out"))
        assertEquals(fallbackNode.centerX, resolution.x)
        assertEquals(fallbackNode.centerY, resolution.y)
    }

    // --- (2) Wrong-Action Guard Tests ---

    @Test
    fun testWrongActionGuard_ContradictoryNodeAtCoords_ReturnsMismatch() {
        val port = TestAutomationPort()
        val perceptionEngine = VisionFirstPerception(automationPort = port)

        // Vision chose "Search", but node at those coordinates says "Home"
        val homeNode = createNode(0, "Home", "Home button", left = 500, top = 2200, right = 600, bottom = 2300)
        val visionElem = VisionElement("Search", "tab", 550, 2250, "search tab")

        val guardResult = perceptionEngine.verifyPreTap(Pair(550, 2250), visionElem, listOf(homeNode))
        assertTrue("Guard must report mismatch when node contradicts vision", guardResult is PreTapResult.Mismatch)
        val mismatch = guardResult as PreTapResult.Mismatch
        assertEquals("Home", mismatch.nodeText)
        assertEquals("Search", mismatch.visionText)
    }

    @Test
    fun testWrongActionGuard_IconOnlyCoords_ProceedsAsValid() {
        val port = TestAutomationPort()
        val perceptionEngine = VisionFirstPerception(automationPort = port)

        // No node exists at these coordinates (icon-only button drawn via Canvas / SurfaceView)
        val visionElem = VisionElement("magnifier search icon", "icon", 950, 100, "search icon")

        val guardResult = perceptionEngine.verifyPreTap(Pair(950, 100), visionElem, emptyList())
        assertTrue("Guard must allow tap on icon-only element without nodes", guardResult is PreTapResult.Valid)
        assertTrue((guardResult as PreTapResult.Valid).isIconOnly)
    }

    // --- (3) Universal, App-Agnostic Control Tests (5 Unrelated Apps) ---

    @Test
    fun testUniversalGenerality_PlayStore_BottomNavSearchAndTopSearchField() = runBlocking {
        // App 1: Play Store (com.android.vending)
        // Screen 1: Home screen with 5 bottom tabs: Games, Apps, Search, Offers, Books
        val bottomNavNodes = listOf(
            createNode(0, "Games", left = 0, top = 2200, right = 216, bottom = 2350),
            createNode(1, "Apps", left = 216, top = 2200, right = 432, bottom = 2350),
            createNode(2, "Search", left = 432, top = 2200, right = 648, bottom = 2350),
            createNode(3, "Offers", left = 648, top = 2200, right = 864, bottom = 2350),
            createNode(4, "Books", left = 864, top = 2200, right = 1080, bottom = 2350)
        )
        val port = TestAutomationPort(
            currentPkg = "com.android.vending",
            screenNodes = bottomNavNodes
        )

        val visionSearchTab = VisionElement("Search", "tab", 540, 2275, "Search tab")
        val perceptionEngine = VisionFirstPerception(
            automationPort = port,
            apiKeyProvider = { "fake_key" },
            visionDescribe = { _, _, _, _, _, _ ->
                Result.success(listOf(visionSearchTab))
            }
        )

        // Step A: Voice "search বাটনে click করো" -> selects Search tab at bottom
        val perceptionTab = perceptionEngine.perceive("search বাটনে click করো", target = "search বাটন")
        val resTab = perceptionEngine.findTarget(perceptionTab, "search বাটন", "search বাটনে click করো")
        assertEquals(540, resTab.x)
        assertEquals(2275, resTab.y)

        // Screen 2: Search screen with top search field "Search apps & games"
        val topSearchField = VisionElement("Search apps & games", "text_field", 540, 150, "search input")
        val searchScreenPort = TestAutomationPort(
            currentPkg = "com.android.vending",
            screenNodes = listOf(
                createNode(0, "Search apps & games", role = "android.widget.EditText", left = 50, top = 80, right = 1030, bottom = 220)
            )
        )
        val searchEngine = VisionFirstPerception(
            automationPort = searchScreenPort,
            apiKeyProvider = { "fake_key" },
            visionDescribe = { _, _, _, _, _, _ ->
                Result.success(listOf(topSearchField))
            }
        )

        // Step B: Top search field tapped
        val perceptionField = searchEngine.perceive("search field", target = "Search apps & games")
        val resField = searchEngine.findTarget(perceptionField, "Search apps & games", "search field")
        assertEquals(540, resField.x)
        assertEquals(150, resField.y)
    }

    @Test
    fun testUniversalGenerality_Facebook_IconOnlyMagnifierSearch() = runBlocking {
        // App 2: Facebook (com.facebook.katana)
        // Header toolbar has unlabeled search magnifier icon (no text in node tree)
        val port = TestAutomationPort(
            currentPkg = "com.facebook.katana",
            screenNodes = listOf(
                createNode(0, "Facebook", role = "android.widget.TextView", left = 20, top = 40, right = 300, bottom = 120),
                createNode(1, "", "", role = "android.view.ViewGroup", left = 920, top = 40, right = 1040, bottom = 120)
            )
        )

        val magnifier = VisionElement("magnifier search icon", "button", 980, 80, "search facebook")
        val perceptionEngine = VisionFirstPerception(
            automationPort = port,
            apiKeyProvider = { "fake_key" },
            visionDescribe = { _, _, _, _, _, _ ->
                Result.success(listOf(magnifier))
            }
        )

        val perception = perceptionEngine.perceive("search", target = "search")
        val res = perceptionEngine.findTarget(perception, "search", "search")

        assertEquals(TargetSource.VISION, res.source)
        assertEquals(980, res.x)
        assertEquals(80, res.y)
    }

    @Test
    fun testUniversalGenerality_YouTube_TopSearchIconAndBottomTabs() = runBlocking {
        // App 3: YouTube (com.google.android.youtube)
        // Bottom tabs (Home, Shorts, Subscriptions, You) and top search icon
        val port = TestAutomationPort(
            currentPkg = "com.google.android.youtube",
            screenNodes = listOf(
                createNode(0, "Home", left = 0, top = 2200, right = 270, bottom = 2350),
                createNode(1, "Shorts", left = 270, top = 2200, right = 540, bottom = 2350),
                createNode(2, "Subscriptions", left = 540, top = 2200, right = 810, bottom = 2350),
                createNode(3, "You", left = 810, top = 2200, right = 1080, bottom = 2350),
                createNode(4, "", "", role = "android.widget.ImageView", left = 850, top = 50, right = 950, bottom = 150)
            )
        )

        val ytSearchIcon = VisionElement("search icon magnifier", "icon", 900, 100, "search youtube")
        val perceptionEngine = VisionFirstPerception(
            automationPort = port,
            apiKeyProvider = { "fake_key" },
            visionDescribe = { _, _, _, _, _, _ ->
                Result.success(listOf(ytSearchIcon))
            }
        )

        val perception = perceptionEngine.perceive("search youtube", target = "search")
        val res = perceptionEngine.findTarget(perception, "search", "search youtube")

        assertEquals(TargetSource.VISION, res.source)
        assertEquals(900, res.x)
        assertEquals(100, res.y)
    }

    @Test
    fun testUniversalGenerality_Chrome_AddressBar() = runBlocking {
        // App 4: Chrome (com.android.chrome)
        // Address bar at top "Search or type web address"
        val addressNode = createNode(0, "Search or type web address", role = "android.widget.EditText", left = 100, top = 60, right = 900, bottom = 160)
        val port = TestAutomationPort(
            currentPkg = "com.android.chrome",
            screenNodes = listOf(addressNode)
        )

        val perceptionEngine = VisionFirstPerception(
            automationPort = port,
            apiKeyProvider = { "fake_key" }
        )

        val perception = perceptionEngine.perceive("open google", target = "Search or type web address")
        val res = perceptionEngine.findTarget(perception, "Search or type web address", "open google")

        assertEquals(TargetSource.NODES_EXACT, res.source)
        assertEquals(500, res.x)
        assertEquals(110, res.y)
    }

    @Test
    fun testUniversalGenerality_Settings_SearchSettings() = runBlocking {
        // App 5: Settings (com.android.settings)
        // Search bar at top "Search settings"
        val searchSettingsNode = createNode(0, "Search settings", role = "android.widget.TextView", left = 100, top = 100, right = 980, bottom = 200)
        val wifiNode = createNode(1, "Network & internet", left = 100, top = 250, right = 980, bottom = 350)
        val port = TestAutomationPort(
            currentPkg = "com.android.settings",
            screenNodes = listOf(searchSettingsNode, wifiNode)
        )

        val perceptionEngine = VisionFirstPerception(
            automationPort = port,
            apiKeyProvider = { "fake_key" }
        )

        val perception = perceptionEngine.perceive("search bluetooth", target = "Search settings")
        val res = perceptionEngine.findTarget(perception, "Search settings", "search bluetooth")

        assertEquals(TargetSource.NODES_EXACT, res.source)
        assertEquals(540, res.x)
        assertEquals(150, res.y)
    }

    // --- A-M12: Vision picker must not return position-only elements ---

    @Test
    fun testPickBestVisionElement_PositionalOnlyElement_ReturnsNoVisionResolution() = runBlocking {
        // A bottom tab with no text match to the target must not be selected
        // purely on position (A-M12 fix).
        val port = TestAutomationPort(
            screenNodes = emptyList(),
            currentPkg = "com.example.app",
            dimensions = Pair(1080, 2400)
        )

        val bottomTab = VisionElement("Home", "tab", 540, 2275, "bottom nav tab")
        val perceptionEngine = VisionFirstPerception(
            automationPort = port,
            apiKeyProvider = { "fake_key" },
            visionDescribe = { _, _, _, _, _, _ ->
                Result.success(listOf(bottomTab))
            }
        )

        val perception = perceptionEngine.perceive("search", target = "Search Magnifier")
        val resolution = perceptionEngine.findTarget(perception, "Search Magnifier", "search")

        // No text match -> no vision resolution, falls back to NODES_FALLBACK
        assertEquals(TargetSource.NODES_FALLBACK, resolution.source)
        assertEquals(0, resolution.x)
        assertEquals(0, resolution.y)
    }

    @Test
    fun testPickBestVisionElement_TextMatchBeatsPositionOnlyElement() = runBlocking {
        // A text-matching element must win over a position-only bottom tab (A-M12 fix).
        val port = TestAutomationPort(
            screenNodes = emptyList(),
            currentPkg = "com.example.app",
            dimensions = Pair(1080, 2400)
        )

        val bottomTab = VisionElement("Home", "tab", 540, 2275, "bottom nav tab")
        val searchButton = VisionElement("Search", "button", 540, 100, "search button")
        val perceptionEngine = VisionFirstPerception(
            automationPort = port,
            apiKeyProvider = { "fake_key" },
            visionDescribe = { _, _, _, _, _, _ ->
                Result.success(listOf(bottomTab, searchButton))
            }
        )

        val perception = perceptionEngine.perceive("search", target = "Search")
        val resolution = perceptionEngine.findTarget(perception, "Search", "search")

        assertEquals(TargetSource.VISION, resolution.source)
        assertEquals(540, resolution.x)
        assertEquals(100, resolution.y)
    }
}
