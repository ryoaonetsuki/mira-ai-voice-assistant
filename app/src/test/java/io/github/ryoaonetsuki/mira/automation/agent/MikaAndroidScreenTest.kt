package io.github.ryoaonetsuki.mira.automation.agent

import org.junit.Test
import org.junit.Assert.*
import kotlinx.coroutines.test.runTest

class MiraAndroidScreenHelpersTest {

    @Test
    fun verdictToResult_mapsChanged() {
        assertEquals(
            MiraActionResult.Ok(changed = true),
            MiraAndroidScreen.verdictToResult(MikaReliableActions.ActionVerdict.Changed)
        )
    }

    @Test
    fun verdictToResult_mapsUnchanged() {
        assertEquals(
            MiraActionResult.Ok(changed = false),
            MiraAndroidScreen.verdictToResult(MikaReliableActions.ActionVerdict.Unchanged)
        )
    }

    @Test
    fun verdictToResult_mapsFailed() {
        val result = MiraAndroidScreen.verdictToResult(MikaReliableActions.ActionVerdict.failed("boom"))
        assertTrue(result is MiraActionResult.Failed)
        assertEquals("boom", (result as MiraActionResult.Failed).reason)
    }

    @Test
    fun isRefExpired_nullLastId() {
        assertTrue(MiraAndroidScreen.isRefExpired("s1", null))
    }

    @Test
    fun isRefExpired_sameId() {
        assertFalse(MiraAndroidScreen.isRefExpired("s1", "s1"))
    }

    @Test
    fun isRefExpired_differentId() {
        assertTrue(MiraAndroidScreen.isRefExpired("s1", "s2"))
    }

    @Test
    fun snapshotIdFor() {
        assertEquals("s1", MiraAndroidScreen.snapshotIdFor(1))
        assertEquals("s5", MiraAndroidScreen.snapshotIdFor(5))
    }

    @Test
    fun matchWaitFor_appear_found() {
        val elements = listOf(
            MikaUiElement("e1", "Button", "Hello World", 0, 0, listOf(), null, false, false, false, false, false, false, null, false, emptySet())
        )
        assertTrue(MiraAndroidScreen.matchWaitFor("appear", "hello", elements, null))
    }

    @Test
    fun matchWaitFor_appear_caseInsensitive() {
        val elements = listOf(
            MikaUiElement("e1", "Button", "HELLO", 0, 0, listOf(), null, false, false, false, false, false, false, null, false, emptySet())
        )
        assertTrue(MiraAndroidScreen.matchWaitFor("appear", "hello", elements, null))
    }

    @Test
    fun matchWaitFor_appear_nullValue() {
        val elements = listOf(
            MikaUiElement("e1", "Button", "Hello", 0, 0, listOf(), null, false, false, false, false, false, false, null, false, emptySet())
        )
        assertFalse(MiraAndroidScreen.matchWaitFor("appear", null, elements, null))
    }

    @Test
    fun matchWaitFor_disappear_absent() {
        val elements = listOf(
            MikaUiElement("e1", "Button", "Hello", 0, 0, listOf(), null, false, false, false, false, false, false, null, false, emptySet())
        )
        assertTrue(MiraAndroidScreen.matchWaitFor("disappear", "Goodbye", elements, null))
    }

    @Test
    fun matchWaitFor_disappear_present() {
        val elements = listOf(
            MikaUiElement("e1", "Button", "Hello", 0, 0, listOf(), null, false, false, false, false, false, false, null, false, emptySet())
        )
        assertFalse(MiraAndroidScreen.matchWaitFor("disappear", "Hello", elements, null))
    }

    @Test
    fun matchWaitFor_activity_match() {
        assertTrue(MiraAndroidScreen.matchWaitFor("activity", "com.app", emptyList(), "com.app"))
    }

    @Test
    fun matchWaitFor_activity_different() {
        assertFalse(MiraAndroidScreen.matchWaitFor("activity", "com.app", emptyList(), "com.other"))
    }

    @Test
    fun matchWaitFor_unknown() {
        assertFalse(MiraAndroidScreen.matchWaitFor("bogus", "x", emptyList(), null))
    }
}

class MiraAndroidScreenRegistryTest {

    @Test
    fun resolveRef_findsFirst() {
        val locator = MikaElementLocator("Button", "OK", null, 100, 100, null)
        val element = MikaUiElement("e1", "Button", "OK", 100, 100, listOf(0, 0, 200, 200), null, true, false, false, false, false, false, null, false, emptySet())
        val entry = NodeEntry(element, locator)
        val entries = listOf(entry)
        assertSame(entry, MikaReliableActions.resolveRef("@e1", entries))
    }

    @Test
    fun resolveRef_outOfRange() {
        val locator = MikaElementLocator("Button", "OK", null, 100, 100, null)
        val element = MikaUiElement("e1", "Button", "OK", 100, 100, listOf(0, 0, 200, 200), null, true, false, false, false, false, false, null, false, emptySet())
        val entry = NodeEntry(element, locator)
        val entries = listOf(entry)
        assertNull(MikaReliableActions.resolveRef("@e0", entries))
        assertNull(MikaReliableActions.resolveRef("@e99", entries))
        assertNull(MikaReliableActions.resolveRef("x", entries))
    }

    @Test
    fun relocate_foundByRoleNameResourceId() = runTest {
        val prev = MikaElementLocator("Button", "Submit", "btn_submit", 500, 500, null)
        val before = listOf(prev)
        val after = listOf(MikaElementLocator("Button", "Submit", "btn_submit", 510, 505, null))
        val result = relocate(prev, before, after, 1000)
        assertEquals(Relocation.Found(0), result)
    }

    @Test
    fun relocate_goneForMissingName() = runTest {
        val prev = MikaElementLocator("Button", "Settings", null, 500, 500, null)
        val before = listOf(prev)
        val after = listOf(MikaElementLocator("Button", "Home", null, 500, 500, null))
        val result = relocate(prev, before, after, 1000)
        assertEquals(Relocation.Gone, result)
    }

    @Test
    fun findTapRef_exactMatch() {
        val elements = listOf(
            MikaUiElement("e1", "Button", "Search", 100, 100, listOf(), null, true, false, false, false, false, false, null, false, emptySet()),
            MikaUiElement("e2", "Button", "Settings", 200, 200, listOf(), null, true, false, false, false, false, false, null, false, emptySet())
        )
        assertEquals("e1", MiraAndroidScreen.findTapRef(elements, "Search"))
    }

    @Test
    fun findTapRef_caseInsensitive() {
        val elements = listOf(
            MikaUiElement("e1", "Button", "SEARCH", 100, 100, listOf(), null, true, false, false, false, false, false, null, false, emptySet())
        )
        assertEquals("e1", MiraAndroidScreen.findTapRef(elements, "search"))
    }

    @Test
    fun findTapRef_containsFallback() {
        val elements = listOf(
            MikaUiElement("e1", "Button", "Search YouTube", 100, 100, listOf(), null, true, false, false, false, false, false, null, false, emptySet()),
            MikaUiElement("e2", "Button", "Settings", 200, 200, listOf(), null, true, false, false, false, false, false, null, false, emptySet())
        )
        assertEquals("e1", MiraAndroidScreen.findTapRef(elements, "YouTube"))
    }

    @Test
    fun findTapRef_noMatch() {
        val elements = listOf(
            MikaUiElement("e1", "Button", "Settings", 100, 100, listOf(), null, true, false, false, false, false, false, null, false, emptySet())
        )
        assertNull(MiraAndroidScreen.findTapRef(elements, "Search"))
    }

    @Test
    fun findTapRef_emptyList() {
        assertNull(MiraAndroidScreen.findTapRef(emptyList(), "Search"))
    }
}
