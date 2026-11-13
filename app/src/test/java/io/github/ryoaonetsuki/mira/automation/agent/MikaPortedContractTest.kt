package io.github.ryoaonetsuki.mira.automation.agent

import org.junit.Test
import org.junit.Assert.*
import kotlinx.coroutines.test.runTest
import kotlin.random.Random

class MikaAppPacingTest {
    @Test
    fun offPacing() {
        val pacing = AppPacing.Off
        assertFalse(pacing.paced)
        assertEquals(0L, pacing.waitBefore(1000))
        assertEquals(0L, pacing.waitBefore(2000, 100))
    }

    @Test
    fun humanSameScreenDwell() = runTest {
        val pacing = AppPacing.human(Random(42))
        pacing.noteAction("com.app", 1000, false)
        val wait = pacing.waitBefore(1000)
        assertTrue(wait in 0L..2700L)
        val later = pacing.waitBefore(2000)
        assertTrue(later <= wait)
        assertTrue(later in 0L..2700L)
    }

    @Test
    fun humanNewScreenDwell() = runTest {
        val pacing = AppPacing.human(Random(42))
        pacing.noteAction("com.app", 1000, true)
        val wait = pacing.waitBefore(1000)
        assertTrue(wait in 0L..3500L)
    }

    @Test
    fun capReached() = runTest {
        val pacing = AppPacing.human(Random(42))
        repeat(24) { pacing.noteAction("com.careful", 1000L * it, true, PlacePace.CAREFUL) }
        assertTrue(pacing.capReached("com.careful", PlacePace.CAREFUL))
        assertFalse(pacing.capReached("com.normal", PlacePace.NORMAL))

        pacing.userAnswered()
        assertFalse(pacing.capReached("com.careful", PlacePace.CAREFUL))

        repeat(24) { pacing.noteAction("com.careful", 1000L * it, true, PlacePace.CAREFUL) }
        pacing.outwardDone("com.careful")
        assertFalse(pacing.capReached("com.careful", PlacePace.CAREFUL))
    }

    @Test
    fun idleAllowed() = runTest {
        val pacing = AppPacing.human(Random(42), limits = AppPacing.Limits(idleScroll = true))
        assertFalse(pacing.idleAllowed())
        pacing.noteAction("com.app", 1000, true, PlacePace.NORMAL, counts = true)
        assertTrue(pacing.idleAllowed())
    }
}

class MikaOperatingPaceTest {
    @Test
    fun fastPace() {
        val pace = OperatingPace.FAST
        assertFalse(pace.appPacing().paced)
        assertSame(TypingPacing.Instant, pace.typing(PlacePace.NORMAL))
    }

    @Test
    fun standardPace() {
        val pace = OperatingPace.STANDARD
        assertTrue(pace.appPacing(Random(42)).paced)
        assertNotSame(TypingPacing.Instant, pace.typing(PlacePace.NORMAL))
    }

    @Test
    fun humanPace() {
        val pace = OperatingPace.HUMAN
        assertTrue(pace.appPacing(Random(42)).paced)
        val typing = pace.typing(PlacePace.NORMAL, Random(42))
        assertNotSame(TypingPacing.Instant, typing)
    }

    @Test
    fun parse() {
        assertSame(OperatingPace.FAST, OperatingPace.parse("fast"))
        assertSame(OperatingPace.STANDARD, OperatingPace.parse("STANDARD"))
        assertSame(OperatingPace.HUMAN, OperatingPace.parse("Human"))
        assertNull(OperatingPace.parse("nope"))
        assertNull(OperatingPace.parse(null))
    }

    @Test
    fun defaults() {
        assertSame(OperatingPace.STANDARD, OperatingPace.DEFAULT)
        assertSame(OperatingPace.FAST, MIRA_DEFAULT_OPERATING_PACE)
    }
}

class MiraScreenPortTest {
    private class FakeScreenPort : MiraScreenPort {
        var typedText: String? = null
        var cleared: Boolean = false
        var tapX: Int? = null
        var tapY: Int? = null

        override suspend fun snapshot(): MiraScreenSnapshot = MiraScreenSnapshot(
            snapshotId = "s1", packageName = "com.app", elements = emptyList(),
            width = 1080, height = 2400
        )

        override suspend fun screenshotPng(): ByteArray? = null

        override suspend fun tapRef(ref: String, snapshotId: String): MiraActionResult = MiraActionResult.Ok()
        override suspend fun tapXY(x: Int, y: Int): MiraActionResult = MiraActionResult.Ok().also { tapX = x; tapY = y }
        override suspend fun typeText(ref: String?, snapshotId: String?, text: String): MiraActionResult = MiraActionResult.Ok().also { typedText = text }
        override suspend fun clearText(ref: String?, snapshotId: String?): MiraActionResult = MiraActionResult.Ok().also { cleared = true }
        override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): MiraActionResult = MiraActionResult.Ok()
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): MiraActionResult = MiraActionResult.Ok()
        override suspend fun back(): MiraActionResult = MiraActionResult.Ok()
        override suspend fun pressKey(keyCode: Int): MiraActionResult = MiraActionResult.Ok()
        override suspend fun openApp(packageName: String, cold: Boolean): MiraActionResult = MiraActionResult.Ok()
        override suspend fun waitStable(timeoutMs: Long): Boolean = true
    }

    @Test
    fun defaults() = runTest {
        val port = FakeScreenPort()
        val snap1 = port.snapshot()
        val snap2 = port.peek()
        assertEquals(snap1, snap2)

        val result = port.typeAt(100, 200, "hello")
        assertTrue(result is MiraActionResult.Ok)
        assertEquals(100, port.tapX)
        assertEquals(200, port.tapY)
        assertEquals("hello", port.typedText)

        port.clearAt(300, 400)
        assertEquals(300, port.tapX)
        assertEquals(400, port.tapY)
        assertTrue(port.cleared)

        assertFalse(port.awaitChange(10))
        assertFalse(port.waitFor("appear", "x", 10))
        assertTrue(port.gesture(emptyList(), 100) is MiraActionResult.Failed)
        assertTrue(port.openLink(null, "http://example.com", null) is MiraActionResult.Failed)
        assertNull(port.checkScreen())
        assertFalse(port.isProtected())
        assertNull(port.frameGrid())
    }
}

class MiraScreenPortModelTest {
    @Test
    fun snapshotDefaults() {
        val snap = MiraScreenSnapshot("s1", "com.app", emptyList(), 1080, 2400)
        assertFalse(snap.changedDuringRead)
        assertFalse(snap.protected)
        assertTrue(snap.hasWindow)
        assertNull(snap.unreadableApp)
        assertNull(snap.captureError)
    }

    @Test
    fun okResultDefaults() {
        val ok = MiraActionResult.Ok()
        assertNull(ok.changed)
        assertNull(ok.appSwitched)
        assertFalse(ok.relocated)
        assertNull(ok.relocatedTo)
        assertFalse(ok.switchedByTarget)
        assertNull(ok.verification)
        assertNull(ok.hint)
        assertFalse(ok.secret)
        assertNull(ok.coveredBy)
    }

    @Test
    fun failedResultRoundTrip() {
        val failed = MiraActionResult.Failed("ref_expired", "element gone")
        assertEquals("ref_expired", failed.reason)
        assertEquals("element gone", failed.hint)
    }
}
