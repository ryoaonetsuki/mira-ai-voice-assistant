package io.github.ryoaonetsuki.mira.automation.agent

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import io.github.ryoaonetsuki.mira.service.MiraAccessibilityService

data class ScreenNode(
    val index: Int,
    val text: String,
    val contentDescription: String,
    val className: String,
    val viewId: String,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isCheckable: Boolean,
    val isEnabled: Boolean,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val centerX: Int,
    val centerY: Int
) {
    companion object {
        fun fromMap(map: Map<String, Any?>): ScreenNode {
            return ScreenNode(
                index = (map["index"] as? Number)?.toInt() ?: 0,
                text = map["text"]?.toString() ?: "",
                contentDescription = map["contentDescription"]?.toString() ?: "",
                className = map["className"]?.toString() ?: "",
                viewId = map["viewId"]?.toString() ?: "",
                isClickable = map["isClickable"] as? Boolean ?: false,
                isEditable = map["isEditable"] as? Boolean ?: false,
                isScrollable = map["isScrollable"] as? Boolean ?: false,
                isCheckable = map["isCheckable"] as? Boolean ?: false,
                isEnabled = map["isEnabled"] as? Boolean ?: true,
                left = (map["left"] as? Number)?.toInt() ?: 0,
                top = (map["top"] as? Number)?.toInt() ?: 0,
                right = (map["right"] as? Number)?.toInt() ?: 0,
                bottom = (map["bottom"] as? Number)?.toInt() ?: 0,
                centerX = (map["centerX"] as? Number)?.toInt() ?: 0,
                centerY = (map["centerY"] as? Number)?.toInt() ?: 0
            )
        }
    }
}

class ScreenAutomationService(private val context: Context? = null) : AutomationPort {

    override fun isServiceEnabled(): Boolean {
        return try {
            val ctx = context
            if (ctx != null) {
                MiraAccessibilityService.isEnabled(ctx)
            } else {
                MiraAccessibilityService.isConnected
            }
        } catch (e: Throwable) {
            Log.e(TAG, "isServiceEnabled failed", e)
            false
        }
    }

    override fun dumpScreen(): List<ScreenNode> {
        return try {
            val service = MiraAccessibilityService.instance ?: return emptyList()
            service.dumpScreen().map { ScreenNode.fromMap(it) }
        } catch (e: Throwable) {
            Log.e(TAG, "dumpScreen failed", e)
            emptyList()
        }
    }

    override fun formatNodes(nodes: List<ScreenNode>): String {
        val eligible = nodes.filter { node ->
            node.text.isNotBlank() ||
                node.contentDescription.isNotBlank() ||
                node.isEditable ||
                node.isScrollable
        }
        return eligible.take(120).joinToString("\n") { formatSingleNode(it) }
    }

    override fun getCompressedScreenDescription(nodes: List<ScreenNode>, goal: String): String {
        val eligible = nodes.filter { node ->
            node.text.isNotBlank() ||
                node.contentDescription.isNotBlank() ||
                node.isEditable ||
                node.isScrollable
        }

        val goalWords = goal.lowercase()
            .split(Regex("[^a-zA-Z0-9]+"))
            .filter { it.isNotBlank() }
            .toSet()

        val prioritized = eligible.sortedBy { node ->
            when {
                sharesWordWithGoal(node, goalWords) -> 0
                node.isClickable || node.isEditable -> 1
                else -> 2
            }
        }

        val top40 = prioritized.take(40)
        val lines = ArrayList<String>(top40.size + 1)
        lines.add("Goal: $goal")
        for (node in top40) {
            lines.add(formatSingleNode(node))
        }
        return lines.joinToString("\n")
    }

    private fun formatSingleNode(node: ScreenNode): String {
        val truncatedText = if (node.text.length > 60) node.text.take(60) else node.text
        return "[${node.index}] text=\"$truncatedText\" desc=\"${node.contentDescription}\" class=${node.className} clickable=${node.isClickable} ${node.centerX},${node.centerY}"
    }

    private fun sharesWordWithGoal(node: ScreenNode, goalWords: Set<String>): Boolean {
        if (goalWords.isEmpty()) return false
        val nodeWords = (node.text + " " + node.contentDescription).lowercase()
            .split(Regex("[^a-zA-Z0-9]+"))
            .filter { it.isNotBlank() }
            .toSet()
        return goalWords.any { it in nodeWords }
    }

    override fun clickText(text: String, nodes: List<ScreenNode>): Boolean {
        return try {
            val service = MiraAccessibilityService.instance ?: return false
            if (text.isBlank()) return false

            // 1. Direct clickable node matching text or description
            val directClickable = nodes.firstOrNull { node ->
                node.isClickable && (
                    node.text.contains(text, ignoreCase = true) ||
                        node.contentDescription.contains(text, ignoreCase = true)
                )
            }
            if (directClickable != null) {
                return clickAt(directClickable.centerX.toFloat(), directClickable.centerY.toFloat())
            }

            // 2. Matching node that may not be clickable itself
            val matchingNode = nodes.firstOrNull { node ->
                node.text.contains(text, ignoreCase = true) ||
                    node.contentDescription.contains(text, ignoreCase = true)
            } ?: return false

            // Climb live accessibility node hierarchy first (like MacroRunner.clickNode)
            val root = service.rootInActiveWindow
            if (root != null) {
                val foundNode = findNodeMatchingText(root, text)
                if (foundNode != null) {
                    var current: AccessibilityNodeInfo? = foundNode
                    while (current != null) {
                        if (current.isClickable) {
                            val rect = Rect()
                            current.getBoundsInScreen(rect)
                            return clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
                        }
                        current = current.parent
                    }
                }
            }

            // Fallback: search for enclosing clickable ancestor in nodes list
            val ancestor = nodes.findLast { candidate ->
                candidate.isClickable &&
                    candidate.left <= matchingNode.left &&
                    candidate.top <= matchingNode.top &&
                    candidate.right >= matchingNode.right &&
                    candidate.bottom >= matchingNode.bottom
            }

            if (ancestor != null) {
                clickAt(ancestor.centerX.toFloat(), ancestor.centerY.toFloat())
            } else {
                clickAt(matchingNode.centerX.toFloat(), matchingNode.centerY.toFloat())
            }
        } catch (e: Throwable) {
            Log.e(TAG, "clickText failed", e)
            false
        }
    }

    private fun findNodeMatchingText(root: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val q = query.lowercase()
        val text = root.text?.toString()?.lowercase() ?: ""
        val desc = root.contentDescription?.toString()?.lowercase() ?: ""
        if (text.contains(q) || desc.contains(q)) {
            return root
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNodeMatchingText(child, query)
            if (found != null) return found
        }
        return null
    }

    override fun clickAt(x: Float, y: Float): Boolean {
        return try {
            val service = MiraAccessibilityService.instance ?: return false
            service.clickAt(x, y)
        } catch (e: Throwable) {
            Log.e(TAG, "clickAt failed", e)
            false
        }
    }

    override fun typeText(text: String): Boolean {
        return try {
            val service = MiraAccessibilityService.instance ?: return false
            service.typeText(text)
        } catch (e: Throwable) {
            Log.e(TAG, "typeText failed", e)
            false
        }
    }

    override fun pressEnter(): Boolean {
        return try {
            val service = MiraAccessibilityService.instance ?: return false
            service.pressKey("enter")
        } catch (e: Throwable) {
            Log.e(TAG, "pressEnter failed", e)
            false
        }
    }

    override fun scroll(direction: String): Boolean {
        return try {
            val service = MiraAccessibilityService.instance ?: return false
            service.scroll(direction)
        } catch (e: Throwable) {
            Log.e(TAG, "scroll failed", e)
            false
        }
    }

    override fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): Boolean {
        return try {
            val service = MiraAccessibilityService.instance ?: return false
            service.swipe(startX, startY, endX, endY, durationMs)
        } catch (e: Throwable) {
            Log.e(TAG, "swipe failed", e)
            false
        }
    }

    override fun pressBack(): Boolean {
        return try {
            val service = MiraAccessibilityService.instance ?: return false
            service.pressKey("back")
        } catch (e: Throwable) {
            Log.e(TAG, "pressBack failed", e)
            false
        }
    }

    override fun pressHome(): Boolean {
        return try {
            val service = MiraAccessibilityService.instance ?: return false
            service.pressKey("home")
        } catch (e: Throwable) {
            Log.e(TAG, "pressHome failed", e)
            false
        }
    }

    override fun openApp(query: String): String? {
        return try {
            val ctx = context ?: return null
            val pm = ctx.packageManager

            // 1. Try resolving query as package name directly
            val directIntent = pm.getLaunchIntentForPackage(query)
            if (directIntent != null) {
                directIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(directIntent)
                return query
            }

            // 2. Search installed launcher activities by app label or package
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            val target = resolveInfos.firstOrNull { ri ->
                val label = ri.loadLabel(pm).toString()
                label.equals(query, ignoreCase = true)
            } ?: resolveInfos.firstOrNull { ri ->
                val label = ri.loadLabel(pm).toString()
                label.contains(query, ignoreCase = true)
            } ?: resolveInfos.firstOrNull { ri ->
                ri.activityInfo.packageName.contains(query, ignoreCase = true)
            }

            if (target != null) {
                val pkg = target.activityInfo.packageName
                val launchIntent = pm.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(launchIntent)
                    return pkg
                }
            }
            null
        } catch (e: Throwable) {
            Log.e(TAG, "openApp failed for query: $query", e)
            null
        }
    }

    override fun getCurrentPackage(): String? {
        return try {
            MiraAccessibilityService.instance?.rootInActiveWindow?.packageName?.toString()
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        private const val TAG = "ScreenAutomation"
    }
}
