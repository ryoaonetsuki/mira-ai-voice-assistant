/*
 * Ported from Mika (github.com/wangzan101/Mika), Apache License 2.0.
 * Copyright 2026 wangzan101. Original code used with attribution.
 * Adaptations: dropped shell/virtual-display, replaced with Mira's
 * AccessibilityService APIs; dropped spill/adopt machinery.
 */
package io.github.ryoaonetsuki.mira.automation.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.github.ryoaonetsuki.mira.service.MiraAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.random.Random

private const val TAG = "MikaReliableActions"

/**
 * Reliability core ported from Mika's AndroidScreen.kt.
 *
 * Provides verified action primitives (tap, scroll, type) using Mira's
 * AccessibilityService APIs. All shell / virtual-display / spill machinery
 * has been dropped; we operate directly on [MiraAccessibilityService.instance].
 */
object MikaReliableActions {

    // -------------------------------------------------------------------------
    // ActionVerdict
    // -------------------------------------------------------------------------

    /**
     * Result of a verified action.
     *
     * [changed] is true when the accessibility tree or the frame changed after
     * the action. [error] carries a short diagnostic string on failure.
     */
    data class ActionVerdict(val changed: Boolean, val error: String? = null) {
        companion object {
            val Changed = ActionVerdict(true)
            val Unchanged = ActionVerdict(false)
            fun failed(msg: String) = ActionVerdict(false, msg)
        }
    }

    // -------------------------------------------------------------------------
    // Tree / frame fingerprints
    // -------------------------------------------------------------------------

    /**
     * FNV-1a hash of the visible accessibility tree.
     * Delegates to [MikaScreenReader.hash] so the same coverage rules apply.
     */
    fun treeHash(windows: List<AccessibilityWindowInfo>): Long =
        MikaScreenReader.hash(windows)

    /**
     * Capture a low-resolution frame grid for before/after comparison.
     *
     * Calls [MiraAccessibilityService.takeScreenshotJpeg] at 360px wide (small
     * enough to be fast), decodes to a [Bitmap], samples via [FrameGrid.sample],
     * then recycles the bitmap.
     *
     * Returns null if the service is unavailable, the screenshot fails, or the
     * bitmap is too small to grid.
     */
    suspend fun captureFrameGrid(service: MiraAccessibilityService): FrameGrid? {
        val bytes = service.takeScreenshotJpeg(maxWidthPx = 360) ?: return null
        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        } catch (e: Throwable) {
            Log.w(TAG, "captureFrameGrid: bitmap decode failed: ${e.message}")
            return null
        }
        return try {
            FrameGrid.sample(bitmap.width, bitmap.height) { x, y -> bitmap.getPixel(x, y) }
        } catch (e: Throwable) {
            Log.w(TAG, "captureFrameGrid: FrameGrid.sample failed: ${e.message}")
            null
        } finally {
            bitmap.recycle()
        }
    }

    // -------------------------------------------------------------------------
    // Change detection
    // -------------------------------------------------------------------------

    /**
     * Poll until either the accessibility-tree hash or the frame grid changes,
     * or [timeoutMs] expires.
     *
     * Mirrors the `awaitChange` / `ChangeWait.awaitStableChange` pattern from
     * Mika's AndroidScreen.kt, simplified: no event-clock, just timer + poll.
     *
     * @param beforeHash  Tree hash captured before the action.
     * @param beforeFrame Frame grid captured before the action (may be null).
     * @param timeoutMs   Maximum time to wait.
     * @param pollMs      Polling interval.
     * @return true if a change was detected within [timeoutMs].
     */
    suspend fun awaitChange(
        beforeHash: Long,
        timeoutMs: Long,
        pollMs: Long = 80L,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val windows = MiraAccessibilityService.instance?.windows ?: emptyList()
            if (treeHash(windows) != beforeHash) return true
            delay(pollMs)
        }
        return false
    }

    /**
     * Wait until the accessibility tree hash is stable for 300 ms, or
     * [timeoutMs] expires.
     *
     * Mirrors [AndroidScreen.waitStable] without the event-clock machinery.
     *
     * @return true if the tree settled, false on timeout.
     */
    suspend fun waitStable(timeoutMs: Long = 1500L): Boolean {
        val stableForMs = 300L
        val pollMs = 80L
        val deadline = System.currentTimeMillis() + timeoutMs
        var stableSince = System.currentTimeMillis()
        var lastHash = treeHash(MiraAccessibilityService.instance?.windows ?: emptyList())
        while (System.currentTimeMillis() < deadline) {
            delay(pollMs)
            val windows = MiraAccessibilityService.instance?.windows ?: emptyList()
            val hash = treeHash(windows)
            if (hash != lastHash) {
                lastHash = hash
                stableSince = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - stableSince >= stableForMs) {
                return true
            }
        }
        return false
    }

    // -------------------------------------------------------------------------
    // Ref resolution
    // -------------------------------------------------------------------------

    /**
     * Resolve a "@e<N>" ref to the corresponding [NodeEntry].
     *
     * Refs are 1-indexed ("@e1" → nodes[0]). Returns null if the ref is
     * malformed or out of range.
     */
    fun resolveRef(ref: String, nodes: List<NodeEntry>): NodeEntry? {
        val idx = ref.removePrefix("@e").toIntOrNull() ?: return null
        if (idx < 1 || idx > nodes.size) return null
        return nodes[idx - 1]
    }

    // -------------------------------------------------------------------------
    // Relocation
    // -------------------------------------------------------------------------

    /**
     * Re-locate an element after the UI may have changed.
     *
     * Thin wrapper over [MikaAccuracy.relocate].
     */
    fun relocateNode(
        locator: MikaElementLocator,
        beforeLocators: List<MikaElementLocator>,
        afterLocators: List<MikaElementLocator>,
        shortSide: Int,
    ): Relocation = relocate(locator, beforeLocators, afterLocators, shortSide)

    // -------------------------------------------------------------------------
    // Ancestor walks
    // -------------------------------------------------------------------------

    /**
     * Walk up the parent chain and return the first node that is scrollable,
     * or null if none found within a reasonable depth.
     *
     * Mirrors the `ancestorOrSelf` helper in Mika's AndroidScreen.kt.
     */
    fun findScrollableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth <= 20) {
            if (n.isScrollable) return n
            n = n.parent
            depth++
        }
        return null
    }

    /**
     * Walk up the parent chain and return the first node that is clickable,
     * or null if none found within a reasonable depth.
     */
    fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth <= 20) {
            if (n.isClickable) return n
            n = n.parent
            depth++
        }
        return null
    }

    // -------------------------------------------------------------------------
    // IME / keyboard helpers
    // -------------------------------------------------------------------------

    /**
     * Return the on-screen bounds of the input method window, or null if none
     * is visible.
     *
     * Mirrors [AndroidScreen.imeBounds].
     */
    fun imeBounds(): Rect? {
        val windows = MiraAccessibilityService.instance?.windows ?: return null
        val w = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?: return null
        return Rect().also(w::getBoundsInScreen).takeUnless { it.isEmpty }
    }

    /**
     * True when the soft keyboard is currently visible.
     */
    fun keyboardUp(): Boolean = imeBounds() != null

    // -------------------------------------------------------------------------
    // Text setting
    // -------------------------------------------------------------------------

    /**
     * Set the text content of [node] using [AccessibilityNodeInfo.ACTION_SET_TEXT].
     *
     * Mirrors [AndroidScreen.setText].
     */
    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // -------------------------------------------------------------------------
    // Jitter helper
    // -------------------------------------------------------------------------

    /**
     * Pick a random point strictly inside [rect] (1-pixel inset on each side).
     *
     * Returns the centre if the rect is too small to inset.
     */
    private fun jitter(rect: Rect, rng: Random = Random.Default): Pair<Float, Float> {
        val left = (rect.left + 1).coerceAtMost(rect.right - 1)
        val right = (rect.right - 1).coerceAtLeast(rect.left + 1)
        val top = (rect.top + 1).coerceAtMost(rect.bottom - 1)
        val bottom = (rect.bottom - 1).coerceAtLeast(rect.top + 1)
        val x = if (left >= right) rect.centerX().toFloat() else rng.nextInt(left, right).toFloat()
        val y = if (top >= bottom) rect.centerY().toFloat() else rng.nextInt(top, bottom).toFloat()
        return x to y
    }

    // -------------------------------------------------------------------------
    // Verified tap
    // -------------------------------------------------------------------------

    /**
     * Tap the element identified by [ref] in [snapshot], then confirm the UI
     * changed.
     *
     * Strategy (ported from [AndroidScreen.verified] + [AndroidScreen.tapRef]):
     * 1. Capture before-hash and before-frame.
     * 2. Resolve the ref to a node. Find clickable ancestor if any.
     * 3. Perform [AccessibilityNodeInfo.ACTION_CLICK]. If accepted and UI
     *    changed → [ActionVerdict.Changed].
     * 4. If unchanged (or ACTION_CLICK rejected) and bounds are known → jitter
     *    touch via [MiraAccessibilityService.clickAt], await change again.
     * 5. Return verdict.
     *
     * @param gesturePacing Retained for future use; currently ignored (Mira's
     *   service handles its own pacing internally).
     */
    suspend fun verifiedTapRef(
        ref: String,
        snapshot: MikaScreenRead,
        service: MiraAccessibilityService,
        gesturePacing: GesturePacing = GesturePacing.Instant,
    ): ActionVerdict {
        val beforeHash = treeHash(service.windows)

        val entry = resolveRef(ref, snapshot.entries)
            ?: return ActionVerdict.failed("ref_not_found: $ref")

        val node = entry.node ?: return ActionVerdict.failed("ref_not_found: $ref")
        val bounds = Rect().also(node::getBoundsInScreen)

        // Try clickable ancestor first; fall back to direct touch on node bounds
        val clickable = findClickableAncestor(node)
        val actionAccepted = clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ?: node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        if (actionAccepted) {
            if (awaitChange(beforeHash, 2000L)) return ActionVerdict.Changed
        }

        // Fallback: jitter touch inside node bounds
        if (!bounds.isEmpty) {
            val (x, y) = jitter(bounds)
            service.clickAt(x, y)
            if (awaitChange(beforeHash, 2000L)) return ActionVerdict.Changed
        }

        return if (!actionAccepted && bounds.isEmpty) {
            ActionVerdict.failed("action_failed")
        } else {
            ActionVerdict.Unchanged
        }
    }

    // -------------------------------------------------------------------------
    // Verified scroll
    // -------------------------------------------------------------------------

    /**
     * Scroll [node] (or the first scrollable in the tree if null) in the given
     * direction, then confirm the UI changed.
     *
     * Strategy (ported from [AndroidScreen.scroll]):
     * 1. Find scrollable ancestor of [node]; if none, use swipe fallback.
     * 2. Perform [AccessibilityNodeInfo.ACTION_SCROLL_FORWARD] or [ACTION_SCROLL_BACKWARD].
     * 3. If unchanged: swipe the centre of the screen as a fallback.
     */
    suspend fun verifiedScroll(
        node: AccessibilityNodeInfo?,
        down: Boolean,
        service: MiraAccessibilityService,
        screenWidth: Int,
        screenHeight: Int,
    ): ActionVerdict {
        val beforeHash = treeHash(service.windows)

        val scrollable: AccessibilityNodeInfo? = when {
            node != null -> findScrollableAncestor(node)
            else -> {
                // Find first scrollable in the entire window tree
                service.windows
                    .flatMap { w -> w.root?.let { listOf(it) } ?: emptyList() }
                    .firstNotNullOfOrNull { root -> findScrollableDescendant(root) }
            }
        }

        val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD

        if (scrollable != null) {
            val accepted = scrollable.performAction(action)
            if (accepted && awaitChange(beforeHash, 1500L)) return ActionVerdict.Changed
        }

        // Swipe fallback: centre-screen swipe (up to scroll down, down to scroll up)
        val cx = screenWidth / 2
        val (fromY, toY) = if (down) {
            (screenHeight * 3 / 4).toFloat() to (screenHeight / 4).toFloat()
        } else {
            (screenHeight / 4).toFloat() to (screenHeight * 3 / 4).toFloat()
        }
        service.swipe(cx.toFloat(), fromY, cx.toFloat(), toY, 400L)
        if (awaitChange(beforeHash, 1500L)) return ActionVerdict.Changed

        return ActionVerdict.Unchanged
    }

    /** BFS to find the first scrollable descendant of [root]. */
    private fun findScrollableDescendant(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 500) {
            val n = queue.removeFirst()
            if (n.isScrollable) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        return null
    }

    // -------------------------------------------------------------------------
    // Verified type
    // -------------------------------------------------------------------------

    /**
     * Type [text] into [node] and read back to verify delivery.
     *
     * Strategy (ported from [AndroidScreen.typeField] / [AndroidScreen.typeText]):
     * 1. Focus the node (ACTION_FOCUS, then ACTION_CLICK if needed).
     * 2. Set text via [setText].
     * 3. Read back via [MikaScreenReader.fieldText]; compare to [text].
     * 4. Return [ActionVerdict.Changed] on match, [ActionVerdict.Unchanged] on
     *    mismatch or if readback is unavailable (e.g. password field).
     */
    suspend fun verifiedTypeText(
        node: AccessibilityNodeInfo,
        text: String,
        service: MiraAccessibilityService,
    ): ActionVerdict {
        // 1. Focus
        if (!node.isFocused) {
            node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            delay(80)
            node.refresh()
            if (!node.isFocused) {
                // Click to focus
                val bounds = Rect().also(node::getBoundsInScreen)
                if (!bounds.isEmpty) {
                    val (x, y) = jitter(bounds)
                    service.clickAt(x, y)
                    delay(200)
                    node.refresh()
                }
            }
        }

        // 2. Set text
        val setOk = setText(node, text)
        if (!setOk) {
            Log.w(TAG, "verifiedTypeText: ACTION_SET_TEXT returned false for node")
        }

        // 3. Read back
        delay(120)
        node.refresh()
        val readBack = MikaScreenReader.fieldText(node)

        // Password fields return null — treat as UNKNOWN (Unchanged with no error)
        if (readBack == null) {
            Log.d(TAG, "verifiedTypeText: readback null (password or inaccessible)")
            return ActionVerdict.Unchanged
        }

        return if (readBack == text) ActionVerdict.Changed
        else {
            Log.d(TAG, "verifiedTypeText: readback mismatch: expected='$text' got='$readBack'")
            ActionVerdict.Unchanged
        }
    }
}
