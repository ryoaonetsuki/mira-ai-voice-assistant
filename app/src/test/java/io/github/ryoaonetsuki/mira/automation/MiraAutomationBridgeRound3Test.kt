package io.github.ryoaonetsuki.mira.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiraAutomationBridgeRound3Test {

    // =========================================================================
    // 1. extractTargetApp Tests (Play Store + Missing Everyday Apps)
    // =========================================================================

    @Test
    fun extractTargetApp_playStore_englishAndBangla() {
        assertEquals("com.android.vending", MiraAutomationBridge.extractTargetApp("play store"))
        assertEquals("com.android.vending", MiraAutomationBridge.extractTargetApp("open play store"))
        assertEquals("com.android.vending", MiraAutomationBridge.extractTargetApp("google play store"))
        assertEquals("com.android.vending", MiraAutomationBridge.extractTargetApp("প্লে স্টোর খুলো"))
        assertEquals("com.android.vending", MiraAutomationBridge.extractTargetApp("প্লে স্টোরে যাও"))
    }

    @Test
    fun extractTargetApp_everydayApps_resolvedCorrectly() {
        assertEquals("com.google.android.apps.photos", MiraAutomationBridge.extractTargetApp("photos"))
        assertEquals("com.google.android.apps.photos", MiraAutomationBridge.extractTargetApp("google photos"))
        assertEquals("com.google.android.apps.photos", MiraAutomationBridge.extractTargetApp("ফটোস"))

        assertEquals("com.google.android.apps.docs", MiraAutomationBridge.extractTargetApp("drive"))
        assertEquals("com.google.android.apps.docs", MiraAutomationBridge.extractTargetApp("google drive"))
        assertEquals("com.google.android.apps.docs", MiraAutomationBridge.extractTargetApp("ড্রাইভ"))

        assertEquals("com.google.android.apps.nbu.files", MiraAutomationBridge.extractTargetApp("files"))
        assertEquals("com.google.android.apps.nbu.files", MiraAutomationBridge.extractTargetApp("files by google"))
        assertEquals("com.google.android.apps.nbu.files", MiraAutomationBridge.extractTargetApp("ফাইলস"))

        assertEquals("com.google.android.deskclock", MiraAutomationBridge.extractTargetApp("clock"))
        assertEquals("com.google.android.deskclock", MiraAutomationBridge.extractTargetApp("ক্লক"))

        assertEquals("com.google.android.calculator", MiraAutomationBridge.extractTargetApp("calculator"))
        assertEquals("com.google.android.calculator", MiraAutomationBridge.extractTargetApp("ক্যালকুলেটর"))

        assertEquals("com.google.android.calendar", MiraAutomationBridge.extractTargetApp("calendar"))
        assertEquals("com.google.android.calendar", MiraAutomationBridge.extractTargetApp("ক্যালেন্ডার"))

        assertEquals("com.google.android.contacts", MiraAutomationBridge.extractTargetApp("contacts"))
        assertEquals("com.google.android.contacts", MiraAutomationBridge.extractTargetApp("কন্টাক্টস"))

        assertEquals("com.google.android.keep", MiraAutomationBridge.extractTargetApp("keep"))
        assertEquals("com.google.android.keep", MiraAutomationBridge.extractTargetApp("google keep"))

        assertEquals("com.google.android.play.games", MiraAutomationBridge.extractTargetApp("play games"))
    }

    @Test
    fun extractTargetApp_playVerb_neverResolvesToPlayStore() {
        // Coordinator regression guard: bare "play" is a media verb, not the Play Store.
        // "play music" must stay a music target (YouTube) and "play games" must stay
        // Play Games — neither may resolve to com.android.vending.
        assertEquals("com.google.android.youtube", MiraAutomationBridge.extractTargetApp("play music"))
        assertNotEquals("com.android.vending", MiraAutomationBridge.extractTargetApp("play lofi"))
        assertEquals("com.google.android.play.games", MiraAutomationBridge.extractTargetApp("play games"))
        val playMusic = MiraAutomationBridge.parseDirectAction("play music")
        assertEquals(
            MiraAutomationBridge.DirectAction.OpenApp("com.google.android.youtube"),
            playMusic
        )
    }

    @Test
    fun extractTargetApp_fuzzyLatinMatching() {
        // Edit distance <= 1 on token length >= 4
        assertEquals("com.google.android.youtube", MiraAutomationBridge.extractTargetApp("youtub kholo"))
        // Trailing 's' strip
        assertEquals("com.facebook.katana", MiraAutomationBridge.extractTargetApp("open facebooks"))
    }

    // =========================================================================
    // 2. parseDirectAction Tests (Search Hijack Fix & Button Stripping)
    // =========================================================================

    @Test
    fun parseDirectAction_clickSearchButton_parsedAsTapLabelNotSearch() {
        // Must NOT parse as Search("button"), must parse as TapLabel("search")
        val actionEn = MiraAutomationBridge.parseDirectAction("click the search button")
        assertTrue("Expected TapLabel, got $actionEn", actionEn is MiraAutomationBridge.DirectAction.TapLabel)
        assertEquals("search", (actionEn as MiraAutomationBridge.DirectAction.TapLabel).label)

        val actionBn = MiraAutomationBridge.parseDirectAction("search বাটনে click করো")
        assertTrue("Expected TapLabel, got $actionBn", actionBn is MiraAutomationBridge.DirectAction.TapLabel)
        assertEquals("search", (actionBn as MiraAutomationBridge.DirectAction.TapLabel).label)

        val actionPlease = MiraAutomationBridge.parseDirectAction("tap the search button please")
        assertTrue("Expected TapLabel, got $actionPlease", actionPlease is MiraAutomationBridge.DirectAction.TapLabel)
        assertEquals("search", (actionPlease as MiraAutomationBridge.DirectAction.TapLabel).label)
    }

    @Test
    fun parseDirectAction_normalSearchCommands_preserved() {
        val search1 = MiraAutomationBridge.parseDirectAction("search lofi songs")
        assertTrue("Expected Search, got $search1", search1 is MiraAutomationBridge.DirectAction.Search)
        assertEquals("lofi songs", (search1 as MiraAutomationBridge.DirectAction.Search).query)

        val search2 = MiraAutomationBridge.parseDirectAction("search for cat videos")
        assertTrue("Expected Search, got $search2", search2 is MiraAutomationBridge.DirectAction.Search)
        assertEquals("cat videos", (search2 as MiraAutomationBridge.DirectAction.Search).query)
    }

    @Test
    fun parseDirectAction_openPlayStore_parsedAsOpenApp() {
        val actionEn = MiraAutomationBridge.parseDirectAction("open play store")
        assertEquals(MiraAutomationBridge.DirectAction.OpenApp("com.android.vending"), actionEn)

        val actionBn = MiraAutomationBridge.parseDirectAction("প্লে স্টোর খুলো")
        assertEquals(MiraAutomationBridge.DirectAction.OpenApp("com.android.vending"), actionBn)
    }

    @Test
    fun parseDirectAction_normalizedFillers_scrollsAndTaps() {
        val scrollBn = MiraAutomationBridge.parseDirectAction("একটু নিচে স্ক্রল করো তো")
        assertEquals(MiraAutomationBridge.DirectAction.Scroll("down", true), scrollBn)

        val tapPlease = MiraAutomationBridge.parseDirectAction("please tap continue button")
        assertTrue("Expected TapLabel, got $tapPlease", tapPlease is MiraAutomationBridge.DirectAction.TapLabel)
        assertEquals("continue", (tapPlease as MiraAutomationBridge.DirectAction.TapLabel).label)
    }

    // =========================================================================
    // 3. isOpenOnlyGoal Tests (Multi-word App Handling)
    // =========================================================================

    @Test
    fun isOpenOnlyGoal_multiWordAppNames() {
        assertTrue("প্লে স্টোর খুলো must be open-only goal", MiraAutomationBridge.isOpenOnlyGoal("প্লে স্টোর খুলো"))
        assertTrue("open play store must be open-only goal", MiraAutomationBridge.isOpenOnlyGoal("open play store"))
        assertTrue("open google play store must be open-only goal", MiraAutomationBridge.isOpenOnlyGoal("open google play store"))
        assertFalse("open play store and search games is multi-step", MiraAutomationBridge.isOpenOnlyGoal("open play store and search games"))
    }
}
