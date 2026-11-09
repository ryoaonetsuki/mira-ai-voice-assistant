package io.github.ryoaonetsuki.mira.automation.agent

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import io.github.ryoaonetsuki.mira.service.MiraAccessibilityService
import kotlinx.coroutines.CancellationException

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

open class ScreenAutomationService(private val context: Context? = null) : AutomationPort {

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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "dumpScreen failed", e)
            emptyList()
        }
    }

    override fun getScreenElements(): List<ScreenNode> = dumpScreen()

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
            .split(Regex("[^\\p{L}\\p{N}]+"))
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
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() }
            .toSet()
        return goalWords.any { it in nodeWords }
    }

    override fun clickText(
        text: String,
        nodes: List<ScreenNode>,
        fallbackX: Int?,
        fallbackY: Int?
    ): Boolean {
        if (clickText(text, nodes)) return true
        if (fallbackX != null && fallbackY != null && fallbackX > 0 && fallbackY > 0) {
            try {
                Log.i(TAG, "clickText '$text' not found on screen; falling back to recorded coordinates ($fallbackX, $fallbackY)")
            } catch (_: Throwable) {}
            return clickAt(fallbackX.toFloat(), fallbackY.toFloat())
        }
        return false
    }

    override fun clickText(text: String, nodes: List<ScreenNode>): Boolean {
        return try {
            if (text.isBlank()) return false

            val service = MiraAccessibilityService.instance
            val root = service?.rootInActiveWindow

            // 1. Direct clickable node matching text or description
            val directClickable = nodes.firstOrNull { node ->
                node.isClickable && (
                    node.text.contains(text, ignoreCase = true) ||
                        node.contentDescription.contains(text, ignoreCase = true)
                )
            }
            if (directClickable != null) {
                // Prefer performAction(ACTION_CLICK) on live accessibility node first
                if (root != null) {
                    val liveNode = findNodeMatchingText(root, text)
                    if (liveNode != null) {
                        var cur: AccessibilityNodeInfo? = liveNode
                        while (cur != null) {
                            if (cur.isClickable) {
                                if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                                    return true
                                }
                                break
                            }
                            cur = cur.parent
                        }
                    }
                }
                return clickAt(directClickable.centerX.toFloat(), directClickable.centerY.toFloat())
            }

            // 2. Matching node that may not be clickable itself
            val matchingNode = nodes.firstOrNull { node ->
                node.text.contains(text, ignoreCase = true) ||
                    node.contentDescription.contains(text, ignoreCase = true)
            } ?: return false

            // Climb live accessibility node hierarchy first (like MacroRunner.clickNode)
            if (root != null) {
                val foundNode = findNodeMatchingText(root, text)
                if (foundNode != null) {
                    var current: AccessibilityNodeInfo? = foundNode
                    while (current != null) {
                        if (current.isClickable) {
                            if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                                return true
                            }
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
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "clickAt failed", e)
            false
        }
    }

    override fun typeText(text: String): Boolean {
        return try {
            val service = MiraAccessibilityService.instance ?: return false
            service.typeText(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "typeText failed", e)
            false
        }
    }

    override fun pressEnter(): Boolean {
        return try {
            val service = MiraAccessibilityService.instance ?: return false
            service.pressKey("enter")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "pressEnter failed", e)
            false
        }
    }

    override fun scroll(direction: String): Boolean {
        return try {
            val service = MiraAccessibilityService.instance ?: return false
            service.scroll(direction)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "scroll failed", e)
            false
        }
    }

    override fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): Boolean {
        return try {
            val service = MiraAccessibilityService.instance ?: return false
            service.swipe(startX, startY, endX, endY, durationMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "swipe failed", e)
            false
        }
    }

    override fun pressBack(): Boolean {
        return try {
            val service = MiraAccessibilityService.instance ?: return false
            service.pressKey("back")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "pressBack failed", e)
            false
        }
    }

    override fun pressHome(): Boolean {
        return try {
            val service = MiraAccessibilityService.instance ?: return false
            service.pressKey("home")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "pressHome failed", e)
            false
        }
    }

    override fun openApp(query: String): String? {
        val q = query.trim()
        if (q.isEmpty()) return null
        return try {
            val ctx = context ?: run {
                Log.e(TAG, "openApp failed: context is null")
                return null
            }
            val pm = ctx.packageManager

            // 1. Resolve known-app alias table (English & Bangla) before label search
            val aliasCandidates = resolveKnownAppPackages(q)
            for (pkg in aliasCandidates) {
                val aliasIntent = pm.getLaunchIntentForPackage(pkg)
                if (aliasIntent != null) {
                    aliasIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        ctx.startActivity(aliasIntent)
                        Log.i(TAG, "openApp: successfully launched alias package $pkg")
                        return pkg
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Log.e(TAG, "openApp: startActivity threw for alias package $pkg", e)
                    }
                }
            }

            // 2. Try resolving query as package name directly
            val directIntent = pm.getLaunchIntentForPackage(q)
            if (directIntent != null) {
                directIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    ctx.startActivity(directIntent)
                    Log.i(TAG, "openApp: successfully launched package $q directly")
                    return q
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.e(TAG, "openApp: startActivity threw for package $q directly", e)
                }
            }

            // 3. Search installed launcher activities by app label or package
            val searchTerms = listOfNotNull(q, resolveEnglishLabelFallback(q)).distinct()
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            var target: android.content.pm.ResolveInfo? = null
            for (term in searchTerms) {
                if (term.isEmpty()) continue
                target = resolveInfos.firstOrNull { ri ->
                    val label = ri.loadLabel(pm).toString()
                    label.equals(term, ignoreCase = true)
                } ?: resolveInfos.firstOrNull { ri ->
                    val label = ri.loadLabel(pm).toString()
                    label.contains(term, ignoreCase = true)
                } ?: resolveInfos.firstOrNull { ri ->
                    ri.activityInfo.packageName.contains(term, ignoreCase = true)
                }
                if (target != null) break
            }

            if (target != null) {
                val pkg = target.activityInfo.packageName
                val launchIntent = pm.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        ctx.startActivity(launchIntent)
                        Log.i(TAG, "openApp: successfully launched resolved package $pkg")
                        return pkg
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Log.e(TAG, "openApp: startActivity threw for resolved package $pkg", e)
                    }
                }
            }
            Log.w(TAG, "openApp: unable to find or launch any activity for query: $q")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "openApp failed for query: $q", e)
            null
        }
    }

    override fun getCurrentPackage(): String? {
        return try {
            val service = MiraAccessibilityService.instance ?: return null
            service.getActivePackageName() ?: service.rootInActiveWindow?.packageName?.toString()
        } catch (_: Throwable) {
            null
        }
    }

    override suspend fun takeScreenshotJpeg(maxWidthPx: Int): ByteArray? {
        return try {
            val service = MiraAccessibilityService.instance ?: return null
            service.takeScreenshotJpeg(maxWidthPx = maxWidthPx)
        } catch (e: Throwable) {
            Log.w(TAG, "takeScreenshotJpeg failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "ScreenAutomation"

        private val KNOWN_APP_ALIASES: Map<String, List<String>> = mapOf(
            // YouTube across 30 languages
            "youtube" to listOf("com.google.android.youtube"),
            "ইউটিউব" to listOf("com.google.android.youtube"),
            "यूट्यूब" to listOf("com.google.android.youtube"),
            "युट्युब" to listOf("com.google.android.youtube"),
            "یوٹیوب" to listOf("com.google.android.youtube"),
            "يوتيوب" to listOf("com.google.android.youtube"),
            "ユーチューブ" to listOf("com.google.android.youtube"),
            "유튜브" to listOf("com.google.android.youtube"),
            "ютуб" to listOf("com.google.android.youtube"),
            "ютьюб" to listOf("com.google.android.youtube"),
            "ยูทูบ" to listOf("com.google.android.youtube"),
            "ยูทูป" to listOf("com.google.android.youtube"),
            "யூடியூப்" to listOf("com.google.android.youtube"),
            "యూట్యూబ్" to listOf("com.google.android.youtube"),
            "યુટ્યુબ" to listOf("com.google.android.youtube"),
            "ਯੂਟਿਊਬ" to listOf("com.google.android.youtube"),
            "യൂട്യൂബ്" to listOf("com.google.android.youtube"),
            "ಯೂಟ್ಯೂಬ್" to listOf("com.google.android.youtube"),
            "γιουτιουμπ" to listOf("com.google.android.youtube"),
            "油管" to listOf("com.google.android.youtube"),
            // Music / Song across 30 languages
            "music" to listOf("com.google.android.youtube", "com.spotify.music"),
            "song" to listOf("com.google.android.youtube", "com.spotify.music"),
            "গান" to listOf("com.google.android.youtube", "com.spotify.music"),
            "মিউজিক" to listOf("com.google.android.youtube", "com.spotify.music"),
            "गाना" to listOf("com.google.android.youtube", "com.spotify.music"),
            "गीत" to listOf("com.google.android.youtube", "com.spotify.music"),
            "संगीत" to listOf("com.google.android.youtube", "com.spotify.music"),
            "म्यूजिक" to listOf("com.google.android.youtube", "com.spotify.music"),
            "म्युझिक" to listOf("com.google.android.youtube", "com.spotify.music"),
            "गाणं" to listOf("com.google.android.youtube", "com.spotify.music"),
            "गाणे" to listOf("com.google.android.youtube", "com.spotify.music"),
            "گانا" to listOf("com.google.android.youtube", "com.spotify.music"),
            "موسیقی" to listOf("com.google.android.youtube", "com.spotify.music"),
            "موسيقى" to listOf("com.google.android.youtube", "com.spotify.music"),
            "أغنية" to listOf("com.google.android.youtube", "com.spotify.music"),
            "اغنية" to listOf("com.google.android.youtube", "com.spotify.music"),
            "音楽" to listOf("com.google.android.youtube", "com.spotify.music"),
            "曲" to listOf("com.google.android.youtube", "com.spotify.music"),
            "歌" to listOf("com.google.android.youtube", "com.spotify.music"),
            "음악" to listOf("com.google.android.youtube", "com.spotify.music"),
            "노래" to listOf("com.google.android.youtube", "com.spotify.music"),
            "музыка" to listOf("com.google.android.youtube", "com.spotify.music"),
            "музыку" to listOf("com.google.android.youtube", "com.spotify.music"),
            "песня" to listOf("com.google.android.youtube", "com.spotify.music"),
            "музика" to listOf("com.google.android.youtube", "com.spotify.music"),
            "музику" to listOf("com.google.android.youtube", "com.spotify.music"),
            "пісня" to listOf("com.google.android.youtube", "com.spotify.music"),
            "เพลง" to listOf("com.google.android.youtube", "com.spotify.music"),
            "ดนตรี" to listOf("com.google.android.youtube", "com.spotify.music"),
            "பாடல்" to listOf("com.google.android.youtube", "com.spotify.music"),
            "பாட்டு" to listOf("com.google.android.youtube", "com.spotify.music"),
            "இசை" to listOf("com.google.android.youtube", "com.spotify.music"),
            "పాట" to listOf("com.google.android.youtube", "com.spotify.music"),
            "సంగీతం" to listOf("com.google.android.youtube", "com.spotify.music"),
            "ગીત" to listOf("com.google.android.youtube", "com.spotify.music"),
            "સંગીત" to listOf("com.google.android.youtube", "com.spotify.music"),
            "ਗਾਣਾ" to listOf("com.google.android.youtube", "com.spotify.music"),
            "ਗੀਤ" to listOf("com.google.android.youtube", "com.spotify.music"),
            "ਸੰਗੀਤ" to listOf("com.google.android.youtube", "com.spotify.music"),
            "പാട്ട്" to listOf("com.google.android.youtube", "com.spotify.music"),
            "സംഗീതം" to listOf("com.google.android.youtube", "com.spotify.music"),
            "ಹಾಡು" to listOf("com.google.android.youtube", "com.spotify.music"),
            "ಸಂಗೀತ" to listOf("com.google.android.youtube", "com.spotify.music"),
            "μουσική" to listOf("com.google.android.youtube", "com.spotify.music"),
            "τραγούδι" to listOf("com.google.android.youtube", "com.spotify.music"),
            "音乐" to listOf("com.google.android.youtube", "com.spotify.music"),
            "音樂" to listOf("com.google.android.youtube", "com.spotify.music"),
            "歌曲" to listOf("com.google.android.youtube", "com.spotify.music"),
            "música" to listOf("com.google.android.youtube", "com.spotify.music"),
            "musica" to listOf("com.google.android.youtube", "com.spotify.music"),
            "musique" to listOf("com.google.android.youtube", "com.spotify.music"),
            "musik" to listOf("com.google.android.youtube", "com.spotify.music"),
            "müzik" to listOf("com.google.android.youtube", "com.spotify.music"),
            "lagu" to listOf("com.google.android.youtube", "com.spotify.music"),
            "nhạc" to listOf("com.google.android.youtube", "com.spotify.music"),
            "muziek" to listOf("com.google.android.youtube", "com.spotify.music"),
            "muzyka" to listOf("com.google.android.youtube", "com.spotify.music"),
            "muzykę" to listOf("com.google.android.youtube", "com.spotify.music"),
            // Spotify
            "spotify" to listOf("com.spotify.music"),
            "স্পটিফাই" to listOf("com.spotify.music"),
            "स्पॉटिफाई" to listOf("com.spotify.music"),
            "स्पॉटिफाय" to listOf("com.spotify.music"),
            "سپوٹیفائی" to listOf("com.spotify.music"),
            "سبوتيفاي" to listOf("com.spotify.music"),
            "スポティファイ" to listOf("com.spotify.music"),
            "스포티파이" to listOf("com.spotify.music"),
            "спотифай" to listOf("com.spotify.music"),
            "спотіфай" to listOf("com.spotify.music"),
            "สปอติฟาย" to listOf("com.spotify.music"),
            "ஸ்பாட்டிஃபை" to listOf("com.spotify.music"),
            "స్పాటిఫై" to listOf("com.spotify.music"),
            "સ્પોટિફાઇ" to listOf("com.spotify.music"),
            "ਸਪੋਟੀਫਾਈ" to listOf("com.spotify.music"),
            "സ്പോട്ടിഫൈ" to listOf("com.spotify.music"),
            "ಸ್ಪಾಟಿಫೈ" to listOf("com.spotify.music"),
            "σπότιφαϊ" to listOf("com.spotify.music"),
            // WhatsApp
            "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "হোয়াটসঅ্যাপ" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "व्हाट्सएप" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "वॉट्सऐप" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "व्हॉट्सअॅप" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "واٹس ایپ" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "واتساب" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "واتس اب" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "ワッツアップ" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "왓츠앱" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "ватсап" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "вотсап" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "วอทส์แอพ" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "வாட்ஸ்அப்" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "వాట్సాప్" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "વોટ્સએપ" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "ਵਟਸਐਪ" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "വാട്ട്സ്ആപ്പ്" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "ವಾಟ್ಸಾಪ್" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "γουατσαπ" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "γούατσαπ" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            // Chrome
            "chrome" to listOf("com.android.chrome"),
            "ক্রোম" to listOf("com.android.chrome"),
            "क्रोम" to listOf("com.android.chrome"),
            "کروم" to listOf("com.android.chrome"),
            "كروم" to listOf("com.android.chrome"),
            "クローム" to listOf("com.android.chrome"),
            "크롬" to listOf("com.android.chrome"),
            "хром" to listOf("com.android.chrome"),
            "โครม" to listOf("com.android.chrome"),
            "குரோம்" to listOf("com.android.chrome"),
            "క్రోమ్" to listOf("com.android.chrome"),
            "ક્રોમ" to listOf("com.android.chrome"),
            "ਕ੍ਰੋਮ" to listOf("com.android.chrome"),
            "ക്രോം" to listOf("com.android.chrome"),
            "ಕ್ರೋಮ್" to listOf("com.android.chrome"),
            "κρομ" to listOf("com.android.chrome"),
            "浏览器" to listOf("com.android.chrome"),
            "瀏覽器" to listOf("com.android.chrome"),
            // Maps
            "maps" to listOf("com.google.android.apps.maps"),
            "google maps" to listOf("com.google.android.apps.maps"),
            "ম্যাপস" to listOf("com.google.android.apps.maps"),
            "मैप्स" to listOf("com.google.android.apps.maps"),
            "मॅप्स" to listOf("com.google.android.apps.maps"),
            "नक्शा" to listOf("com.google.android.apps.maps"),
            "میپس" to listOf("com.google.android.apps.maps"),
            "نقشہ" to listOf("com.google.android.apps.maps"),
            "خرائط" to listOf("com.google.android.apps.maps"),
            "マップ" to listOf("com.google.android.apps.maps"),
            "地図" to listOf("com.google.android.apps.maps"),
            "지도" to listOf("com.google.android.apps.maps"),
            "맵" to listOf("com.google.android.apps.maps"),
            "карты" to listOf("com.google.android.apps.maps"),
            "карти" to listOf("com.google.android.apps.maps"),
            "แผนที่" to listOf("com.google.android.apps.maps"),
            "மேப்ஸ்" to listOf("com.google.android.apps.maps"),
            "வரைபடம்" to listOf("com.google.android.apps.maps"),
            "మ్యాప్స్" to listOf("com.google.android.apps.maps"),
            "મેપ્સ" to listOf("com.google.android.apps.maps"),
            "ਮੈਪਸ" to listOf("com.google.android.apps.maps"),
            "മാപ്സ്" to listOf("com.google.android.apps.maps"),
            "ಮ್ಯಾಪ್ಸ್" to listOf("com.google.android.apps.maps"),
            "χάρτες" to listOf("com.google.android.apps.maps"),
            "地图" to listOf("com.google.android.apps.maps"),
            "地圖" to listOf("com.google.android.apps.maps"),
            // Gmail
            "gmail" to listOf("com.google.android.gm"),
            "জিমেইল" to listOf("com.google.android.gm"),
            "जीमेल" to listOf("com.google.android.gm"),
            "جی میل" to listOf("com.google.android.gm"),
            "جيميل" to listOf("com.google.android.gm"),
            "ジーメール" to listOf("com.google.android.gm"),
            "지메일" to listOf("com.google.android.gm"),
            "джимейл" to listOf("com.google.android.gm"),
            "จีเมล" to listOf("com.google.android.gm"),
            "ஜிமெயில்" to listOf("com.google.android.gm"),
            "జిమెయిల్" to listOf("com.google.android.gm"),
            "જીમેલ" to listOf("com.google.android.gm"),
            "ਜੀਮੇਲ" to listOf("com.google.android.gm"),
            "ജിമെയിൽ" to listOf("com.google.android.gm"),
            "ಜಿಮೇಲ್" to listOf("com.google.android.gm"),
            "τζιμέιλ" to listOf("com.google.android.gm"),
            // Facebook
            "facebook" to listOf("com.facebook.katana", "com.facebook.lite"),
            "ফেসবুক" to listOf("com.facebook.katana", "com.facebook.lite"),
            "फेसबुक" to listOf("com.facebook.katana", "com.facebook.lite"),
            "فیس بک" to listOf("com.facebook.katana", "com.facebook.lite"),
            "فيسبوك" to listOf("com.facebook.katana", "com.facebook.lite"),
            "フェイスブック" to listOf("com.facebook.katana", "com.facebook.lite"),
            "페이스북" to listOf("com.facebook.katana", "com.facebook.lite"),
            "фейсбук" to listOf("com.facebook.katana", "com.facebook.lite"),
            "เฟซบุ๊ก" to listOf("com.facebook.katana", "com.facebook.lite"),
            "เฟสบุ๊ค" to listOf("com.facebook.katana", "com.facebook.lite"),
            "ஃபேஸ்புக்" to listOf("com.facebook.katana", "com.facebook.lite"),
            "ఫేస్బుక్" to listOf("com.facebook.katana", "com.facebook.lite"),
            "ફેસબુક" to listOf("com.facebook.katana", "com.facebook.lite"),
            "ਫੇਸਬੁੱਕ" to listOf("com.facebook.katana", "com.facebook.lite"),
            "ഫേസ്ബുക്ക്" to listOf("com.facebook.katana", "com.facebook.lite"),
            "ಫೇಸ್ಬುಕ್" to listOf("com.facebook.katana", "com.facebook.lite"),
            "φέισμπουκ" to listOf("com.facebook.katana", "com.facebook.lite"),
            "脸书" to listOf("com.facebook.katana", "com.facebook.lite"),
            "臉書" to listOf("com.facebook.katana", "com.facebook.lite"),
            // Settings
            "settings" to listOf("com.android.settings"),
            "সেটিংস" to listOf("com.android.settings"),
            "सेटिंग्स" to listOf("com.android.settings"),
            "सेटिंग" to listOf("com.android.settings"),
            "सेटिंग्ज" to listOf("com.android.settings"),
            "سیٹنگز" to listOf("com.android.settings"),
            "الإعدادات" to listOf("com.android.settings"),
            "اعدادات" to listOf("com.android.settings"),
            "設定" to listOf("com.android.settings"),
            "설정" to listOf("com.android.settings"),
            "настройки" to listOf("com.android.settings"),
            "налаштування" to listOf("com.android.settings"),
            "ตั้งค่า" to listOf("com.android.settings"),
            "การตั้งค่า" to listOf("com.android.settings"),
            "அமைப்புகள்" to listOf("com.android.settings"),
            "సెట్టింగ్స్" to listOf("com.android.settings"),
            "સેટિંગ્સ" to listOf("com.android.settings"),
            "ਸੈਟਿੰਗਾਂ" to listOf("com.android.settings"),
            "ക്രമീകരണങ്ങൾ" to listOf("com.android.settings"),
            "ಸೆಟ್ಟಿಂಗ್ಸ್" to listOf("com.android.settings"),
            "ρυθμίσεις" to listOf("com.android.settings"),
            "设置" to listOf("com.android.settings"),
            "設置" to listOf("com.android.settings"),
            // Camera
            "camera" to listOf(
                "com.android.camera",
                "com.android.camera2",
                "com.google.android.GoogleCamera",
                "com.sec.android.app.camera",
                "com.oplus.camera"
            ),
            "ক্যামেরা" to listOf(
                "com.android.camera",
                "com.android.camera2",
                "com.google.android.GoogleCamera",
                "com.sec.android.app.camera",
                "com.oplus.camera"
            ),
            "कैमरा" to listOf("com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera", "com.sec.android.app.camera", "com.oplus.camera"),
            "कॅमेरा" to listOf("com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera", "com.sec.android.app.camera", "com.oplus.camera"),
            "کیمرہ" to listOf("com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera", "com.sec.android.app.camera", "com.oplus.camera"),
            "كاميرا" to listOf("com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera", "com.sec.android.app.camera", "com.oplus.camera"),
            "カメラ" to listOf("com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera", "com.sec.android.app.camera", "com.oplus.camera"),
            "카메라" to listOf("com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera", "com.sec.android.app.camera", "com.oplus.camera"),
            "камера" to listOf("com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera", "com.sec.android.app.camera", "com.oplus.camera"),
            "камеру" to listOf("com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera", "com.sec.android.app.camera", "com.oplus.camera"),
            "กล้อง" to listOf("com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera", "com.sec.android.app.camera", "com.oplus.camera"),
            "கேமரா" to listOf("com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera", "com.sec.android.app.camera", "com.oplus.camera"),
            "కెమెరా" to listOf("com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera", "com.sec.android.app.camera", "com.oplus.camera"),
            "કેમેરા" to listOf("com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera", "com.sec.android.app.camera", "com.oplus.camera"),
            "ਕੈਮਰਾ" to listOf("com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera", "com.sec.android.app.camera", "com.oplus.camera"),
            "ക്യാമറ" to listOf("com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera", "com.sec.android.app.camera", "com.oplus.camera"),
            "ಕ್ಯಾಮೆರಾ" to listOf("com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera", "com.sec.android.app.camera", "com.oplus.camera"),
            "κάμερα" to listOf("com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera", "com.sec.android.app.camera", "com.oplus.camera"),
            "相机" to listOf("com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera", "com.sec.android.app.camera", "com.oplus.camera"),
            "相機" to listOf("com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera", "com.sec.android.app.camera", "com.oplus.camera"),
            // Messages
            "messages" to listOf(
                "com.google.android.apps.messaging",
                "com.samsung.android.messaging",
                "com.android.mms"
            ),
            "মেসেজ" to listOf(
                "com.google.android.apps.messaging",
                "com.samsung.android.messaging",
                "com.android.mms"
            ),
            "मैसेज" to listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms"),
            "मेसेज" to listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms"),
            "संदेश" to listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms"),
            "میسج" to listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms"),
            "پیغام" to listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms"),
            "رسائل" to listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms"),
            "メッセージ" to listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms"),
            "메시지" to listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms"),
            "문자" to listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms"),
            "сообщения" to listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms"),
            "повідомлення" to listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms"),
            "ข้อความ" to listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms"),
            "செய்திகள்" to listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms"),
            "మెసేజ్" to listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms"),
            "મેસેજ" to listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms"),
            "ਮੈਸੇਜ" to listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms"),
            "സന്ദേശങ്ങൾ" to listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms"),
            "ಮೆಸೇಜ್" to listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms"),
            "μηνύματα" to listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms"),
            "短信" to listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms"),
            // Phone
            "phone" to listOf(
                "com.google.android.dialer",
                "com.samsung.android.dialer",
                "com.android.dialer"
            ),
            "ফোন" to listOf(
                "com.google.android.dialer",
                "com.samsung.android.dialer",
                "com.android.dialer"
            ),
            "কল" to listOf(
                "com.google.android.dialer",
                "com.samsung.android.dialer",
                "com.android.dialer"
            ),
            "फोन" to listOf("com.google.android.dialer", "com.samsung.android.dialer", "com.android.dialer"),
            "فون" to listOf("com.google.android.dialer", "com.samsung.android.dialer", "com.android.dialer"),
            "هاتف" to listOf("com.google.android.dialer", "com.samsung.android.dialer", "com.android.dialer"),
            "電話" to listOf("com.google.android.dialer", "com.samsung.android.dialer", "com.android.dialer"),
            "전화" to listOf("com.google.android.dialer", "com.samsung.android.dialer", "com.android.dialer"),
            "телефон" to listOf("com.google.android.dialer", "com.samsung.android.dialer", "com.android.dialer"),
            "โทรศัพท์" to listOf("com.google.android.dialer", "com.samsung.android.dialer", "com.android.dialer"),
            "போன்" to listOf("com.google.android.dialer", "com.samsung.android.dialer", "com.android.dialer"),
            "ఫోన్" to listOf("com.google.android.dialer", "com.samsung.android.dialer", "com.android.dialer"),
            "ફોન" to listOf("com.google.android.dialer", "com.samsung.android.dialer", "com.android.dialer"),
            "ਫ਼ੋਨ" to listOf("com.google.android.dialer", "com.samsung.android.dialer", "com.android.dialer"),
            "ഫോൺ" to listOf("com.google.android.dialer", "com.samsung.android.dialer", "com.android.dialer"),
            "ಫೋನ್" to listOf("com.google.android.dialer", "com.samsung.android.dialer", "com.android.dialer"),
            "τηλέφωνο" to listOf("com.google.android.dialer", "com.samsung.android.dialer", "com.android.dialer"),
            "电话" to listOf("com.google.android.dialer", "com.samsung.android.dialer", "com.android.dialer")
        )

        private val BANGLA_TO_ENGLISH_LABEL: Map<String, String> = mapOf(
            "ইউটিউব" to "YouTube",
            "গান" to "YouTube",
            "মিউজিক" to "Music",
            "স্পটিফাই" to "Spotify",
            "হোয়াটসঅ্যাপ" to "WhatsApp",
            "ক্রোম" to "Chrome",
            "ম্যাপস" to "Maps",
            "জিমেইল" to "Gmail",
            "ফেসবুক" to "Facebook",
            "সেটিংস" to "Settings",
            "ক্যামেরা" to "Camera",
            "মেসেজ" to "Messages",
            "ফোন" to "Phone",
            "কল" to "Phone"
        )

        fun resolveKnownAppPackages(query: String): List<String> {
            val q = query.trim().lowercase()
            if (q.isEmpty()) return emptyList()
            KNOWN_APP_ALIASES[q]?.let { return it }
            for ((alias, pkgs) in KNOWN_APP_ALIASES) {
                if (q.contains(alias)) return pkgs
            }
            return emptyList()
        }

        fun resolveKnownAppPackage(query: String): String? =
            resolveKnownAppPackages(query).firstOrNull()

        fun resolveEnglishLabelFallback(query: String): String? {
            val q = query.trim().lowercase()
            if (q.isEmpty()) return null
            BANGLA_TO_ENGLISH_LABEL[q]?.let { return it }
            for ((bangla, eng) in BANGLA_TO_ENGLISH_LABEL) {
                if (q.contains(bangla)) return eng
            }
            return null
        }
    }
}
