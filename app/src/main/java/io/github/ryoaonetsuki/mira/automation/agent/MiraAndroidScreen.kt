/*
 * Ported from Mika (github.com/wangzan101/Mika), Apache License 2.0.
 * Copyright 2026 wangzan101. Original code used with attribution.
 * Adaptations: renamed package, dropped shell/virtual-display/multi-profile/spill,
 * built directly on MiraAccessibilityService + ported Mika modules.
 */
package io.github.ryoaonetsuki.mira.automation.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.github.ryoaonetsuki.mira.service.MiraAccessibilityService
import kotlinx.coroutines.delay
import kotlin.math.min

class MiraAndroidScreen(private val service: MiraAccessibilityService) : MiraScreenPort {

    private var lastRead: ScreenRead? = null
    private var lastSnapshotId: String? = null
    private var snapshotCounter = 0
    private var protectedFlag = false
    private var lastWidth = 0
    private var lastHeight = 0

    private fun dimensions(): Pair<Int, Int> {
        val metrics = service.resources?.displayMetrics
        if (metrics != null) {
            lastWidth = metrics.widthPixels
            lastHeight = metrics.heightPixels
            return lastWidth to lastHeight
        }
        return if (lastWidth > 0 && lastHeight > 0) lastWidth to lastHeight else (0 to 0)
    }

    private fun nextSnapshotId(): String {
        snapshotCounter++
        return "s$snapshotCounter"
    }

    /**
     * Resolve a ref against the last read. If the snapshot id is stale or the
     * node can't be refreshed, re-read the screen and relocate via
     * [relocate]. On success, [lastRead]/[lastSnapshotId] are
     * replaced with the fresh read and a new snapshot id.
     */
    private fun resolveLive(ref: String, snapshotId: String): Pair<AccessibilityNodeInfo, ScreenRead>? {
        if (snapshotId != lastSnapshotId) return null
        val read = lastRead ?: return null
        val entry = MikaReliableActions.resolveRef(ref, read.entries) ?: return null
        val node = entry.node ?: return null
        if (node.refresh()) return node to read
        val (w, h) = dimensions()
        val fresh = MikaScreenReader.read(service.windows, w, h)
        val beforeLocators = read.entries.map { it.locator }
        val afterLocators = fresh.entries.map { it.locator }
        val shortSide = min(w, h)
        val relocation = relocate(entry.locator, beforeLocators, afterLocators, shortSide)
        if (relocation !is Relocation.Found) return null
        val freshEntry = fresh.entries[relocation.index]
        val freshNode = freshEntry.node ?: return null
        lastRead = fresh
        lastSnapshotId = nextSnapshotId()
        return freshNode to fresh
    }

    override suspend fun snapshot(): MiraScreenSnapshot {
        val (w, h) = dimensions()
        if (w == 0 || h == 0) {
            return MiraScreenSnapshot(
                snapshotId = nextSnapshotId(),
                packageName = null,
                elements = emptyList(),
                width = 0,
                height = 0,
                protected = protectedFlag,
                hasWindow = false,
                captureError = "no_dimensions",
            )
        }
        val read = MikaScreenReader.read(service.windows, w, h)
        lastRead = read
        lastSnapshotId = nextSnapshotId()
        return MiraScreenSnapshot(
            snapshotId = lastSnapshotId!!,
            packageName = read.topPackage,
            elements = read.entries.map { it.element },
            width = w,
            height = h,
            protected = protectedFlag,
            hasWindow = read.hasAppWindow,
        )
    }

    override suspend fun peek(): MiraScreenSnapshot {
        val (w, h) = dimensions()
        val read = MikaScreenReader.read(service.windows, w, h)
        val id = lastSnapshotId ?: nextSnapshotId()
        if (lastSnapshotId == null) {
            lastRead = read
            lastSnapshotId = id
        }
        return MiraScreenSnapshot(
            snapshotId = id,
            packageName = read.topPackage,
            elements = read.entries.map { it.element },
            width = w,
            height = h,
            protected = protectedFlag,
            hasWindow = read.hasAppWindow,
        )
    }

    override suspend fun screenshotPng(): ByteArray? {
        val bytes = service.takeScreenshotJpeg() ?: run {
            protectedFlag = service.windows.isNotEmpty()
            return null
        }
        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: run {
                protectedFlag = service.windows.isNotEmpty()
                return null
            }
        } catch (e: Throwable) {
            protectedFlag = service.windows.isNotEmpty()
            return null
        }
        val out = java.io.ByteArrayOutputStream()
        val ok = bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        if (!ok) {
            protectedFlag = service.windows.isNotEmpty()
            return null
        }
        protectedFlag = false
        return out.toByteArray()
    }

    override suspend fun tapRef(ref: String, snapshotId: String): MiraActionResult {
        val (node, freshRead) = resolveLive(ref, snapshotId) ?: return MiraActionResult.Failed("ref_expired")
        val freshIndex = freshRead.entries.indexOfFirst { it.node === node }
        val freshRef = if (freshIndex >= 0) "@e${freshIndex + 1}" else ref
        val verdict = MikaReliableActions.verifiedTapRef(freshRef, freshRead, service)
        return verdictToResult(verdict)
    }

    override suspend fun tapXY(x: Int, y: Int): MiraActionResult {
        val (w, h) = dimensions()
        if (w == 0 || h == 0) return MiraActionResult.Failed("no_dimensions")
        val beforeHash = MikaReliableActions.treeHash(service.windows)
        val beforeFrame = MikaReliableActions.captureFrameGrid(service)
        val ok = service.clickAt(x.toFloat(), y.toFloat())
        if (!ok) return MiraActionResult.Failed("tap_failed")
        val changed = MikaReliableActions.awaitChange(beforeHash, beforeFrame, 2000L)
        return MiraActionResult.Ok(changed = changed)
    }

    override suspend fun typeText(ref: String?, snapshotId: String?, text: String): MiraActionResult {
        val node = when {
            ref != null -> {
                resolveLive(ref, snapshotId ?: "")?.first ?: return MiraActionResult.Failed("ref_expired")
            }
            else -> {
                service.windows
                    .flatMap { w -> w.root?.let { listOf(it) } ?: emptyList() }
                    .firstNotNullOfOrNull { root -> findFocusedEditable(root) }
                    ?: return MiraActionResult.Failed("no_focused_field")
            }
        } as AccessibilityNodeInfo
        val field = object : TextField {
            override fun read(): String? = MikaScreenReader.fieldText(node)
            override fun write(text: String): Boolean = MikaReliableActions.setText(node, text)
            override fun retry(text: String): Boolean = write(text)
        }
        val verification = typeAndVerify(field, text, secret = node.isPassword)
        return when (verification) {
            Verification.MATCH ->
                MiraActionResult.Ok(changed = true, verification = Verification.MATCH)
            Verification.UNKNOWN ->
                MiraActionResult.Ok(changed = null, verification = Verification.UNKNOWN)
            Verification.MISMATCH ->
                MiraActionResult.Failed("type_mismatch")
        }
    }

    private fun findFocusedEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isFocused && MikaScreenReader.canSetText(root)) return root
        for (i in 0 until root.childCount) {
            root.getChild(i)?.let { found ->
                val result = findFocusedEditable(found)
                if (result != null) return result
            }
        }
        return null
    }

    override suspend fun clearText(ref: String?, snapshotId: String?): MiraActionResult {
        val node = when {
            ref != null -> {
                resolveLive(ref, snapshotId ?: "")?.first ?: return MiraActionResult.Failed("ref_expired")
            }
            else -> return MiraActionResult.Failed("no_focused_field")
        } as AccessibilityNodeInfo
        if (!node.isFocused) {
            node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
            node.refresh()
        }
        val ok = MikaReliableActions.setText(node, "")
        return if (ok) MiraActionResult.Ok(changed = true) else MiraActionResult.Failed("clear_failed")
    }

    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): MiraActionResult {
        val node = if (ref != null) {
            resolveLive(ref, snapshotId ?: "")?.first
        } else null
        val (w, h) = dimensions()
        if (w == 0 || h == 0) return MiraActionResult.Failed("no_dimensions")
        val verdict = MikaReliableActions.verifiedScroll(node, down, service, w, h)
        return verdictToResult(verdict)
    }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): MiraActionResult {
        val beforeHash = MikaReliableActions.treeHash(service.windows)
        val beforeFrame = MikaReliableActions.captureFrameGrid(service)
        val ok = service.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), durationMs)
        if (!ok) return MiraActionResult.Failed("swipe_failed")
        val changed = MikaReliableActions.awaitChange(beforeHash, beforeFrame, 1500L)
        return MiraActionResult.Ok(changed = changed)
    }

    override suspend fun back(): MiraActionResult {
        val ok = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        return if (ok) MiraActionResult.Ok(changed = null) else MiraActionResult.Failed("back_failed")
    }

    override suspend fun pressKey(keyCode: Int): MiraActionResult {
        return when (keyCode) {
            android.view.KeyEvent.KEYCODE_BACK -> back()
            android.view.KeyEvent.KEYCODE_HOME -> {
                val ok = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                if (ok) MiraActionResult.Ok(changed = null) else MiraActionResult.Failed("key_failed")
            }
            android.view.KeyEvent.KEYCODE_APP_SWITCH -> {
                val ok = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
                if (ok) MiraActionResult.Ok(changed = null) else MiraActionResult.Failed("key_failed")
            }
            android.view.KeyEvent.KEYCODE_ENTER -> {
                val ok = service.pressKey("enter")
                if (ok) MiraActionResult.Ok(changed = null) else MiraActionResult.Failed("key_failed")
            }
            else -> MiraActionResult.Failed("unsupported_key")
        }
    }

    override suspend fun openApp(packageName: String, cold: Boolean): MiraActionResult {
        if (packageName == "com.google.android.apps.nexuslauncher") {
            val ok = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            return if (ok) MiraActionResult.Ok(changed = null) else MiraActionResult.Failed("back_failed")
        }
        val launchIntent = service.packageManager.getLaunchIntentForPackage(packageName) ?: return MiraActionResult.Failed("no_launch_intent")
        launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (cold) launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        service.startActivity(launchIntent)
        val deadline = System.currentTimeMillis() + 3000L
        while (System.currentTimeMillis() < deadline) {
            val top = MikaScreenReader.topPackage(service.windows)
            if (top == packageName) return MiraActionResult.Ok(appSwitched = top)
            delay(100L)
        }
        return MiraActionResult.Failed("open_failed")
    }

    override suspend fun openLink(action: String?, uri: String?, packageName: String?): MiraActionResult {
        if (uri.isNullOrBlank()) return MiraActionResult.Failed("invalid_link")
        return try {
            val intent = android.content.Intent(action ?: android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri)).apply {
                if (packageName != null) setPackage(packageName)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            service.startActivity(intent)
            MiraActionResult.Ok(changed = null)
        } catch (e: android.content.ActivityNotFoundException) {
            MiraActionResult.Failed("link_unresolved")
        } catch (e: Throwable) {
            MiraActionResult.Failed("invalid_link")
        }
    }

    override suspend fun waitStable(timeoutMs: Long): Boolean = MikaReliableActions.waitStable(timeoutMs)

    override suspend fun awaitChange(timeoutMs: Long): Boolean {
        val beforeHash = MikaReliableActions.treeHash(service.windows)
        val beforeFrame = MikaReliableActions.captureFrameGrid(service)
        return MikaReliableActions.awaitChange(beforeHash, beforeFrame, timeoutMs)
    }

    override suspend fun waitFor(condition: String, value: String?, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val (w, h) = dimensions()
            val read = MikaScreenReader.read(service.windows, w, h)
            when (condition) {
                "appear" -> {
                    if (value != null && read.entries.any { it.element.name.contains(value, ignoreCase = true) }) return true
                }
                "disappear" -> {
                    if (value == null || read.entries.none { it.element.name.contains(value, ignoreCase = true) }) return true
                }
                "activity" -> {
                    if (value != null && read.topPackage == value) return true
                }
                "stable" -> {
                    if (MikaReliableActions.waitStable(300L)) return true
                }
                else -> return false
            }
            delay(100L)
        }
        return false
    }

    override suspend fun checkScreen(): MiraScreenCheck {
        val snap = snapshot()
        return MiraScreenCheck(
            empty = snap.elements.isEmpty(),
            protected = protectedFlag,
            unreadableApp = if (!snap.hasWindow) snap.packageName else null,
        )
    }

    override suspend fun isProtected(): Boolean = protectedFlag

    override suspend fun frameGrid(): FrameGrid? = MikaReliableActions.captureFrameGrid(service)

    companion object {
        fun verdictToResult(v: MikaReliableActions.ActionVerdict): MiraActionResult = when {
            v === MikaReliableActions.ActionVerdict.Changed -> MiraActionResult.Ok(changed = true)
            v === MikaReliableActions.ActionVerdict.Unchanged -> MiraActionResult.Ok(changed = false)
            else -> MiraActionResult.Failed(reason = v.error ?: "action_failed")
        }

        fun isRefExpired(snapshotId: String?, lastSnapshotId: String?): Boolean =
            lastSnapshotId == null || snapshotId != lastSnapshotId

        fun snapshotIdFor(counter: Int): String = "s$counter"

        fun matchWaitFor(condition: String, value: String?, elements: List<MikaUiElement>, topPackage: String?): Boolean =
            when (condition) {
                "appear" -> value != null && elements.any { it.name.contains(value, ignoreCase = true) }
                "disappear" -> value == null || elements.none { it.name.contains(value, ignoreCase = true) }
                "activity" -> value != null && topPackage == value
                else -> false
            }

        /**
         * Pure element matcher: exact name match (case-insensitive) first, then
         * contains-match, else null. Used by the port-first click_text / type_text
         * attempts in MicService.
         */
        fun findTapRef(elements: List<MikaUiElement>, text: String): String? {
            val norm = text.lowercase()
            val exact = elements.firstOrNull { it.name.lowercase() == norm }?.ref
            if (exact != null) return exact
            return elements.firstOrNull { it.name.lowercase().contains(norm) }?.ref
        }
    }
}
