package io.github.ryoaonetsuki.mira.automation.agent

import android.util.Log
import io.github.ryoaonetsuki.mira.network.GeminiRestClient
import io.github.ryoaonetsuki.mira.network.VisionElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class TargetSource {
    NODES_EXACT,
    VISION,
    NODES_FALLBACK
}

data class TargetResolution(
    val x: Int,
    val y: Int,
    val source: TargetSource,
    val failureReason: String? = null,
    val matchedNode: ScreenNode? = null,
    val matchedVisionElement: VisionElement? = null
)

data class ScreenPerception(
    val nodes: List<ScreenNode>,
    var visionElements: List<VisionElement>?,
    var visionFailureReason: String?,
    val screenSignature: Int,
    val packageName: String,
    val perceptionLatencyMs: Long,
    var mode: String,
    val screenshotBytes: ByteArray? = null
)

sealed class PreTapResult {
    data class Valid(val isIconOnly: Boolean) : PreTapResult()
    data class Mismatch(val nodeText: String, val visionText: String) : PreTapResult()
}

/**
 * Full-screen vision-first perception engine.
 * Decides whether to tap via exact node match or full-screen vision understanding.
 */
class VisionFirstPerception(
    val automationPort: AutomationPort,
    val apiKeyProvider: (suspend () -> String)? = null,
    val visionDescribe: (suspend (apiKey: String, imageBytes: ByteArray, nodeDump: String, screenWidth: Int, screenHeight: Int, goal: String) -> Result<List<VisionElement>>)? = null,
    val visionLocator: (suspend (target: String, nodes: List<ScreenNode>) -> Pair<Int, Int>?)? = null
) {
    companion object {
        const val TAG = GeminiRestClient.TAG_VISION

        private val BUTTON_STRIP_REGEX = Regex(
            """(?i)\b(button|buttons|tab|tabs|icon|icons)\b|[বাটন|বাটনে|বোতাম|বোতামে|আইকন]"""
        )

        fun normalizeTarget(raw: String): String {
            val stripped = raw
                .replace("বাটনে", " ")
                .replace("বাটন", " ")
                .replace("বোতামে", " ")
                .replace("বোতাম", " ")
                .replace("আইকন", " ")
                .replace(Regex("""(?i)\b(button|buttons|tab|tabs|icon|icons)\b"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
            return stripped.ifBlank { raw.trim() }
        }

        fun computeScreenSignature(pkg: String, nodes: List<ScreenNode>): Int {
            val nodeSig = TaskExecutor.computeNodeSignature(nodes)
            return "$pkg:$nodeSig".hashCode()
        }
    }

    private val failedAttempts = ConcurrentHashMap.newKeySet<Pair<Int, String>>()

    fun recordFailedAttempt(signature: Int, target: String) {
        val norm = normalizeTarget(target).lowercase(Locale.ROOT)
        failedAttempts.add(signature to norm)
    }

    fun hasAttemptFailed(signature: Int, target: String): Boolean {
        val norm = normalizeTarget(target).lowercase(Locale.ROOT)
        return failedAttempts.contains(signature to norm)
    }

    /**
     * Captures a fresh screenshot and fresh node dump, optionally invoking vision describe
     * if target is ambiguous/missing or if forceVision is requested.
     */
    suspend fun perceive(
        goal: String = "",
        target: String? = null,
        forceVision: Boolean = false
    ): ScreenPerception = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val nodes = automationPort.dumpScreen()
        val pkg = automationPort.getCurrentPackage().orEmpty()
        val sig = computeScreenSignature(pkg, nodes)
        val dims = automationPort.getScreenDimensions() ?: Pair(1080, 2400)
        val screenshot = automationPort.takeScreenshotJpeg(720)

        var visionElements: List<VisionElement>? = null
        var visionFailureReason: String? = null
        var mode = "nodes-only"

        val shouldRunVision = forceVision || (target != null && shouldTriggerVision(nodes, sig, target))

        if (shouldRunVision) {
            val describeResult = executeVisionDescribe(screenshot, nodes, dims, goal)
            if (describeResult.isSuccess) {
                visionElements = describeResult.getOrNull()
                mode = "vision"
            } else {
                val err = describeResult.exceptionOrNull()?.message ?: "Vision describe failed"
                visionFailureReason = err
                mode = "vision-failed:$err"
            }
        }

        val latencyMs = System.currentTimeMillis() - startMs
        val elementCount = visionElements?.size ?: nodes.size
        Log.i(TAG, "Vision perception took ${latencyMs}ms (mode=$mode) screen=$pkg elements=$elementCount")

        ScreenPerception(
            nodes = nodes,
            visionElements = visionElements,
            visionFailureReason = visionFailureReason,
            screenSignature = sig,
            packageName = pkg,
            perceptionLatencyMs = latencyMs,
            mode = mode,
            screenshotBytes = screenshot
        )
    }

    private fun shouldTriggerVision(nodes: List<ScreenNode>, sig: Int, target: String): Boolean {
        val norm = normalizeTarget(target).lowercase(Locale.ROOT)
        if (norm.isBlank()) return false
        if (hasAttemptFailed(sig, norm)) return true

        val candidates = nodes.filter {
            (it.text.contains(norm, ignoreCase = true) ||
                it.contentDescription.contains(norm, ignoreCase = true) ||
                (it.viewId.isNotBlank() && (it.viewId.equals(norm, ignoreCase = true) || it.viewId.endsWith(":id/$norm", ignoreCase = true))))
        }

        if (candidates.isEmpty()) return true
        if (candidates.size > 1) return true
        val single = candidates.first()
        if (single.text.isBlank() && single.contentDescription.isBlank()) return true
        return false
    }

    private suspend fun executeVisionDescribe(
        screenshot: ByteArray?,
        nodes: List<ScreenNode>,
        dims: Pair<Int, Int>,
        goal: String
    ): Result<List<VisionElement>> {
        if (screenshot == null || screenshot.isEmpty()) {
            return Result.failure(IllegalStateException("Screenshot capture returned null or empty"))
        }

        val key = apiKeyProvider?.invoke()?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("Gemini API key is missing or blank"))

        val nodeDump = automationPort.formatNodes(nodes)

        return if (visionDescribe != null) {
            visionDescribe.invoke(key, screenshot, nodeDump, dims.first, dims.second, goal)
        } else {
            val rest = GeminiRestClient()
            rest.describeScreenWithVision(
                apiKey = key,
                imageBytes = screenshot,
                nodeDump = nodeDump,
                screenWidth = dims.first,
                screenHeight = dims.second,
                goal = goal
            )
        }
    }

    /**
     * DECISION RULE:
     * 1. FAST PATH: exact unambiguous labeled node match -> NODES_EXACT
     * 2. VISION PATH: no match, multiple matches, icon-only, or failed attempt -> VISION
     * 3. NODES_FALLBACK: vision unavailable/failed -> NODES_FALLBACK + failure reason
     */
    suspend fun findTarget(
        perception: ScreenPerception,
        target: String,
        goal: String = ""
    ): TargetResolution {
        val norm = normalizeTarget(target).trim()
        val normLower = norm.lowercase(Locale.ROOT)
        val rawLower = target.trim().lowercase(Locale.ROOT)

        // 1. FAST PATH
        val candidates = perception.nodes.filter {
            (it.text.contains(norm, ignoreCase = true) ||
                it.contentDescription.contains(norm, ignoreCase = true) ||
                (it.viewId.isNotBlank() && (it.viewId.equals(norm, ignoreCase = true) || it.viewId.endsWith(":id/$norm", ignoreCase = true))))
        }

        val labeledCandidates = candidates.filter { it.text.isNotBlank() || it.contentDescription.isNotBlank() }
        val previouslyFailed = hasAttemptFailed(perception.screenSignature, norm)

        if (!previouslyFailed && labeledCandidates.size == 1) {
            val exact = labeledCandidates.first()
            return TargetResolution(
                x = exact.centerX,
                y = exact.centerY,
                source = TargetSource.NODES_EXACT,
                matchedNode = exact
            )
        }

        // 2. VISION PATH
        if (perception.visionElements == null && perception.visionFailureReason == null) {
            val dims = automationPort.getScreenDimensions() ?: Pair(1080, 2400)
            val res = executeVisionDescribe(perception.screenshotBytes, perception.nodes, dims, goal)
            if (res.isSuccess) {
                perception.visionElements = res.getOrNull()
                perception.mode = "vision"
            } else {
                val err = res.exceptionOrNull()?.message ?: "Vision describe failed"
                perception.visionFailureReason = err
                perception.mode = "vision-failed:$err"
            }
        }

        val visionList = perception.visionElements
        if (!visionList.isNullOrEmpty()) {
            val dims = automationPort.getScreenDimensions() ?: Pair(1080, 2400)
            val best = pickBestVisionElement(visionList, normLower, rawLower, goal, dims)
            if (best != null) {
                return TargetResolution(
                    x = best.xPx,
                    y = best.yPx,
                    source = TargetSource.VISION,
                    matchedVisionElement = best
                )
            }
        }

        // Fallback to single-target locator if available
        if (visionLocator != null) {
            val singleCoords = visionLocator.invoke(target, perception.nodes)
            if (singleCoords != null) {
                return TargetResolution(
                    x = singleCoords.first,
                    y = singleCoords.second,
                    source = TargetSource.VISION
                )
            }
            // The locator returned null — its specific failure reason (set by
            // resolveVisionCoordinates on the executor) is merged into the
            // caller's error by TaskExecutor._performAction, which reads the
            // executor's lastVisionFailureReason alongside the resolution's
            // failureReason.
        }

        // 3. NODES_FALLBACK
        val bestNode = candidates.firstOrNull() ?: UiNodeMatcher.findBest(perception.nodes, norm)
        val reason = perception.visionFailureReason ?: "Vision could not locate '$target'"
        return if (bestNode != null) {
            TargetResolution(
                x = bestNode.centerX,
                y = bestNode.centerY,
                source = TargetSource.NODES_FALLBACK,
                failureReason = reason,
                matchedNode = bestNode
            )
        } else {
            TargetResolution(
                x = 0,
                y = 0,
                source = TargetSource.NODES_FALLBACK,
                failureReason = reason
            )
        }
    }

    private fun pickBestVisionElement(
        elements: List<VisionElement>,
        targetLower: String,
        rawLower: String,
        goal: String,
        dims: Pair<Int, Int>
    ): VisionElement? {
        val screenHeight = dims.second
        val goalLower = goal.lowercase(Locale.ROOT)

        val isTabIntent = rawLower.contains("tab") || rawLower.contains("বাটন") || rawLower.contains("button") || rawLower.contains("nav")
        val isFieldIntent = rawLower.contains("field") || rawLower.contains("input") || rawLower.contains("edit") || rawLower.contains("text") || rawLower.contains("top") || rawLower.contains("box")

        var bestElem: VisionElement? = null
        var bestScore = -1

        for (elem in elements) {
            var score = 0
            val eText = elem.text.lowercase(Locale.ROOT)
            val eRole = elem.role.lowercase(Locale.ROOT)
            val eAction = elem.action.lowercase(Locale.ROOT)
            val y = elem.yPx

            // Direct text match
            if (eText == targetLower) {
                score += 100
            } else if (eText.contains(targetLower) || (targetLower.length > 2 && targetLower.contains(eText))) {
                score += 60
            }

            // Semantic icon description matching
            val isSearchTarget = targetLower.contains("search") || goalLower.contains("search") || targetLower.contains("সার্চ") || targetLower.contains("খুঁজ")
            if (isSearchTarget) {
                if (eText.contains("search") || eText.contains("magnifier") || eText.contains("glass")) {
                    score += 50
                }
            }

            // Action matching
            if (eAction.contains(targetLower) || (isSearchTarget && eAction.contains("search"))) {
                score += 30
            }

            // Generic positional priors
            // (a) Bottom navigation / tab prior: y >= screenHeight * 0.70
            if (y >= screenHeight * 0.70) {
                if (eRole == "tab" || eRole == "navigation") score += 40
                if (isTabIntent) score += 50
                if (!isFieldIntent && eRole == "tab") score += 30
            }

            // (b) Top search field / input prior: y <= screenHeight * 0.30
            if (y <= screenHeight * 0.30) {
                if (eRole in listOf("text_field", "input", "edit", "search_field", "search_box")) score += 60
                if (isFieldIntent) score += 50
                if (isSearchTarget && (eRole.contains("field") || eRole.contains("edit") || eRole == "input" || eText.contains("search"))) {
                    score += 40
                }
            }

            if (score > bestScore) {
                bestScore = score
                bestElem = elem
            }
        }

        return if (bestScore > 0) bestElem else null
    }

    /**
     * Pre-tap verification against the fresh node dump.
     * Prevents tapping vision coordinates if the underlying node clearly contradicts vision.
     */
    fun verifyPreTap(
        coords: Pair<Int, Int>,
        visionElement: VisionElement?,
        nodes: List<ScreenNode>
    ): PreTapResult {
        val (x, y) = coords
        val nearby = nodes.filter { node ->
            (x in node.left..node.right && y in node.top..node.bottom) ||
                (Math.abs(node.centerX - x) <= 60 && Math.abs(node.centerY - y) <= 60)
        }

        if (nearby.isEmpty()) {
            Log.i(TAG, "icon-only tap, unverified by nodes at ($x, $y) for '${visionElement?.text}'")
            return PreTapResult.Valid(isIconOnly = true)
        }

        val labeledNode = nearby.firstOrNull { it.text.isNotBlank() || it.contentDescription.isNotBlank() }
        if (labeledNode == null) {
            return PreTapResult.Valid(isIconOnly = true)
        }

        val vText = normalizeTarget(visionElement?.text.orEmpty()).lowercase(Locale.ROOT)
        val nText = normalizeTarget(labeledNode.text.ifBlank { labeledNode.contentDescription }).lowercase(Locale.ROOT)

        if (vText.isEmpty() || nText.isEmpty() || nText.contains(vText) || vText.contains(nText)) {
            return PreTapResult.Valid(isIconOnly = false)
        }

        val vTokens = vText.split(Regex("""\s+""")).filter { it.length > 2 }
        val nTokens = nText.split(Regex("""\s+""")).filter { it.length > 2 }
        if (vTokens.any { it in nTokens }) {
            return PreTapResult.Valid(isIconOnly = false)
        }

        Log.w(TAG, "Pre-tap mismatch at ($x, $y): vision said '$vText', node says '$nText'")
        return PreTapResult.Mismatch(
            nodeText = labeledNode.text.ifBlank { labeledNode.contentDescription },
            visionText = visionElement?.text.orEmpty()
        )
    }
}
