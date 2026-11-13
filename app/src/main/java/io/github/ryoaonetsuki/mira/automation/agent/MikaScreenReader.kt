/*
 * Ported from Mika (github.com/wangzan101/Mika), Apache License 2.0.
 * Copyright 2026 wangzan101. Original code used with attribution.
 * Adaptations: renamed package, dropped WheelMath/UiWindowTree/UiTreeNode,
 * inlined normalize(), inlined ambiguousFieldReadback().
 */
package io.github.ryoaonetsuki.mira.automation.agent

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// Inline data types (ported from Mika's uistate package)
// ---------------------------------------------------------------------------

data class MikaUiElement(
    val ref: String,
    val role: String,
    val name: String,
    val centerX: Int,
    val centerY: Int,
    val bounds: List<Int>,
    val resourceId: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val hint: Boolean,
    val focused: Boolean,
    val secret: Boolean,
    val placeholder: String?,
    val searchField: Boolean,
    val states: Set<String>,
)

data class MikaElementLocator(
    val role: String,
    val name: String,
    val resourceId: String?,
    val centerX: Int,
    val centerY: Int,
    val context: String?,
    val hint: Boolean = false,
    val inList: Boolean = false,
)

object UiStates {
    const val CHECKED = "checked"
    const val SELECTED = "selected"
    const val EXPANDED = "expanded"
    const val PRESSED = "pressed"
    const val DISABLED = "disabled"
}

// ---------------------------------------------------------------------------
// NodeEntry — the flattened read result
// ---------------------------------------------------------------------------

data class NodeEntry(val element: MikaUiElement, val locator: MikaElementLocator, val node: AccessibilityNodeInfo? = null)

/**
 * 读一次屏的结果。[hash] 覆盖所有可见节点(含标成「对无障碍不重要」的)的文字、类型、位置、勾选、
 * 输入焦点和光标/选区,用来判断界面变没变:点输入框只换了焦点也算变了。
 */
class ScreenRead(
    val entries: List<NodeEntry>,
    val hash: Long,
    val texts: List<String>,
    val passwordField: Boolean,
    val hasAppWindow: Boolean,
    val topPackage: String?,
)

// ---------------------------------------------------------------------------
// ambiguousFieldReadback (ported inline from Mika's uistate package)
// ---------------------------------------------------------------------------

/**
 * 读回是不是「原提示语 + 空白 + 目标文字」这种有歧义的形式。
 * 它可能是控件把标签和正文拼在了一起,也可能用户正文本来就以标签开头;不能删前缀或判成匹配。
 */
fun ambiguousFieldReadback(shown: String, placeholder: String?, typing: String?): Boolean {
    val label = placeholder?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val wanted = typing?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val value = shown.trim()
    if (!value.startsWith(label) || !value.endsWith(wanted)) return false
    val separatorLength = value.length - label.length - wanted.length
    if (separatorLength <= 0) return false
    return value.substring(label.length, label.length + separatorLength).all { it.isWhitespace() }
}

// ---------------------------------------------------------------------------
// ScreenReader
// ---------------------------------------------------------------------------

/**
 * Public type alias so callers (e.g. MikaReliableActions) can reference
 * [MikaScreenReader] without duplicating the reader object.
 */
typealias MikaScreenRead = ScreenRead

/**
 * Public object alias for the internal [ScreenReader].
 * MikaReliableActions references this by name; routing through the alias
 * keeps the internal object name stable and avoids a second copy.
 */
object MikaScreenReader {
    fun read(windows: List<android.view.accessibility.AccessibilityWindowInfo>, width: Int, height: Int): ScreenRead =
        ScreenReader.read(windows, width, height)
    fun hash(windows: List<android.view.accessibility.AccessibilityWindowInfo>): Long =
        ScreenReader.hash(windows)
    fun labelOf(n: android.view.accessibility.AccessibilityNodeInfo, own: String? = ScreenReader.ownLabel(n)): String =
        ScreenReader.labelOf(n, own)
    fun fieldText(n: android.view.accessibility.AccessibilityNodeInfo, placeholder: String? = null, typing: String? = null): String? =
        ScreenReader.fieldText(n, placeholder, typing)
    fun locatorOf(n: android.view.accessibility.AccessibilityNodeInfo, width: Int, height: Int): MikaElementLocator? =
        ScreenReader.locatorOf(n, width, height)
    fun ownLabel(n: android.view.accessibility.AccessibilityNodeInfo): String? =
        ScreenReader.ownLabel(n)
    fun topPackage(windows: List<android.view.accessibility.AccessibilityWindowInfo>): String? =
        ScreenReader.topPackage(windows)
    fun canSetText(n: android.view.accessibility.AccessibilityNodeInfo): Boolean =
        ScreenReader.canSetText(n)
}

/** 从某块屏的窗口里读出元素列表、界面指纹和诊断用的文字。 */
internal object ScreenReader {
    private const val MIN_SIZE = 5
    private const val MAX_DEPTH = 80
    private const val AGGREGATE_MAX = 60

    // 输入法与无障碍浮层不属于被操作的 App
    private val SKIPPED_TYPES = setOf(
        AccessibilityWindowInfo.TYPE_INPUT_METHOD,
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
        AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY,
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER,
    )

    /** 从上到下排好的、属于界面内容的窗口。 */
    fun contentWindows(windows: List<AccessibilityWindowInfo>): List<AccessibilityWindowInfo> =
        windows.filter { it.type !in SKIPPED_TYPES }.sortedByDescending { it.layer }

    // 最上面的应用窗口可能是读不到内容的浮层(弹出菜单、输入框的选字浮窗等),跳过它们
    fun topPackage(windows: List<AccessibilityWindowInfo>): String? =
        contentWindows(windows).filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .firstNotNullOfOrNull { it.root?.packageName?.toString() }

    fun read(windows: List<AccessibilityWindowInfo>, width: Int, height: Int): ScreenRead {
        val content = contentWindows(windows)
        val entries = mutableListOf<NodeEntry>()
        val texts = mutableListOf<String>()
        val seen = HashSet<String>()
        var hash = FNV_OFFSET
        var password = false
        var lastLabel: String? = null
        val rect = Rect()

        fun normalize(p: Int, size: Int): Int =
            if (size <= 1) 0 else minOf(999, maxOf(0, (p * 999.0 / (size - 1)).roundToInt()))

        fun visit(n: AccessibilityNodeInfo, depth: Int, inList: Boolean) {
            if (depth > MAX_DEPTH) return
            val visible = n.isVisibleToUser
            val editable = visible && canSetText(n)
            var ref: String? = null
            var clippedBounds = listOf(0, 0, 0, 0)
            if (visible) {
                n.getBoundsInScreen(rect)
                hash = mix(hash, n.className, n.text, n.contentDescription, rect.flattenToString(),
                    states(n))
                if (n.isPassword) password = true
                val own = if (n.isPassword) n.hintText?.toString()?.trim()?.ifEmpty { null } else ownLabel(n)
                own?.let(texts::add)
                clip(rect, width, height)
                clippedBounds = listOf(normalize(rect.left, width), normalize(rect.top, height),
                    normalize(rect.right, width), normalize(rect.bottom, height))
                val usable = rect.width() >= MIN_SIZE && rect.height() >= MIN_SIZE
                val interactive = n.isClickable || editable || n.isScrollable
                if (usable && (own != null || interactive)) {
                    val label = labelOf(n, own)
                    val role = roleOf(n)
                    if (seen.add("$role|$label|${rect.flattenToString()}")) {
                        val id = "@e${entries.size + 1}"
                        ref = id
                        val rid = n.viewIdResourceName
                        val element = MikaUiElement(
                            ref = id, role = role, name = label,
                            centerX = rect.centerX(), centerY = rect.centerY(),
                            bounds = listOf(
                                normalize(rect.left, width), normalize(rect.top, height),
                                normalize(rect.right, width), normalize(rect.bottom, height),
                            ),
                            // 输入框总带上资源 ID:回车前要靠它认出搜索框(名字常常只是推荐词)
                            resourceId = if (label.isEmpty() || editable) readableResourceId(rid) else null,
                            clickable = n.isClickable, editable = editable, scrollable = n.isScrollable,
                            hint = own != null && showingHint(n),
                            focused = n.isFocused,
                            secret = n.isPassword,
                            placeholder = if (editable) n.hintText?.toString()?.trim()?.ifEmpty { null } else null,
                            // 安卓的搜索框控件(SearchView 里的输入框)按类名认
                            searchField = editable && n.className?.toString()?.contains("Search", ignoreCase = true) == true,
                            states = uiStates(n),
                        )
                        val locator = MikaElementLocator(role, label, rid, rect.centerX(), rect.centerY(), lastLabel, element.hint, inList)
                        entries += NodeEntry(element, locator, n)
                        if (label.isNotEmpty()) lastLabel = label
                    }
                }
            }
            val childrenInList = inList || isList(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it, depth + 1, childrenInList) }
        }

        for (w in content) w.root?.let { visit(it, 0, false) }

        return ScreenRead(
            entries = entries,
            hash = hash,
            texts = texts,
            passwordField = password,
            hasAppWindow = content.any { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root != null },
            topPackage = topPackage(windows),
        )
    }

    /** 单个节点当前的特征(不含上下文);节点不可见或太小时为 null。 */
    fun locatorOf(n: AccessibilityNodeInfo, width: Int, height: Int): MikaElementLocator? {
        if (!n.isVisibleToUser) return null
        val rect = Rect().also { n.getBoundsInScreen(it) }
        clip(rect, width, height)
        if (rect.width() < MIN_SIZE || rect.height() < MIN_SIZE) return null
        val own = ownLabel(n)
        fun normalize(p: Int, size: Int): Int =
            if (size <= 1) 0 else minOf(999, maxOf(0, (p * 999.0 / (size - 1)).roundToInt()))
        return MikaElementLocator(
            roleOf(n), labelOf(n, own), n.viewIdResourceName, rect.centerX(), rect.centerY(), null,
            hint = own != null && showingHint(n), inList = inList(n),
        )
    }

    /**
     * 看上去是滚轮选择器:原版的数字滚轮控件(WheelMath dropped — always returns false for
     * heuristic wheel detection; NumberPicker is still recognized by class name).
     */
    fun isWheel(n: AccessibilityNodeInfo): Boolean {
        if (n.className?.toString()?.endsWith("NumberPicker") == true) return true
        return false
    }

    /** 列表类容器:子项会复用,同一位置可能换成别的条目。 */
    private fun isList(n: AccessibilityNodeInfo): Boolean {
        if (n.collectionInfo != null) return true
        val cls = n.className?.toString() ?: return false
        return cls.endsWith("RecyclerView") || cls.endsWith("ListView") || cls.endsWith("GridView")
    }

    private fun inList(n: AccessibilityNodeInfo): Boolean {
        var p = n.parent
        var depth = 0
        while (p != null && depth++ < MAX_DEPTH) {
            if (isList(p)) return true
            p = p.parent
        }
        return false
    }

    private fun clip(rect: Rect, width: Int, height: Int) {
        if (!rect.intersect(0, 0, width, height)) rect.setEmpty()
    }

    private fun roleOf(n: AccessibilityNodeInfo) = n.className?.toString()?.substringAfterLast('.')?.ifEmpty { null } ?: "View"

    fun labelOf(n: AccessibilityNodeInfo, own: String? = ownLabel(n)) = own ?: if (n.isClickable) descendantLabel(n) else ""

    /** 只算界面指纹,不建元素表(动作前后比对用)。 */
    fun hash(windows: List<AccessibilityWindowInfo>): Long {
        var hash = FNV_OFFSET
        val rect = Rect()
        fun visit(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_DEPTH) return
            if (n.isVisibleToUser) {
                n.getBoundsInScreen(rect)
                hash = mix(hash, n.className, n.text, n.contentDescription, rect.flattenToString(),
                    states(n))
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it, depth + 1) }
        }
        for (w in contentWindows(windows)) w.root?.let { visit(it, 0) }
        return hash
    }

    /** 判动作效果用的控件状态(和 [hash] 看的同一批)。 */
    @Suppress("DEPRECATION")
    private fun uiStates(n: AccessibilityNodeInfo): Set<String> = buildSet {
        if (n.isChecked) add(UiStates.CHECKED)
        if (n.isSelected) add(UiStates.SELECTED)
        if (!n.isEnabled) add(UiStates.DISABLED)
    }

    // isChecked 在 API 36 起被三态的 checked 取代;minSdk 30 仍需用它
    @Suppress("DEPRECATION")
    private fun states(n: AccessibilityNodeInfo) =
        "${n.isChecked}${n.isSelected}${n.isEnabled}${n.isFocused}${n.textSelectionStart}:${n.textSelectionEnd}"

    /** 节点自己的名字:文字(提示语也算,输入框靠它说明用途),没有就用内容描述。 */
    fun ownLabel(n: AccessibilityNodeInfo): String? {
        val text = n.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) return text
        val desc = n.contentDescription?.toString()?.trim()
        if (!desc.isNullOrEmpty()) return desc
        return n.hintText?.toString()?.trim()?.ifEmpty { null }
    }

    /**
     * 节点上显示的是提示语:系统标了 showingHintText,或没有实际文字、名字来自提示语,
     * 或报出的文字就是提示语本身(有的输入框不设那个标记)。
     */
    private fun showingHint(n: AccessibilityNodeInfo): Boolean {
        if (n.isShowingHintText) return true
        val hint = n.hintText?.toString()?.trim()
        if (hint.isNullOrEmpty()) return false
        val text = n.text?.toString()?.trim()
        return if (text.isNullOrEmpty()) n.contentDescription.isNullOrBlank() else text == hint
    }

    /** 输入框正在显示的提示语;没在显示提示语时为 null。 */
    fun placeholderOf(n: AccessibilityNodeInfo): String? =
        if (showingHint(n)) n.text?.toString()?.trim()?.ifEmpty { null } ?: n.hintText?.toString()?.trim() else null

    /** Some accessibility providers expose the action without setting the editable flag. */
    fun canSetText(n: AccessibilityNodeInfo): Boolean =
        n.isEditable || n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }

    /**
     * 输入框里用户可见的实际文字;显示的是提示语时为空串,密码框读不出时为 null。
     * [placeholder] 是输入前看到的提示语:有的输入框打字后不再标「显示提示语」,却仍把它当文字报出来,这时也算空。
     * 但要输入的 [typing] 本身就等于提示语时,没标「显示提示语」的同样文字就当作已输入。
     * 读回呈「提示语 + 空白 + 目标文字」时无法分清标签和用户正文,返回 null,不删前缀也不触发重写。
     */
    fun fieldText(n: AccessibilityNodeInfo, placeholder: String? = null, typing: String? = null): String? {
        if (n.isPassword) return null
        if (n.isShowingHintText) return ""
        val text = n.text?.toString() ?: return ""
        val shown = text.trim()
        if (typing != null && shown == typing.trim()) return text
        if (showingHint(n) || (placeholder != null && shown == placeholder)) return ""
        if (ambiguousFieldReadback(shown, placeholder, typing)) return null
        return text
    }

    /** 没名字的可点容器用子节点的文字当名字。 */
    private fun descendantLabel(n: AccessibilityNodeInfo): String {
        val parts = mutableListOf<String>()
        fun walk(c: AccessibilityNodeInfo, depth: Int) {
            if (depth > 6 || parts.joinToString(" ").length > AGGREGATE_MAX) return
            for (i in 0 until c.childCount) {
                val child = c.getChild(i) ?: continue
                if (!child.isVisibleToUser) continue
                ownLabel(child)?.let { if (it !in parts) parts += it }
                walk(child, depth + 1)
            }
        }
        walk(n, 0)
        val joined = parts.joinToString(" ")
        return if (joined.length > AGGREGATE_MAX) joined.take(AGGREGATE_MAX) + "…" else joined
    }

    /** `com.x:id/cart_icon` → `cart_icon`;混淆过的短名认不出用途,不给。 */
    private fun readableResourceId(id: String?): String? {
        val short = id?.substringAfterLast('/') ?: return null
        return short.takeIf { Regex("^[A-Za-z0-9_]{4,}$").matches(it) && Regex("[A-Za-z]{3}").containsMatchIn(it) }
    }

    private const val FNV_OFFSET = -0x340d631b7bdddcdbL
    private const val FNV_PRIME = 0x100000001b3L

    private fun mix(h0: Long, vararg parts: Any?): Long {
        var h = h0
        for (p in parts) {
            val s = p?.toString() ?: "\u0000"
            for (c in s) h = (h xor c.code.toLong()) * FNV_PRIME
            h = (h xor 0x1f) * FNV_PRIME
        }
        return h
    }
}
