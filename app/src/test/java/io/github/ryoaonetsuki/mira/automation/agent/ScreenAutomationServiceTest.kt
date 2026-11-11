package io.github.ryoaonetsuki.mira.automation.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenAutomationServiceTest {

    // =========================================================================
    // 1. formatNodes Tests
    // =========================================================================

    @Test
    fun formatNodes_emptyList_returnsEmptyString() {
        val service = ScreenAutomationService()
        assertEquals("", service.formatNodes(emptyList()))
    }

    @Test
    fun formatNodes_nodeLineFormat_containsIndexTextAndCoordinates() {
        val service = ScreenAutomationService()
        val node = ScreenNode(
            index = 7,
            text = "Confirm Booking",
            contentDescription = "Confirm your hotel booking",
            className = "android.widget.Button",
            viewId = "btn_confirm",
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            isCheckable = false,
            isEnabled = true,
            left = 100,
            top = 200,
            right = 300,
            bottom = 250,
            centerX = 200,
            centerY = 225
        )
        val output = service.formatNodes(listOf(node))
        assertTrue("Expected output to contain index [7]", output.contains("[7]"))
        assertTrue("Expected output to contain text", output.contains("text=\"Confirm Booking\""))
        assertTrue("Expected output to contain desc", output.contains("desc=\"Confirm your hotel booking\""))
        assertTrue("Expected output to contain class", output.contains("class=android.widget.Button"))
        assertTrue("Expected output to contain clickable", output.contains("clickable=true"))
        assertTrue("Expected output to contain coordinates 200,225", output.contains("200,225"))
    }

    @Test
    fun formatNodes_moreThan120Nodes_cappedAt120() {
        val service = ScreenAutomationService()
        val nodes = (0 until 150).map { i ->
            ScreenNode(
                index = i,
                text = "Node $i",
                contentDescription = "Desc $i",
                className = "android.view.View",
                viewId = "view_$i",
                isClickable = true,
                isEditable = false,
                isScrollable = false,
                isCheckable = false,
                isEnabled = true,
                left = 0,
                top = i * 10,
                right = 100,
                bottom = (i + 1) * 10,
                centerX = 50,
                centerY = i * 10 + 5
            )
        }
        val output = service.formatNodes(nodes)
        val lines = output.lines()
        assertEquals(120, lines.size)
        assertTrue(lines.first().contains("[0]"))
        assertTrue(lines.last().contains("[119]"))
    }

    @Test
    fun formatNodes_textTruncatedTo60Chars() {
        val service = ScreenAutomationService()
        val longText = "This is a very long text string that exceeds sixty characters easily for testing truncation"
        val node = ScreenNode(
            index = 1,
            text = longText,
            contentDescription = "",
            className = "android.widget.TextView",
            viewId = "text_view",
            isClickable = false,
            isEditable = false,
            isScrollable = false,
            isCheckable = false,
            isEnabled = true,
            left = 0,
            top = 0,
            right = 100,
            bottom = 50,
            centerX = 50,
            centerY = 25
        )
        val output = service.formatNodes(listOf(node))
        val expectedTruncated = longText.take(60)
        assertTrue(output.contains("text=\"$expectedTruncated\""))
        assertFalse(output.contains(longText.take(61)))
    }

    @Test
    fun formatNodes_skipsEmptyTextAndDescUnlessEditableOrScrollable() {
        val service = ScreenAutomationService()
        val emptyNode = ScreenNode(
            index = 0,
            text = "",
            contentDescription = "",
            className = "android.view.ViewGroup",
            viewId = "container",
            isClickable = false,
            isEditable = false,
            isScrollable = false,
            isCheckable = false,
            isEnabled = true,
            left = 0, top = 0, right = 100, bottom = 100, centerX = 50, centerY = 50
        )
        val editableNode = ScreenNode(
            index = 1,
            text = "",
            contentDescription = "",
            className = "android.widget.EditText",
            viewId = "input",
            isClickable = true,
            isEditable = true,
            isScrollable = false,
            isCheckable = false,
            isEnabled = true,
            left = 0, top = 100, right = 100, bottom = 150, centerX = 50, centerY = 125
        )
        val scrollableNode = ScreenNode(
            index = 2,
            text = "",
            contentDescription = "",
            className = "android.widget.ScrollView",
            viewId = "scroll",
            isClickable = false,
            isEditable = false,
            isScrollable = true,
            isCheckable = false,
            isEnabled = true,
            left = 0, top = 150, right = 100, bottom = 250, centerX = 50, centerY = 200
        )
        val output = service.formatNodes(listOf(emptyNode, editableNode, scrollableNode))
        assertFalse(output.contains("[0]"))
        assertTrue(output.contains("[1]"))
        assertTrue(output.contains("[2]"))
    }

    // =========================================================================
    // 2. getCompressedScreenDescription Tests
    // =========================================================================

    @Test
    fun getCompressedScreenDescription_containsGoalLine() {
        val service = ScreenAutomationService()
        val output = service.getCompressedScreenDescription(emptyList(), "Search for hotels in Tokyo")
        assertEquals("Goal: Search for hotels in Tokyo", output)
    }

    @Test
    fun getCompressedScreenDescription_goalRelevantNodesComeFirst() {
        val service = ScreenAutomationService()
        val unrelatedClickable = ScreenNode(
            index = 0,
            text = "Home Menu",
            contentDescription = "Main menu",
            className = "android.widget.Button",
            viewId = "btn_home",
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            isCheckable = false,
            isEnabled = true,
            left = 0, top = 0, right = 50, bottom = 50, centerX = 25, centerY = 25
        )
        val goalRelevantNode = ScreenNode(
            index = 1,
            text = "Search Tokyo Hotels",
            contentDescription = "",
            className = "android.widget.TextView",
            viewId = "txt_search",
            isClickable = false,
            isEditable = false,
            isScrollable = false,
            isCheckable = false,
            isEnabled = true,
            left = 0, top = 60, right = 100, bottom = 90, centerX = 50, centerY = 75
        )
        val output = service.getCompressedScreenDescription(
            listOf(unrelatedClickable, goalRelevantNode),
            "Search Tokyo"
        )
        val lines = output.lines()
        assertEquals("Goal: Search Tokyo", lines[0])
        assertTrue("Goal relevant node [1] should come first", lines[1].contains("[1]"))
        assertTrue("Clickable node [0] should come second", lines[2].contains("[0]"))
    }

    @Test
    fun getCompressedScreenDescription_cappedAt40Nodes() {
        val service = ScreenAutomationService()
        val nodes = (0 until 60).map { i ->
            ScreenNode(
                index = i,
                text = "Result item $i",
                contentDescription = "",
                className = "android.widget.TextView",
                viewId = "item_$i",
                isClickable = true,
                isEditable = false,
                isScrollable = false,
                isCheckable = false,
                isEnabled = true,
                left = 0,
                top = i * 20,
                right = 100,
                bottom = (i + 1) * 20,
                centerX = 50,
                centerY = i * 20 + 10
            )
        }
        val output = service.getCompressedScreenDescription(nodes, "Find results")
        val lines = output.lines()
        assertEquals("Goal: Find results", lines[0])
        assertEquals(41, lines.size) // 1 goal line + 40 nodes
    }

    @Test
    fun getCompressedScreenDescription_banglaGoalWords_preservedAndPrioritized() {
        val service = ScreenAutomationService()
        val unrelatedNode = ScreenNode(
            index = 0,
            text = "Settings",
            contentDescription = "System settings",
            className = "android.widget.Button",
            viewId = "btn_settings",
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            isCheckable = false,
            isEnabled = true,
            left = 0, top = 0, right = 50, bottom = 50, centerX = 25, centerY = 25
        )
        val banglaRelevantNode = ScreenNode(
            index = 1,
            text = "ইউটিউব অ্যাপ",
            contentDescription = "",
            className = "android.widget.TextView",
            viewId = "txt_yt",
            isClickable = false,
            isEditable = false,
            isScrollable = false,
            isCheckable = false,
            isEnabled = true,
            left = 0, top = 60, right = 100, bottom = 90, centerX = 50, centerY = 75
        )
        val output = service.getCompressedScreenDescription(
            listOf(unrelatedNode, banglaRelevantNode),
            "ইউটিউব খুলো"
        )
        val lines = output.lines()
        assertEquals("Goal: ইউটিউব খুলো", lines[0])
        assertTrue("Bangla goal-relevant node [1] should come before unrelated clickable node [0]", lines[1].contains("[1]"))
        assertTrue(lines[2].contains("[0]"))
    }

    @Test
    fun openApp_blankQuery_returnsNull_andKnownAliasesResolve() {
        val service = ScreenAutomationService(null)
        assertEquals(null, service.openApp(""))
        assertEquals(null, service.openApp("   "))

        assertEquals("com.google.android.youtube", ScreenAutomationService.resolveKnownAppPackage("youtube"))
        assertEquals("com.google.android.youtube", ScreenAutomationService.resolveKnownAppPackage("ইউটিউব"))
        assertEquals("com.google.android.youtube", ScreenAutomationService.resolveKnownAppPackage("यूट्यूब"))
        assertEquals("com.google.android.youtube", ScreenAutomationService.resolveKnownAppPackage("يوتيوب"))
        assertEquals("com.google.android.youtube", ScreenAutomationService.resolveKnownAppPackage("ユーチューブ"))
        assertEquals("com.google.android.youtube", ScreenAutomationService.resolveKnownAppPackage("유튜브"))
        assertEquals("com.google.android.youtube", ScreenAutomationService.resolveKnownAppPackage("ютуб"))
        assertEquals("com.google.android.youtube", ScreenAutomationService.resolveKnownAppPackage("গান"))
        assertEquals("com.spotify.music", ScreenAutomationService.resolveKnownAppPackage("spotify"))
        assertEquals("com.spotify.music", ScreenAutomationService.resolveKnownAppPackage("স্পটিফাই"))
        assertEquals("com.whatsapp", ScreenAutomationService.resolveKnownAppPackage("whatsapp"))
        assertEquals("com.whatsapp", ScreenAutomationService.resolveKnownAppPackage("হোয়াটসঅ্যাপ"))
        assertEquals("com.whatsapp", ScreenAutomationService.resolveKnownAppPackage("व्हाट्सएप"))
        assertEquals("com.whatsapp", ScreenAutomationService.resolveKnownAppPackage("واتساب"))
        assertEquals("com.android.chrome", ScreenAutomationService.resolveKnownAppPackage("ক্রোম"))
        assertEquals("com.google.android.apps.maps", ScreenAutomationService.resolveKnownAppPackage("ম্যাপস"))
        assertEquals("com.google.android.gm", ScreenAutomationService.resolveKnownAppPackage("জিমেইল"))
        assertEquals("com.facebook.katana", ScreenAutomationService.resolveKnownAppPackage("ফেসবুক"))
        assertEquals("com.android.settings", ScreenAutomationService.resolveKnownAppPackage("সেটিংস"))
    }

    // =========================================================================
    // 3. ScreenNode fromMap Tests
    // =========================================================================

    @Test
    fun screenNode_fromMap_convertsAllFields() {
        val map = mapOf<String, Any?>(
            "index" to 42,
            "text" to "Send Message",
            "contentDescription" to "Send button description",
            "className" to "android.widget.ImageButton",
            "viewId" to "io.github.ryoaonetsuki.mira:id/send_btn",
            "isClickable" to true,
            "isEditable" to false,
            "isScrollable" to false,
            "isCheckable" to false,
            "isEnabled" to true,
            "left" to 10,
            "top" to 20,
            "right" to 110,
            "bottom" to 120,
            "centerX" to 60,
            "centerY" to 70
        )
        val node = ScreenNode.fromMap(map)
        assertEquals(42, node.index)
        assertEquals("Send Message", node.text)
        assertEquals("Send button description", node.contentDescription)
        assertEquals("android.widget.ImageButton", node.className)
        assertEquals("io.github.ryoaonetsuki.mira:id/send_btn", node.viewId)
        assertTrue(node.isClickable)
        assertFalse(node.isEditable)
        assertFalse(node.isScrollable)
        assertFalse(node.isCheckable)
        assertTrue(node.isEnabled)
        assertEquals(10, node.left)
        assertEquals(20, node.top)
        assertEquals(110, node.right)
        assertEquals(120, node.bottom)
        assertEquals(60, node.centerX)
        assertEquals(70, node.centerY)
    }

    @Test
    fun screenNode_fromMap_handlesMissingAndNullValuesSafely() {
        val emptyMap = emptyMap<String, Any?>()
        val node = ScreenNode.fromMap(emptyMap)
        assertEquals(0, node.index)
        assertEquals("", node.text)
        assertEquals("", node.contentDescription)
        assertEquals("", node.className)
        assertEquals("", node.viewId)
        assertFalse(node.isClickable)
        assertFalse(node.isEditable)
        assertFalse(node.isScrollable)
        assertFalse(node.isCheckable)
        assertTrue(node.isEnabled)
        assertEquals(0, node.left)
        assertEquals(0, node.top)
        assertEquals(0, node.right)
        assertEquals(0, node.bottom)
        assertEquals(0, node.centerX)
        assertEquals(0, node.centerY)
    }

    // =========================================================================
    // 4. Round 25 Fix Turn C — C3 (clickText fallback) & C5 (gesture-nav clamping)
    // =========================================================================

    @Test
    fun clickText_withFallbackCoordinates_usesClickAtWhenTextNotOnScreen() {
        val clickedCoords = mutableListOf<Pair<Float, Float>>()
        val service = object : ScreenAutomationService(null) {
            override fun clickAt(x: Float, y: Float): Boolean {
                clickedCoords.add(Pair(x, y))
                return true
            }
        }
        val nodes = listOf(
            ScreenNode(
                index = 0,
                text = "Trending Video Today",
                contentDescription = "",
                className = "android.widget.TextView",
                viewId = "video_title",
                isClickable = true,
                isEditable = false,
                isScrollable = false,
                isCheckable = false,
                isEnabled = true,
                left = 0, top = 100, right = 1080, bottom = 300,
                centerX = 540, centerY = 200
            )
        )

        // 1. When text is found on screen, clicks the matched node's center (540, 200)
        assertTrue(service.clickText("Trending Video", nodes, fallbackX = 900, fallbackY = 1800))
        assertEquals(Pair(540f, 200f), clickedCoords.last())

        // 2. When stale recorded video title is gone, falls back to recorded (fallbackX, fallbackY)
        assertTrue(service.clickText("Stale Yesterday Video Title", nodes, fallbackX = 540, fallbackY = 960))
        assertEquals(Pair(540f, 960f), clickedCoords.last())

        // 3. When text is gone and no fallback coordinates exist, returns false
        assertFalse(service.clickText("Stale Yesterday Video Title", nodes, fallbackX = null, fallbackY = null))
    }

    @Test
    fun gestureNavHardening_C5_swipeClampedToEdgeInset_andClickAtClampedToBoundsOnly() {
        // 1080x2400 @ 3.0x density -> 24dp = 72px inset
        val inset3x = io.github.ryoaonetsuki.mira.service.MiraAccessibilityService.computeSwipeEdgeInsetPx(3.0f)
        assertEquals(72f, inset3x, 0.001f)

        // Low density (0.5x -> 12px) still enforces minimum 16px inset
        val insetLow = io.github.ryoaonetsuki.mira.service.MiraAccessibilityService.computeSwipeEdgeInsetPx(0.5f)
        assertEquals(16f, insetLow, 0.001f)

        // Edge-started swipe (left edge x=2 -> right edge x=1078, top y=5 -> bottom y=2395) is clamped inside [72..1008, 72..2328]
        val clampedSwipe = io.github.ryoaonetsuki.mira.service.MiraAccessibilityService.clampSwipeCoordinates(
            startX = 2f,
            startY = 5f,
            endX = 1078f,
            endY = 2395f,
            displayWidth = 1080,
            displayHeight = 2400,
            density = 3.0f
        )
        assertEquals(72f, clampedSwipe.startX, 0.001f)
        assertEquals(72f, clampedSwipe.startY, 0.001f)
        assertEquals(1008f, clampedSwipe.endX, 0.001f)
        assertEquals(2328f, clampedSwipe.endY, 0.001f)

        // Interior swipe is untouched
        val interiorSwipe = io.github.ryoaonetsuki.mira.service.MiraAccessibilityService.clampSwipeCoordinates(
            startX = 540f,
            startY = 1600f,
            endX = 540f,
            endY = 800f,
            displayWidth = 1080,
            displayHeight = 2400,
            density = 3.0f
        )
        assertEquals(540f, interiorSwipe.startX, 0.001f)
        assertEquals(1600f, interiorSwipe.startY, 0.001f)
        assertEquals(540f, interiorSwipe.endX, 0.001f)
        assertEquals(800f, interiorSwipe.endY, 0.001f)

        // clickAt: clamps out-of-bounds coordinates to [0..width-1, 0..height-1] WITHOUT edge inset
        val bottomTabTap = io.github.ryoaonetsuki.mira.service.MiraAccessibilityService.clampClickCoordinates(
            x = 12f,
            y = 2385f,
            displayWidth = 1080,
            displayHeight = 2400
        )
        assertEquals("Bottom-tab and edge taps must NOT be inset", 12f, bottomTabTap.first, 0.001f)
        assertEquals("Bottom-tab tap near bottom edge must NOT be inset", 2385f, bottomTabTap.second, 0.001f)

        val outOfBoundsTap = io.github.ryoaonetsuki.mira.service.MiraAccessibilityService.clampClickCoordinates(
            x = -25f,
            y = 3000f,
            displayWidth = 1080,
            displayHeight = 2400
        )
        assertEquals(0f, outOfBoundsTap.first, 0.001f)
        assertEquals(2399f, outOfBoundsTap.second, 0.001f)
    }

    // =========================================================================
    // 5. Round 25 Fix Turn E — Bounded dumpScreen Traversal
    // =========================================================================

    private data class TestTreeNode(
        val id: Int,
        val isEligible: Boolean = true,
        val children: MutableList<TestTreeNode> = mutableListOf()
    )

    @Test
    fun dumpScreenTraversal_nodeCap_capsAtMaxNodes() {
        // Build a tree with 1200 nodes (1 root with 1199 children)
        val root = TestTreeNode(id = 0)
        for (i in 1..1200) {
            root.children.add(TestTreeNode(id = i))
        }

        val collected = mutableListOf<TestTreeNode>()
        val count = io.github.ryoaonetsuki.mira.service.MiraAccessibilityService.traverseTreeBounded(
            root = root,
            startIndex = 0,
            out = collected,
            maxNodes = 800,
            timeBudgetMs = 100_000L,
            startTimeMs = 0L,
            clockMs = { 0L },
            isEligible = { it.isEligible },
            getChildren = { it.children }
        )

        assertEquals("Collected nodes must be capped at maxNodes (800)", 800, collected.size)
        assertEquals("Returned count index must match collected size", 800, count)
    }

    @Test
    fun dumpScreenTraversal_timeBudget_stopsEarlyWhenBudgetExceeded() {
        // Build a wide tree
        val root = TestTreeNode(id = 0)
        for (i in 1..500) {
            root.children.add(TestTreeNode(id = i))
        }

        var simulatedTime = 0L
        val collected = mutableListOf<TestTreeNode>()
        io.github.ryoaonetsuki.mira.service.MiraAccessibilityService.traverseTreeBounded(
            root = root,
            startIndex = 0,
            out = collected,
            maxNodes = 800,
            timeBudgetMs = 50L,
            startTimeMs = 0L,
            clockMs = {
                simulatedTime += 2L
                simulatedTime
            },
            isEligible = { it.isEligible },
            getChildren = { it.children }
        )

        assertTrue("Must stop traversal well before all 501 nodes are visited", collected.size < 500)
        assertTrue("Collected nodes must be > 0", collected.size > 0)
        assertTrue("Simulated time must have reached/exceeded 50ms budget", simulatedTime >= 50L)
    }

    // =========================================================================
    // 6. Issue 1: Launcher Package openApp Tests
    // =========================================================================

    @Test
    fun openApp_launcherPackages_returnsNonNullWithoutStartActivity() {
        val service = ScreenAutomationService(context = null)
        val nexusResult = service.openApp("com.google.android.apps.nexuslauncher")
        assertNotNull("openApp with nexuslauncher must return non-null", nexusResult)
        assertEquals("com.google.android.apps.nexuslauncher", nexusResult)

        val launcher3Result = service.openApp("com.android.launcher3")
        assertNotNull("openApp with launcher3 must return non-null", launcher3Result)
        assertEquals("com.android.launcher3", launcher3Result)
    }
}
