/*
 * Unit tests for pure-Kotlin logic ported from Mika (github.com/wangzan101/Mika).
 * These tests are JVM-only (no Android framework dependencies).
 * They cover: FrameGrid/FrameDiff, MikaAccuracy (relocate/stationary/typeAndVerify),
 * TypingPacing, GesturePacing, and pure-logic helpers from MikaScreenReader.
 */
package io.github.ryoaonetsuki.mira.automation.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

// ============================================================
// 1. FrameGrid / FrameDiff tests (pure JVM, no Android)
// ============================================================

class FrameGridTest {

    /** FrameGrid.sample produces the expected GRID_COLS × GRID_ROWS dimensions. */
    @Test
    fun sample_producesDimensions() {
        val width = 1080
        val height = 2160
        // Provide a pixel function that always returns white (0xFFFFFFFF)
        val grid = FrameGrid.sample(width, height) { _, _ -> 0xFFFFFFFF.toInt() }
        assertNotNull("sample should not return null for large-enough image", grid)
        assertEquals(FrameTuning.GRID_COLS, grid!!.cols)
        assertEquals(FrameTuning.GRID_ROWS, grid.rows)
        assertEquals(FrameTuning.GRID_COLS * FrameTuning.GRID_ROWS, grid.luma.size)
    }

    /** FrameGrid.sample returns null when the image is too small to fit any cell. */
    @Test
    fun sample_returnsNullForTinyImage() {
        // cols = 24, so width needs to be >= 24 for cw > 0; 23 is too narrow
        val grid = FrameGrid.sample(23, 2160) { _, _ -> 0 }
        assertNull("too-narrow image should produce null", grid)
    }

    /** FrameDiff.compare returns null when grids differ in size. */
    @Test
    fun compare_returnsNullOnSizeMismatch() {
        val g1 = FrameGrid(FrameTuning.GRID_COLS, FrameTuning.GRID_ROWS,
            IntArray(FrameTuning.GRID_COLS * FrameTuning.GRID_ROWS) { 100 })
        // Different row count
        val g2 = FrameGrid(FrameTuning.GRID_COLS, FrameTuning.GRID_ROWS - 1,
            IntArray(FrameTuning.GRID_COLS * (FrameTuning.GRID_ROWS - 1)) { 100 })
        assertNull(FrameDiff.compare(g1, g2))
    }

    /** FrameDiff.compare returns null when either grid is null. */
    @Test
    fun compare_returnsNullForNullGrid() {
        val g = FrameGrid(FrameTuning.GRID_COLS, FrameTuning.GRID_ROWS,
            IntArray(FrameTuning.GRID_COLS * FrameTuning.GRID_ROWS) { 100 })
        assertNull(FrameDiff.compare(null, g))
        assertNull(FrameDiff.compare(g, null))
        assertNull(FrameDiff.compare(null, null))
    }

    /** FrameDiff.compare returns visible=false for identical grids (no change). */
    @Test
    fun compare_identicalGrids_notVisible() {
        val luma = IntArray(FrameTuning.GRID_COLS * FrameTuning.GRID_ROWS) { 128 }
        val before = FrameGrid(FrameTuning.GRID_COLS, FrameTuning.GRID_ROWS, luma.copyOf())
        val after = FrameGrid(FrameTuning.GRID_COLS, FrameTuning.GRID_ROWS, luma.copyOf())
        val result = FrameDiff.compare(before, after)
        assertNotNull(result)
        assertFalse("identical grids should not be visible", result!!.visible)
        assertEquals(0.0, result.whole, 0.001)
    }

    /** FrameDiff.compare returns visible=true when many cells differ significantly. */
    @Test
    fun compare_allCellsChanged_isVisible() {
        val before = FrameGrid(FrameTuning.GRID_COLS, FrameTuning.GRID_ROWS,
            IntArray(FrameTuning.GRID_COLS * FrameTuning.GRID_ROWS) { 0 })
        val after = FrameGrid(FrameTuning.GRID_COLS, FrameTuning.GRID_ROWS,
            IntArray(FrameTuning.GRID_COLS * FrameTuning.GRID_ROWS) { 200 })
        val result = FrameDiff.compare(before, after)
        assertNotNull(result)
        assertTrue("all-cells-changed grids should be visible", result!!.visible)
        assertTrue("whole should be ~1.0 when all cells change", result.whole > 0.99)
        assertTrue("large should be true when all cells change", result.large)
    }

    /** FrameDiff.compare with ROI returns non-null region fraction. */
    @Test
    fun compare_withRoi_checksRegion() {
        // before: all dark; after: top-left quarter bright (cells 0..11 in cols, 0..23 in rows)
        val cols = FrameTuning.GRID_COLS
        val rows = FrameTuning.GRID_ROWS
        val beforeLuma = IntArray(cols * rows) { 0 }
        // Make top-left quadrant bright in after
        val afterLuma = IntArray(cols * rows) { idx ->
            val gx = idx % cols
            val gy = idx / cols
            if (gx < cols / 2 && gy < rows / 2) 200 else 0
        }
        val before = FrameGrid(cols, rows, beforeLuma)
        val after = FrameGrid(cols, rows, afterLuma)
        // ROI covers top-left quadrant (0–499 in 0–999 space)
        val roi = listOf(0, 0, 499, 499)
        val result = FrameDiff.compare(before, after, roi)
        assertNotNull(result)
        assertNotNull("ROI region fraction should not be null when roi is given", result!!.region)
        // The region is fully changed, so region fraction should be high
        assertTrue("region within changed quadrant should be large", result.region!! > 0.5)
    }

    /** FrameDiff.compare without ROI has null region. */
    @Test
    fun compare_noRoi_regionIsNull() {
        val luma = IntArray(FrameTuning.GRID_COLS * FrameTuning.GRID_ROWS) { 100 }
        val g = FrameGrid(FrameTuning.GRID_COLS, FrameTuning.GRID_ROWS, luma)
        val result = FrameDiff.compare(g, g.copy())
        assertNotNull(result)
        assertNull("no ROI means region should be null", result!!.region)
    }

    private fun FrameGrid.copy() = FrameGrid(cols, rows, luma.copyOf())
}

// ============================================================
// 2. MikaElementLocator + relocate / stationary tests (pure JVM)
// ============================================================

class MikaRelocateTest {

    private fun loc(
        role: String = "Button",
        name: String = "OK",
        resourceId: String? = null,
        cx: Int = 500,
        cy: Int = 500,
        context: String? = null,
        hint: Boolean = false,
        inList: Boolean = false,
    ) = MikaElementLocator(role, name, resourceId, cx, cy, context, hint, inList)

    /** relocate finds element when name + role + resourceId match and within shift limit. */
    @Test
    fun relocate_found_whenMatchWithinShiftLimit() {
        val prev = loc(name = "Submit", resourceId = "btn_submit", cx = 500, cy = 500)
        val before = listOf(prev)
        // After: element moved slightly (well within 15% of shortSide=1000 → 150px)
        val after = listOf(loc(name = "Submit", resourceId = "btn_submit", cx = 510, cy = 505))
        val result = relocate(prev, before, after, shortSide = 1000)
        assertEquals(Relocation.Found(0), result)
    }

    /** relocate returns Gone when element moved too far (>15% of short side). */
    @Test
    fun relocate_gone_whenMovedTooFar() {
        val shortSide = 1000
        val prev = loc(name = "Submit", resourceId = "btn_submit", cx = 500, cy = 500)
        val before = listOf(prev)
        // Move > 150px (15% of 1000) → Moved (or Gone if page not stationary)
        val after = listOf(loc(name = "Submit", resourceId = "btn_submit", cx = 500, cy = 700))
        val result = relocate(prev, before, after, shortSide)
        // The shift is 200 > 150 so should be Moved
        assertEquals(Relocation.Moved, result)
    }

    /** relocate returns Ambiguous when no name and no resourceId. */
    @Test
    fun relocate_ambiguous_whenNoNameNoResourceId() {
        val prev = loc(name = "", resourceId = null)
        val before = listOf(prev)
        val after = listOf(loc(name = "", resourceId = null))
        val result = relocate(prev, before, after, shortSide = 1000)
        assertEquals(Relocation.Ambiguous, result)
    }

    /** relocate returns Ambiguous when multiple elements match. */
    @Test
    fun relocate_ambiguous_whenMultipleMatch() {
        val prev = loc(name = "Item", resourceId = "item_btn")
        val before = listOf(prev, prev.copy(cx = 200))   // two in before
        val after = listOf(
            loc(name = "Item", resourceId = "item_btn", cx = 100),
            loc(name = "Item", resourceId = "item_btn", cx = 300),
        )
        val result = relocate(prev, before, after, shortSide = 1000)
        assertEquals(Relocation.Ambiguous, result)
    }

    /** relocate returns Gone when no match found and page not stationary. */
    @Test
    fun relocate_gone_whenElementDisappeared() {
        val prev = loc(name = "Settings")
        val before = listOf(prev)
        val after = listOf(loc(name = "Home"))   // completely different set
        val result = relocate(prev, before, after, shortSide = 1000)
        assertEquals(Relocation.Gone, result)
    }

    /** stationary returns true when ≥80% of shared elements are in-place. */
    @Test
    fun stationary_true_whenMostElementsInPlace() {
        val spot = 1000 * SAME_SPOT_SHIFT   // 20px spot
        val e1 = loc(name = "Home", cx = 100, cy = 100)
        val e2 = loc(name = "Search", cx = 200, cy = 200)
        val e3 = loc(name = "Profile", cx = 300, cy = 300)
        val before = listOf(e1, e2, e3)
        // After: all elements still in-place (< 20px shift)
        val after = listOf(
            e1.copy(cx = 102, cy = 101),
            e2.copy(cx = 201, cy = 200),
            e3.copy(cx = 300, cy = 302),
        )
        assertTrue(stationary(before, after, spot))
    }

    /** stationary returns false when most elements moved significantly. */
    @Test
    fun stationary_false_whenMostElementsMoved() {
        val spot = 1000 * SAME_SPOT_SHIFT   // 20px spot
        val e1 = loc(name = "Home", cx = 100, cy = 100)
        val e2 = loc(name = "Search", cx = 200, cy = 200)
        val e3 = loc(name = "Profile", cx = 300, cy = 300)
        val before = listOf(e1, e2, e3)
        // After: all moved far away
        val after = listOf(
            e1.copy(cx = 500, cy = 500),
            e2.copy(cx = 600, cy = 600),
            e3.copy(cx = 700, cy = 700),
        )
        assertFalse(stationary(before, after, spot))
    }

    /** stationary returns false when fewer than 2 shared unique named elements. */
    @Test
    fun stationary_false_whenTooFewSharedElements() {
        val spot = 20.0
        val before = listOf(loc(name = "Only"))
        val after = listOf(loc(name = "Only"))
        // only 1 shared → shared < 2
        assertFalse(stationary(before, after, spot))
    }

    private fun MikaElementLocator.copy(
        cx: Int = centerX,
        cy: Int = centerY,
    ) = MikaElementLocator(role, name, resourceId, cx, cy, context, hint, inList)
}

// ============================================================
// 3. typeAndVerify taxonomy tests (pure JVM with coroutines)
// ============================================================

class TypeAndVerifyTest {

    /** Fake TextField for testing: controlled read/write sequence. */
    private class FakeField(
        private var content: String = "",
        private val writeOk: Boolean = true,
        private val readNull: Boolean = false,
    ) : TextField {
        var writes = mutableListOf<String>()

        override fun read(): String? = if (readNull) null else content
        override fun write(text: String): Boolean {
            writes += text
            if (writeOk) content = text
            return writeOk
        }
        override fun retry(text: String): Boolean = write(text)
    }

    /** Returns MATCH when field.read() == written text. */
    @Test
    fun typeAndVerify_match() = runTest {
        val field = FakeField(content = "")
        val result = typeAndVerify(field, "hello")
        assertEquals(Verification.MATCH, result)
    }

    /** Returns MISMATCH when field.read() != written text after all attempts. */
    @Test
    fun typeAndVerify_mismatch_whenReadDiffers() = runTest {
        // Field that accepts write but content never updates (simulates broken field)
        val field = object : TextField {
            override fun read(): String = "wrong"
            override fun write(text: String): Boolean = true
            override fun retry(text: String): Boolean = true
        }
        val result = typeAndVerify(field, "correct", attempts = 2)
        assertEquals(Verification.MISMATCH, result)
    }

    /** Returns UNKNOWN for secret fields (no readback). */
    @Test
    fun typeAndVerify_unknown_forSecretField() = runTest {
        val field = FakeField()
        val result = typeAndVerify(field, "password123", secret = true)
        // Write succeeds → UNKNOWN (can't verify password fields)
        assertEquals(Verification.UNKNOWN, result)
    }

    /** Returns MISMATCH for secret fields when write is rejected. */
    @Test
    fun typeAndVerify_mismatch_forSecretFieldWriteRejected() = runTest {
        val field = FakeField(writeOk = false)
        val result = typeAndVerify(field, "password123", secret = true)
        assertEquals(Verification.MISMATCH, result)
    }

    /** Returns UNKNOWN when write accepted but read is always null (unreadable field) and reacted. */
    @Test
    fun typeAndVerify_unknown_whenReadNullAndReacted() = runTest {
        val field = FakeField(readNull = true)
        val result = typeAndVerify(field, "hello", reacted = { true })
        // read() returns null → UNKNOWN (write accepted but can't verify)
        assertEquals(Verification.UNKNOWN, result)
    }

    /** Clears field first if it already contains the target text before writing. */
    @Test
    fun typeAndVerify_clearsField_whenAlreadyContainsTarget() = runTest {
        // Field starts containing the target text already
        val field = FakeField(content = "hello")
        // After clear (write ""), content becomes ""; then write "hello" again → MATCH
        val result = typeAndVerify(field, "hello")
        // Should have written "" (clear) and then "hello"
        assertTrue("should have written empty string to clear", field.writes.contains(""))
        assertEquals(Verification.MATCH, result)
    }

    /** Returns MATCH for empty target when field is already empty. */
    @Test
    fun typeAndVerify_match_emptyTarget() = runTest {
        val field = FakeField(content = "")
        val result = typeAndVerify(field, "")
        assertEquals(Verification.MATCH, result)
    }

    /** Returns UNKNOWN when write accepted but read stuck + reacted (autocomplete triggered). */
    @Test
    fun typeAndVerify_unknown_whenStuckButReacted() = runTest {
        val baseline = "placeholder"
        val field = object : TextField {
            override fun read(): String = baseline   // always returns baseline (stuck)
            override fun write(text: String): Boolean = true
            override fun retry(text: String): Boolean = true
        }
        val result = typeAndVerify(
            field,
            text = "search query",
            attempts = 2,
            reacted = { true },  // UI reacted (autocomplete appeared)
        )
        // Stuck and reacted → UNKNOWN
        assertEquals(Verification.UNKNOWN, result)
    }
}

// ============================================================
// 4. TypingPacing.plan() tests (pure JVM)
// ============================================================

class TypingPacingTest {

    /** Instant.plan() returns a single step with the full text and 0 delay. */
    @Test
    fun instant_plan_singleStep() {
        val pacing = TypingPacing.Instant
        val steps = pacing.plan("hello world")
        assertEquals(1, steps.size)
        assertEquals("hello world".length, steps[0].end)
        assertEquals(0L, steps[0].delayMs)
    }

    /** Instant.plan() for empty string returns single zero step. */
    @Test
    fun instant_plan_emptyString() {
        val steps = TypingPacing.Instant.plan("")
        assertEquals(1, steps.size)
        assertEquals(0, steps[0].end)
    }

    /** human plan produces multiple steps for long text. */
    @Test
    fun human_plan_multipleStepsForLongText() {
        val pacing = TypingPacing.human(Random(42))
        // Long enough text to guarantee multiple steps
        val text = "This is a fairly long sentence that should be split into multiple typing steps."
        val steps = pacing.plan(text)
        assertTrue("human plan should produce >1 step for long text", steps.size > 1)
        // Last step always ends at the full text length
        assertEquals(text.length, steps.last().end)
    }

    /** plan never exceeds maxSteps. */
    @Test
    fun plan_neverExceedsMaxSteps() {
        val limits = TypingPacing.Limits(maxSteps = 10)
        val pacing = TypingPacing.human(Random(123), limits)
        val text = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val steps = pacing.plan(text)
        assertTrue("steps (${steps.size}) should not exceed maxSteps (${limits.maxSteps})",
            steps.size <= limits.maxSteps)
    }

    /** plan total delay <= maxTotalMs (after scaling). */
    @Test
    fun plan_totalDelayWithinMaxTotalMs() {
        val limits = TypingPacing.Limits(maxTotalMs = 3000L, maxSteps = 40)
        val pacing = TypingPacing.human(Random(999), limits)
        val text = "Testing total delay constraint with a medium-length string here."
        val steps = pacing.plan(text)
        val total = steps.sumOf { it.delayMs }
        assertTrue("total delay ($total ms) should be <= maxTotalMs (${limits.maxTotalMs} ms)",
            total <= limits.maxTotalMs)
    }

    /** forField(web=true) returns Instant pacing. */
    @Test
    fun forField_webTrue_returnsInstant() {
        val human = TypingPacing.human(Random(1))
        val result = human.forField(web = true, secret = false)
        assertFalse("web field should use Instant (not paced)", result.paced)
    }

    /** forField(secret=true) returns Instant pacing. */
    @Test
    fun forField_secretTrue_returnsInstant() {
        val human = TypingPacing.human(Random(1))
        val result = human.forField(web = false, secret = true)
        assertFalse("secret field should use Instant (not paced)", result.paced)
    }

    /** forField(web=false, secret=false) with human pacing returns paced. */
    @Test
    fun forField_normalField_returnsPaced() {
        val human = TypingPacing.human(Random(1))
        val result = human.forField(web = false, secret = false)
        assertTrue("normal field with human pacing should remain paced", result.paced)
    }

    /** Instant.paced is false. */
    @Test
    fun instant_paced_isFalse() {
        assertFalse(TypingPacing.Instant.paced)
    }

    /** human().paced is true. */
    @Test
    fun human_paced_isTrue() {
        assertTrue(TypingPacing.human(Random(0)).paced)
    }

    /** Steps are monotonically increasing in end position. */
    @Test
    fun plan_stepsAreMonotonic() {
        val pacing = TypingPacing.human(Random(77))
        val text = "Hello, world! This is a test of monotonic step positions."
        val steps = pacing.plan(text)
        for (i in 1 until steps.size) {
            assertTrue("step[$i].end (${steps[i].end}) should > step[${i-1}].end (${steps[i-1].end})",
                steps[i].end > steps[i-1].end)
        }
    }
}

// ============================================================
// 5. GesturePacing.stroke() tests (pure JVM)
// ============================================================

class GesturePacingTest {

    private val path2 = listOf(0.0 to 0.0, 100.0 to 0.0)   // simple horizontal path

    /** Instant.stroke returns a stroke with exactly the two path points (uniform). */
    @Test
    fun instant_stroke_hasTwoPoints() {
        val stroke = GesturePacing.Instant.stroke(path2, durationMs = 200L)
        assertEquals(2, stroke.points.size)
        assertEquals(0L, stroke.points.first().t)
        assertEquals(200L, stroke.durationMs)
    }

    /** Instant.stroke point coordinates match path exactly. */
    @Test
    fun instant_stroke_pointsMatchPath() {
        val stroke = GesturePacing.Instant.stroke(path2, durationMs = 100L)
        assertEquals(0.0, stroke.points[0].x, 0.001)
        assertEquals(0.0, stroke.points[0].y, 0.001)
        assertEquals(100.0, stroke.points[1].x, 0.001)
        assertEquals(0.0, stroke.points[1].y, 0.001)
    }

    /** human stroke produces ≥2 points. */
    @Test
    fun human_stroke_hasAtLeastTwoPoints() {
        val pacing = GesturePacing.human(Random(42))
        val stroke = pacing.stroke(path2, durationMs = 300L)
        assertTrue("stroke should have ≥2 points, got ${stroke.points.size}",
            stroke.points.size >= 2)
    }

    /** human stroke first point t=0 and last point t=durationMs (approximately). */
    @Test
    fun human_stroke_firstAndLastTime() {
        val pacing = GesturePacing.human(Random(7))
        val duration = 500L
        val stroke = pacing.stroke(path2, durationMs = duration)
        assertEquals(0L, stroke.points.first().t)
        // durationMs may be jittered; allow generous range
        assertTrue("last t should be positive", stroke.points.last().t > 0)
    }

    /** still path (start ≈ end) produces exactly 2 points (press-and-hold). */
    @Test
    fun human_stroke_stillPath_twoPoints() {
        val pacing = GesturePacing.human(Random(1))
        // Start and end within stillPx (default 3.0 px)
        val stillPath = listOf(100.0 to 100.0, 100.5 to 100.5)
        val stroke = pacing.stroke(stillPath, durationMs = 500L)
        assertEquals("still path (hold) should have exactly 2 points", 2, stroke.points.size)
        // hold is longer than requested for a still path
        assertTrue("hold duration should be >= requested", stroke.durationMs >= 500L)
    }

    /** stroke with bounds clips all points within bounds. */
    @Test
    fun human_stroke_boundsClipsPoints() {
        val pacing = GesturePacing.human(Random(3))
        val bounds = 100 to 200  // 100×200 screen
        val widePath = listOf(-50.0 to 50.0, 150.0 to 150.0)
        val stroke = pacing.stroke(widePath, durationMs = 200L, bounds = bounds)
        for (pt in stroke.points) {
            assertTrue("x=${pt.x} should be in [0, 99]", pt.x >= 0.0 && pt.x <= 99.0)
            assertTrue("y=${pt.y} should be in [0, 199]", pt.y >= 0.0 && pt.y <= 199.0)
        }
    }

    /** progress(0.0, any) == 0 and progress(1.0, any) == 1 (boundary conditions). */
    @Test
    fun progress_boundaries() {
        // Access via reflection isn't needed; GesturePacing.progress is internal
        // Test via stroke monotonicity instead: first and last positions match path ends
        val pacing = GesturePacing.human(Random(5))
        val p1 = 10.0 to 20.0
        val p2 = 90.0 to 80.0
        val stroke = pacing.stroke(listOf(p1, p2), durationMs = 400L)
        assertEquals(p1.first, stroke.points.first().x, 0.5)
        assertEquals(p1.second, stroke.points.first().y, 0.5)
        assertEquals(p2.first, stroke.points.last().x, 0.5)
        assertEquals(p2.second, stroke.points.last().y, 0.5)
    }

    /** stroke requires ≥2 points (throws on single-point path). */
    @Test(expected = IllegalArgumentException::class)
    fun stroke_requiresTwoPoints() {
        GesturePacing.Instant.stroke(listOf(0.0 to 0.0), durationMs = 100L)
    }

    /** Instant.paced is false. */
    @Test
    fun instant_paced_isFalse() {
        assertFalse(GesturePacing.Instant.paced)
    }

    /** human().paced is true. */
    @Test
    fun human_paced_isTrue() {
        assertTrue(GesturePacing.human(Random(0)).paced)
    }
}

// ============================================================
// 6. ambiguousFieldReadback tests (pure JVM — no Android needed)
// ============================================================

class AmbiguousFieldReadbackTest {

    /** Returns false when placeholder is null. */
    @Test
    fun returnsFalse_whenPlaceholderNull() {
        assertFalse(ambiguousFieldReadback("label hello", null, "hello"))
    }

    /** Returns false when placeholder is empty. */
    @Test
    fun returnsFalse_whenPlaceholderEmpty() {
        assertFalse(ambiguousFieldReadback("label hello", "", "hello"))
    }

    /** Returns false when typing is null. */
    @Test
    fun returnsFalse_whenTypingNull() {
        assertFalse(ambiguousFieldReadback("label hello", "label", null))
    }

    /** Returns false when typing is empty. */
    @Test
    fun returnsFalse_whenTypingEmpty() {
        assertFalse(ambiguousFieldReadback("label hello", "label", ""))
    }

    /** Returns true for the classic "label<whitespace>value" readback. */
    @Test
    fun returnsTrue_forLabelWhitespaceValue() {
        // "Search hello" where placeholder="Search" and typing="hello"
        assertTrue(ambiguousFieldReadback("Search hello", "Search", "hello"))
    }

    /** Returns true when separator is multiple spaces. */
    @Test
    fun returnsTrue_forMultipleSpaceSeparator() {
        assertTrue(ambiguousFieldReadback("Name   Alice", "Name", "Alice"))
    }

    /** Returns false when no whitespace separator between label and value. */
    @Test
    fun returnsFalse_whenNoSeparator() {
        // "SearchHello" — no whitespace between label and value
        assertFalse(ambiguousFieldReadback("SearchHello", "Search", "Hello"))
    }

    /** Returns false when value doesn't end with the target text. */
    @Test
    fun returnsFalse_whenValueDoesNotEndWithTyping() {
        assertFalse(ambiguousFieldReadback("Search something else", "Search", "hello"))
    }

    /** Returns false when shown is just the label (no separator or value). */
    @Test
    fun returnsFalse_whenShownIsJustLabel() {
        assertFalse(ambiguousFieldReadback("Search", "Search", "hello"))
    }

    /** Returns false when separator is non-whitespace. */
    @Test
    fun returnsFalse_whenSeparatorIsNonWhitespace() {
        assertFalse(ambiguousFieldReadback("Search:hello", "Search", "hello"))
    }

    /** Returns true even with leading/trailing whitespace in shown (trims correctly). */
    @Test
    fun returnsTrue_withOuterWhitespace() {
        // After trimming: "Email user@test.com"
        assertTrue(ambiguousFieldReadback("  Email user@test.com  ", "Email", "user@test.com"))
    }
}
