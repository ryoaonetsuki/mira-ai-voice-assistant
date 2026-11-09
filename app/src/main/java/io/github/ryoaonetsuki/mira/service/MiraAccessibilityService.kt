// Accessibility service adapted from Jarvis (github.com/AbuZar-Ansarii/Jarvis), MIT Licence, Copyright (c) 2026 AbuZar Ansarii. Flutter MethodChannel removed; gesture API converted to plain Kotlin.
package io.github.ryoaonetsuki.mira.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.ryoaonetsuki.mira.automation.agent.AutomationSafety
import io.github.ryoaonetsuki.mira.automation.agent.TaskStep
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class MiraAccessibilityService : AccessibilityService() {

    // ------------------------------------------------------------------
    // Autonomous Macro Recording
    // ------------------------------------------------------------------
    @Volatile
    var isRecordingMacro: Boolean = false
        private set

    @Volatile
    private var lastObservedPackage: String? = null

    private val macroEvents = mutableListOf<Map<String, Any>>()

    fun startMacroRecording() {
        synchronized(macroEvents) {
            macroEvents.clear()
        }
        isRecordingMacro = true
    }

    fun stopMacroRecording(): List<Map<String, Any>> {
        isRecordingMacro = false
        synchronized(macroEvents) {
            return ArrayList(macroEvents)
        }
    }

    fun stopMacroRecordingSteps(): List<TaskStep> {
        val events = stopMacroRecording()
        return convertEventsToSteps(events)
    }

    fun getMacroRecordingStatus(): Boolean = isRecordingMacro

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: ""
        if (pkg.isNotEmpty() && pkg != packageName && pkg != OWN_PACKAGE && pkg != "com.android.systemui") {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED
            ) {
                lastObservedPackage = pkg
            }
        }
        if (!isRecordingMacro) return
        if (pkg == packageName || pkg == OWN_PACKAGE) return // Ignore self-interactions inside Mira

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val node = event.source
                val rect = Rect()
                node?.getBoundsInScreen(rect)
                val text = event.text?.joinToString(" ")?.trim() ?: ""
                val desc = event.contentDescription?.toString()?.trim() ?: ""
                val label = if (text.isNotEmpty()) text else desc
                val viewId = node?.viewIdResourceName ?: ""

                val map = mutableMapOf<String, Any>(
                    "type" to "click",
                    "label" to label,
                    "x" to rect.centerX(),
                    "y" to rect.centerY(),
                    "package" to pkg,
                    "viewId" to viewId,
                    "timestamp" to System.currentTimeMillis()
                )
                synchronized(macroEvents) {
                    macroEvents.add(map)
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val text = event.text?.joinToString("") ?: ""
                if (text.isNotEmpty()) {
                    val map = mutableMapOf<String, Any>(
                        "type" to "type",
                        "text" to text,
                        "package" to pkg,
                        "timestamp" to System.currentTimeMillis()
                    )
                    synchronized(macroEvents) {
                        if (macroEvents.isNotEmpty() && macroEvents.last()["type"] == "type") {
                            macroEvents[macroEvents.size - 1] = map
                        } else {
                            macroEvents.add(map)
                        }
                    }
                }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val map = mutableMapOf<String, Any>(
                    "type" to "scroll",
                    "direction" to "down",
                    "package" to pkg,
                    "timestamp" to System.currentTimeMillis()
                )
                synchronized(macroEvents) {
                    val last = macroEvents.lastOrNull()
                    val lastTime = (last?.get("timestamp") as? Long) ?: 0L
                    if (last?.get("type") != "scroll" || (System.currentTimeMillis() - lastTime > 600)) {
                        macroEvents.add(map)
                    }
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg.isNotEmpty() && pkg != "com.android.systemui") {
                    val map = mutableMapOf<String, Any>(
                        "type" to "window",
                        "package" to pkg,
                        "timestamp" to System.currentTimeMillis()
                    )
                    synchronized(macroEvents) {
                        macroEvents.add(map)
                    }
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            val info = serviceInfo ?: android.accessibilityservice.AccessibilityServiceInfo()
            info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            info.feedbackType = android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.notificationTimeout = 100
            info.flags = info.flags or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            serviceInfo = info
        } catch (e: Throwable) {
            Log.w(TAG, "Could not configure AccessibilityServiceInfo: ${e.message}")
        }
        Log.i(TAG, "MiraAccessibilityService connected and ready")
    }

    override fun onInterrupt() {
        Log.w(TAG, "MiraAccessibilityService interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) {
            instance = null
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
        Log.i(TAG, "MiraAccessibilityService destroyed")
    }

    // ------------------------------------------------------------------
    // Screen dump
    // ------------------------------------------------------------------
    /**
     * Recursively traverses the foreground app's window tree and flattens
     * "interesting" nodes into a list of maps.
     *
     * Window selection strategy, in order:
     * 1. [rootInActiveWindow], unless it belongs to Mira itself while
     *    another app is visibly in front.
     * 2. Otherwise the active/focused window from [windows].
     * 3. Otherwise the first non-Mira application window.
     *
     * Our own package is only dumped as a last resort.
     */
    fun dumpScreen(): List<Map<String, Any>> {
        return try {
            val self = packageName.ifEmpty { OWN_PACKAGE }

            val active = rootInActiveWindow
            if (active != null && active.packageName != self) {
                return traverseAll(listOf(active))
            }

            val wins = try {
                windows?.toList() ?: emptyList()
            } catch (_: Exception) {
                emptyList() // windows may throw SecurityException on some OEM builds.
            }

            val foreign = wins.mapNotNull { it.root }
                .filter { it.packageName != self }
            val focused = wins.firstOrNull { it.isActive || it.isFocused }?.root
                ?.takeIf { it.packageName != self }

            val chosen = focused ?: foreign.firstOrNull() ?: active
            if (chosen != null) traverseAll(listOf(chosen)) else emptyList()
        } catch (e: Throwable) {
            Log.e(TAG, "dumpScreen failed", e)
            emptyList()
        }
    }

    private fun traverseAll(
        roots: List<AccessibilityNodeInfo>
    ): List<Map<String, Any>> {
        val out = mutableListOf<Map<String, Any>>()
        var index = 0
        for (root in roots) {
            index = traverse(root, index, out)
        }
        return out
    }

    private fun traverse(
        node: AccessibilityNodeInfo?,
        startIndex: Int,
        out: MutableList<Map<String, Any>>
    ): Int {
        if (node == null) return startIndex
        var index = startIndex

        val rect = Rect()
        node.getBoundsInScreen(rect)

        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val className = node.className?.toString().orEmpty()
        val viewId = node.viewIdResourceName.orEmpty()

        val interactive =
            node.isClickable || node.isEditable || node.isScrollable ||
                node.isCheckable || node.isLongClickable
        val hasLabel = text.isNotBlank() || desc.isNotBlank()
        val onScreen = rect.width() > 0 && rect.height() > 0

        if (node.isVisibleToUser && onScreen && (interactive || hasLabel)) {
            out.add(
                hashMapOf(
                    "index" to index,
                    "text" to text,
                    "contentDescription" to desc,
                    "className" to className,
                    "viewId" to viewId,
                    "isClickable" to node.isClickable,
                    "isEditable" to node.isEditable,
                    "isScrollable" to node.isScrollable,
                    "isCheckable" to node.isCheckable,
                    "isEnabled" to node.isEnabled,
                    "left" to rect.left,
                    "top" to rect.top,
                    "right" to rect.right,
                    "bottom" to rect.bottom,
                    "bounds" to rect,
                    "centerX" to rect.centerX(),
                    "centerY" to rect.centerY()
                )
            )
            index++
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            index = traverse(child, index, out)
        }
        return index
    }

    // ------------------------------------------------------------------
    // Gestures
    // ------------------------------------------------------------------

    /** Short tap at absolute screen coordinates. */
    fun clickAt(x: Float, y: Float): Boolean {
        return try {
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 80L)
            dispatch(GestureDescription.Builder().addStroke(stroke).build())
        } catch (e: Throwable) {
            Log.e(TAG, "clickAt failed", e)
            false
        }
    }

    /** Linear swipe between two points over [durationMs] milliseconds. */
    fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): Boolean {
        return try {
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val clamped = durationMs.coerceIn(50L, 5000L)
            val stroke = GestureDescription.StrokeDescription(path, 0L, clamped)
            dispatch(GestureDescription.Builder().addStroke(stroke).build())
        } catch (e: Throwable) {
            Log.e(TAG, "swipe failed", e)
            false
        }
    }

    /**
     * Scrolls the screen. Direction is the content direction to reveal:
     * "down" reveals content further down (swipes up).
     * Prefers native scroll action on first scrollable node, then gesture fallback.
     */
    fun scroll(direction: String): Boolean {
        return try {
            val dir = direction.lowercase()
            val forward = dir == "down" || dir == "right"
            val vertical = dir == "down" || dir == "up"

            val scrollable = findFirstScrollable(rootInActiveWindow)
            if (scrollable != null) {
                val action = if (forward) {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                } else {
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                }
                if (scrollable.performAction(action)) {
                    return true
                }
            }

            // Gesture fallback
            val metrics = resources.displayMetrics
            val w = metrics.widthPixels.toFloat()
            val h = metrics.heightPixels.toFloat()
            val cx = w / 2f
            val cy = h / 2f
            val dy = h * 0.30f
            val dx = w * 0.30f
            when (dir) {
                "down" -> swipe(cx, cy + dy / 2, cx, cy - dy / 2, 380L)
                "up" -> swipe(cx, cy - dy / 2, cx, cy + dy / 2, 380L)
                "left" -> swipe(cx - dx / 2, cy, cx + dx / 2, cy, 380L)
                "right" -> swipe(cx + dx / 2, cy, cx - dx / 2, cy, 380L)
                else -> {
                    if (vertical) swipe(cx, cy + dy / 2, cx, cy - dy / 2, 380L)
                    else false
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "scroll failed", e)
            false
        }
    }

    private fun findFirstScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val found = findFirstScrollable(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    // ------------------------------------------------------------------
    // Text input
    // ------------------------------------------------------------------

    /**
     * Sets [text] into the currently focused editable node, or the first
     * editable node found on screen. Returns false when no input exists.
     */
    fun typeText(text: String): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            var target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (target == null || !target.isEditable) {
                target = findFirstEditable(root)
            }
            if (target == null) return false

            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Throwable) {
            Log.e(TAG, "typeText failed", e)
            false
        }
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isVisibleToUser && node.isEnabled) return node
        for (i in 0 until node.childCount) {
            val found = findFirstEditable(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    // ------------------------------------------------------------------
    // Global keys
    // ------------------------------------------------------------------

    /** Handles back/home/recents/notifications/quick_settings/power_dialog/lock plus IME enter. */
    fun pressKey(key: String): Boolean {
        return try {
            when (key.lowercase()) {
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "recents", "recent" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                "power_dialog" -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
                "lock" -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                "enter" -> pressImeEnter()
                else -> false
            }
        } catch (e: Throwable) {
            Log.e(TAG, "pressKey failed for key: $key", e)
            false
        }
    }

    private fun pressImeEnter(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return target.performAction(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
        )
    }

    fun getActivePackageName(): String? {
        return try {
            val self = packageName.ifEmpty { OWN_PACKAGE }
            val activePkg = rootInActiveWindow?.packageName?.toString()
            if (!activePkg.isNullOrEmpty() && activePkg != self) {
                return activePkg
            }
            val wins = try {
                windows?.toList() ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            val focusedPkg = wins.firstOrNull { it.isActive || it.isFocused }?.root?.packageName?.toString()
                ?.takeIf { it.isNotEmpty() && it != self }
            val foreignPkg = wins.mapNotNull { it.root?.packageName?.toString() }
                .firstOrNull { it.isNotEmpty() && it != self && it != "com.android.systemui" }
            focusedPkg ?: foreignPkg ?: lastObservedPackage ?: activePkg
        } catch (_: Throwable) {
            lastObservedPackage
        }
    }

    // ------------------------------------------------------------------
    // Screenshot (Android 11+ / API 30+)
    // Ported from Jarvis (github.com/AbuZar-Ansarii/Jarvis, AgentAccessibilityService.kt:419-463),
    // MIT Licence, Copyright (c) 2026 AbuZar Ansarii.
    // Debounce & FLAG_SECURE handling adapted from ClosePaw (github.com/imoonkey/closepaw), Apache-2.0.
    // ------------------------------------------------------------------
    @Volatile
    private var lastScreenshotTimestampMs: Long = 0L

    @Volatile
    private var lastScreenshotErrorCode: Int = 0

    private class ScreenshotCaptureOutcome(val bytes: ByteArray?)

    suspend fun takeScreenshotJpeg(maxWidthPx: Int = 720, quality: Int = 70): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        val activePkg = getActivePackageName()
        if (activePkg != null && AutomationSafety.isBlocked(
                TaskStep(action = TaskStep.ACTION_CLICK_AT),
                activePkg
            ) != null
        ) {
            Log.i(TAG, "Skipping screenshot on sensitive package: $activePkg")
            return null
        }

        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastScreenshotTimestampMs
        if (elapsed in 0 until SCREENSHOT_DEBOUNCE_MS) {
            delay(SCREENSHOT_DEBOUNCE_MS - elapsed)
        }

        val firstAttempt = captureScreenshotOnce(maxWidthPx, quality)
        if (firstAttempt != null) return firstAttempt.bytes
        if (lastScreenshotErrorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_RESTRICTED) {
            delay(SCREENSHOT_DEBOUNCE_MS)
            return captureScreenshotOnce(maxWidthPx, quality)?.bytes
        }
        return null
    }

    private suspend fun captureScreenshotOnce(maxWidthPx: Int, quality: Int): ScreenshotCaptureOutcome? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        lastScreenshotTimestampMs = SystemClock.elapsedRealtime()
        lastScreenshotErrorCode = 0
        return withTimeoutOrNull(3000L) {
            suspendCancellableCoroutine { cont ->
                try {
                    takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        mainExecutor,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(screenshotResult: ScreenshotResult) {
                                try {
                                    val hwBuffer = screenshotResult.hardwareBuffer
                                    val hwBitmap = try {
                                        Bitmap.wrapHardwareBuffer(hwBuffer, screenshotResult.colorSpace)
                                    } finally {
                                        try {
                                            hwBuffer.close()
                                        } catch (_: Throwable) {}
                                    }
                                    if (hwBitmap == null) {
                                        if (cont.isActive) cont.resume(ScreenshotCaptureOutcome(null))
                                        return
                                    }
                                    val soft = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                    hwBitmap.recycle()
                                    if (soft == null) {
                                        if (cont.isActive) cont.resume(ScreenshotCaptureOutcome(null))
                                        return
                                    }
                                    val scaled = if (soft.width > maxWidthPx && maxWidthPx > 0) {
                                        val targetH = maxOf(
                                            1,
                                            Math.round(soft.height.toDouble() * maxWidthPx.toDouble() / soft.width.toDouble()).toInt()
                                        )
                                        Bitmap.createScaledBitmap(soft, maxWidthPx, targetH, true).also {
                                            if (it != soft) soft.recycle()
                                        }
                                    } else {
                                        soft
                                    }
                                    val stream = ByteArrayOutputStream()
                                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                                    scaled.recycle()
                                    if (cont.isActive) cont.resume(ScreenshotCaptureOutcome(stream.toByteArray()))
                                } catch (e: Throwable) {
                                    Log.w(TAG, "Screenshot processing failed (FLAG_SECURE or buffer error): ${e.message}")
                                    if (cont.isActive) cont.resume(ScreenshotCaptureOutcome(null))
                                }
                            }

                            override fun onFailure(errorCode: Int) {
                                lastScreenshotErrorCode = errorCode
                                Log.w(TAG, "takeScreenshot failed with code $errorCode (skipping)")
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                    )
                } catch (e: Throwable) {
                    Log.w(TAG, "takeScreenshot call threw (skipping): ${e.message}")
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Dispatch helper
    // ------------------------------------------------------------------
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private fun dispatch(gesture: GestureDescription): Boolean {
        return try {
            val latch = CountDownLatch(1)
            val completed = AtomicBoolean(false)
            val accepted = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        mainHandler.post {
                            completed.set(true)
                            latch.countDown()
                        }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        mainHandler.post {
                            completed.set(false)
                            latch.countDown()
                        }
                    }
                },
                mainHandler
            )
            if (!accepted) return false
            if (Looper.myLooper() == Looper.getMainLooper()) {
                Log.w(TAG, "dispatch called on main thread; cannot await CountDownLatch without deadlock")
                return accepted
            }
            val finished = latch.await(2, TimeUnit.SECONDS)
            finished && completed.get()
        } catch (e: Throwable) {
            Log.e(TAG, "Gesture dispatch failed", e)
            false
        }
    }

    companion object {
        private const val TAG = "MiraAccessibility"
        private const val OWN_PACKAGE = "io.github.ryoaonetsuki.mira"
        const val SCREENSHOT_DEBOUNCE_MS = 400L
        const val ERROR_TAKE_SCREENSHOT_INTERVAL_RESTRICTED = 3

        @Volatile
        var instance: MiraAccessibilityService? = null
            internal set

        /** True while the service is bound and connected by the system. */
        val isConnected: Boolean get() = instance != null

        val isRecording: Boolean
            get() = instance?.isRecordingMacro == true

        fun startRecording(): Boolean {
            val s = instance ?: return false
            s.startMacroRecording()
            return true
        }

        fun stopRecordingSteps(): List<TaskStep> {
            val s = instance ?: return emptyList()
            return s.stopMacroRecordingSteps()
        }

        fun convertEventsToSteps(events: List<Map<String, Any>>): List<TaskStep> {
            val steps = mutableListOf<TaskStep>()
            var lastPkg: String? = null

            for (event in events) {
                val pkg = (event["package"] as? String)?.trim().orEmpty()
                val type = event["type"] as? String

                // Jarvis import (macro_recorder_service.dart): Launch target application before interaction if package is external
                if (pkg.isNotEmpty() && pkg != lastPkg && pkg != OWN_PACKAGE && pkg != "com.android.systemui") {
                    if (steps.isEmpty() || lastPkg != null) {
                        steps.add(
                            TaskStep(
                                action = TaskStep.ACTION_OPEN_APP,
                                params = mapOf("app" to pkg),
                                reasoning = "Macro launch target application \"$pkg\""
                            )
                        )
                    }
                    lastPkg = pkg
                }

                when (type) {
                    "click" -> {
                        val label = (event["label"] as? String)?.trim() ?: ""
                        val x = (event["x"] as? Number)?.toInt() ?: 0
                        val y = (event["y"] as? Number)?.toInt() ?: 0

                        if (label.isNotEmpty()) {
                            steps.add(
                                TaskStep(
                                    action = TaskStep.ACTION_CLICK_TEXT,
                                    params = mapOf("text" to label),
                                    reasoning = "Macro click on \"$label\""
                                )
                            )
                        } else if (x > 0 && y > 0) {
                            steps.add(
                                TaskStep(
                                    action = TaskStep.ACTION_CLICK_AT,
                                    params = mapOf("x" to x, "y" to y),
                                    reasoning = "Macro click at coordinates ($x, $y)"
                                )
                            )
                        }
                    }
                    "type" -> {
                        val text = (event["text"] as? String) ?: ""
                        if (text.isNotEmpty()) {
                            steps.add(
                                TaskStep(
                                    action = TaskStep.ACTION_TYPE_TEXT,
                                    params = mapOf("text" to text),
                                    reasoning = "Macro enter text \"$text\""
                                )
                            )
                            // Jarvis import: submit input with IME enter after typing
                            steps.add(
                                TaskStep(
                                    action = TaskStep.ACTION_PRESS_ENTER,
                                    params = emptyMap(),
                                    reasoning = "Macro submit input"
                                )
                            )
                        }
                    }
                    "scroll" -> {
                        val direction = (event["direction"] as? String) ?: "down"
                        steps.add(
                            TaskStep(
                                action = TaskStep.ACTION_SCROLL,
                                params = mapOf("direction" to direction),
                                reasoning = "Macro scroll $direction"
                            )
                        )
                    }
                }
            }
            return steps
        }

        /**
         * Checks whether Mira's AccessibilityService is enabled in Android Settings.
         */
        fun isEnabled(context: Context): Boolean {
            if (instance != null) return true

            val expectedComponent = ComponentName(context, MiraAccessibilityService::class.java).flattenToString()
            val expectedShort = "${context.packageName}/.service.MiraAccessibilityService"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabledServices)
            while (splitter.hasNext()) {
                val service = splitter.next()
                if (service.equals(expectedComponent, ignoreCase = true) ||
                    service.equals(expectedShort, ignoreCase = true)
                ) {
                    val accessibilityEnabled = Settings.Secure.getInt(
                        context.contentResolver,
                        Settings.Secure.ACCESSIBILITY_ENABLED,
                        0
                    )
                    return accessibilityEnabled == 1
                }
            }
            return false
        }
    }
}
