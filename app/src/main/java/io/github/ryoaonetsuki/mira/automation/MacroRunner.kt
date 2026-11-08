package io.github.ryoaonetsuki.mira.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import io.github.ryoaonetsuki.mira.MainActivity
import io.github.ryoaonetsuki.mira.R
import io.github.ryoaonetsuki.mira.data.model.MacroActionType
import io.github.ryoaonetsuki.mira.data.model.MacroItem
import io.github.ryoaonetsuki.mira.service.MiraAccessibilityService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeoutException

sealed class AutomationStatus {
    object Idle : AutomationStatus()
    data class Running(
        val macroTitle: String,
        val stepIndex: Int,
        val totalSteps: Int,
        val stepDescription: String
    ) : AutomationStatus()
    data class Success(val macroTitle: String, val message: String) : AutomationStatus()
    data class Failed(val macroTitle: String, val error: String) : AutomationStatus()
    data class Cancelled(val macroTitle: String, val reason: String) : AutomationStatus()
}

object MacroRunner {

    private const val TAG = "MiraMacroRunner"
    private const val CHANNEL_ID = "mira_macro_screen_control"
    private const val NOTIFICATION_ID = 2002

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var activeJob: Job? = null
    private var currentMacroTitle: String = ""
    private var lastPackageRun: String = ""

    private val _status = MutableStateFlow<AutomationStatus>(AutomationStatus.Idle)
    val status: StateFlow<AutomationStatus> = _status.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _currentStepText = MutableStateFlow("")
    val currentStepText: StateFlow<String> = _currentStepText.asStateFlow()

    /**
     * Cancel currently running macro automation cleanly.
     */
    fun cancel(reason: String = "Stopped by user") {
        if (_isRunning.value) {
            Log.i(TAG, "Cancelling automation: $reason")
            activeJob?.cancel(CancellationException(reason))
            activeJob = null
            _isRunning.value = false
            _status.value = AutomationStatus.Cancelled(currentMacroTitle, reason)
            _currentStepText.value = "Automation stopped: $reason"

            // If YouTube was active, try to pause playback
            if (lastPackageRun == "com.google.android.youtube") {
                scope.launch {
                    attemptPauseYouTube()
                }
            }
        }
    }

    /**
     * Main entry point to run a macro with on-device screen automation.
     */
    fun runMacro(
        context: Context,
        macro: MacroItem,
        param: String,
        extraMessage: String = ""
    ) {
        if (_isRunning.value) {
            cancel("Starting new macro request")
        }

        currentMacroTitle = macro.title
        _isRunning.value = true
        _status.value = AutomationStatus.Running(macro.title, 1, 4, "Initializing automation")
        _currentStepText.value = "Starting ${macro.title}"
        createNotificationChannel(context)
        showNotification(context, macro.title, "Starting automation")

        activeJob = scope.launch {
            try {
                when (macro.actionType) {
                    MacroActionType.WHATSAPP -> runWhatsAppFlow(context, param, extraMessage)
                    MacroActionType.YOUTUBE -> runYouTubeFlow(context, param)
                    MacroActionType.MAPS -> runMapsFlow(context, param)
                    MacroActionType.CALL -> runCallFlow(context, param)
                    MacroActionType.SMS -> runSmsFlow(context, param, extraMessage)
                    MacroActionType.WEB_SEARCH -> runWebSearchFlow(context, param)
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "Macro coroutine cancelled: ${e.message}")
                _status.value = AutomationStatus.Cancelled(macro.title, e.message ?: "Cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Macro execution failed: ${e.message}", e)
                _status.value = AutomationStatus.Failed(macro.title, e.message ?: "Automation step failed")
                _currentStepText.value = "Failed: ${e.message ?: "Unknown error"}"
            } finally {
                _isRunning.value = false
                dismissNotification(context)
            }
        }
    }

    // ==========================================
    // CONCRETE AUTOMATION FLOWS
    // ==========================================

    /**
     * WHATSAPP Screen Automation:
     * (1) open WhatsApp, (2) tap Search, type number/name, wait, (3) tap chat, (4) find message box, type, tap Send.
     */
    private suspend fun runWhatsAppFlow(context: Context, target: String, message: String) {
        val recipient = target.ifBlank { "+1234567890" }
        val msg = message.ifBlank { "Hello from Mira AI" }
        lastPackageRun = "com.whatsapp"

        // Step 1: Open WhatsApp
        updateStep(1, 4, "Opening WhatsApp")
        showNotification(context, "WhatsApp", "Step 1/4: Opening WhatsApp")
        if (!launchApp(context, "com.whatsapp")) {
            throw IllegalStateException("WhatsApp is not installed on this device")
        }
        pollForNode(timeoutMs = 8000L, description = "WhatsApp app window") {
            it.packageName == "com.whatsapp"
        }
        delay(600L)

        // Step 2: Tap Search, type number/name, wait for result
        updateStep(2, 4, "Searching for $recipient")
        showNotification(context, "WhatsApp", "Step 2/4: Searching for $recipient")
        val searchBtn = pollForNode(timeoutMs = 6000L, description = "WhatsApp search button") {
            matchesId(it, "menuitem_search", "search_holder", "search_btn") ||
                matchesTextOrDesc(it, "Search", "অনুসন্ধান", "খুঁজুন")
        }
        clickNode(searchBtn)
        delay(400L)

        val searchInput = pollForNode(timeoutMs = 6000L, description = "WhatsApp search text box") {
            matchesId(it, "search_src_text", "search_input") ||
                (it.className?.contains("EditText") == true) ||
                matchesTextOrDesc(it, "Search", "অনুসন্ধান", "খুঁজুন")
        }
        setNodeText(searchInput, recipient)
        delay(1200L) // Wait for search results to populate

        // Step 3: Tap that chat to open it
        updateStep(3, 4, "Opening chat with $recipient")
        showNotification(context, "WhatsApp", "Step 3/4: Opening chat with $recipient")
        val chatResult = pollForNode(timeoutMs = 7000L, description = "Search result contact or chat") {
            it.packageName == "com.whatsapp" && (
                matchesTextOrDesc(it, recipient) ||
                    matchesId(it, "conversations_row", "contact_row", "chat_row", "contact_title") ||
                    it.className?.contains("RelativeLayout") == true ||
                    it.className?.contains("ViewGroup") == true
            )
        }
        clickNode(chatResult)
        delay(800L)

        // Step 4: Find message box, type message, tap Send
        updateStep(4, 4, "Typing message and sending")
        showNotification(context, "WhatsApp", "Step 4/4: Typing message and sending")
        val messageBox = pollForNode(timeoutMs = 7000L, description = "WhatsApp message entry field") {
            matchesId(it, "entry", "message_entry", "input") ||
                (it.className?.contains("EditText") == true) ||
                matchesTextOrDesc(it, "Message", "বার্তা", "Type a message")
        }
        setNodeText(messageBox, msg)
        delay(400L)

        val sendBtn = pollForNode(timeoutMs = 5000L, description = "WhatsApp send button") {
            matchesId(it, "send") ||
                matchesTextOrDesc(it, "Send", "পাঠান")
        }
        clickNode(sendBtn)
        delay(500L)

        _status.value = AutomationStatus.Success("WhatsApp", "Message sent to $recipient")
        _currentStepText.value = "WhatsApp message sent to $recipient"
    }

    /**
     * YOUTUBE Screen Automation:
     * Open YouTube -> tap Search -> type the song -> wait for results -> tap FIRST video result -> plays.
     */
    private suspend fun runYouTubeFlow(context: Context, songQuery: String) {
        val query = songQuery.ifBlank { "Lofi hip hop radio beats to relax" }
        lastPackageRun = "com.google.android.youtube"

        // Step 1: Open YouTube
        updateStep(1, 5, "Opening YouTube")
        showNotification(context, "YouTube", "Step 1/5: Opening YouTube")
        if (!launchApp(context, "com.google.android.youtube")) {
            throw IllegalStateException("YouTube app is not installed on this device")
        }
        pollForNode(timeoutMs = 8000L, description = "YouTube app window") {
            it.packageName == "com.google.android.youtube"
        }
        delay(700L)

        // Step 2: Tap Search
        updateStep(2, 5, "Tapping Search")
        showNotification(context, "YouTube", "Step 2/5: Tapping Search")
        val searchIcon = pollForNode(timeoutMs = 6000L, description = "YouTube search icon") {
            matchesId(it, "menu_item_view", "search") ||
                matchesTextOrDesc(it, "Search", "Search YouTube", "অনুসন্ধান", "খুঁজুন")
        }
        clickNode(searchIcon)
        delay(400L)

        // Step 3: Type song and submit
        updateStep(3, 5, "Searching for '$query'")
        showNotification(context, "YouTube", "Step 3/5: Searching for '$query'")
        val searchBox = pollForNode(timeoutMs = 6000L, description = "YouTube search box") {
            matchesId(it, "search_edit_text") ||
                (it.className?.contains("EditText") == true)
        }
        setNodeText(searchBox, query)
        delay(800L)

        // Try clicking search suggestion or search button
        val searchAction = findNodes(getRootNode()) {
            matchesId(it, "search_btn", "suggestion_text", "text1") ||
                matchesTextOrDesc(it, query)
        }.firstOrNull()
        if (searchAction != null) {
            clickNode(searchAction)
        }
        delay(1200L)

        // Step 4: Wait for results
        updateStep(4, 5, "Waiting for video results")
        showNotification(context, "YouTube", "Step 4/5: Waiting for results")
        val firstVideo = pollForNode(timeoutMs = 8000L, description = "First YouTube video result") {
            it.packageName == "com.google.android.youtube" && (
                matchesId(it, "video_info_view", "title", "thumbnail", "watch_panel") ||
                    (it.isClickable && matchesTextOrDesc(it, query))
            )
        }

        // Step 5: Tap FIRST video result -> it plays!
        updateStep(5, 5, "Playing first video")
        showNotification(context, "YouTube", "Step 5/5: Playing video")
        clickNode(firstVideo)
        delay(1000L)

        _status.value = AutomationStatus.Success("YouTube", "Playing '$query' on YouTube")
        _currentStepText.value = "Playing '$query' on YouTube"
    }

    /**
     * Pauses YouTube playback if on screen when stopped.
     */
    private fun attemptPauseYouTube() {
        val root = getRootNode() ?: return
        val pauseBtn = findNodes(root) {
            matchesId(it, "player_pause_button", "pause_button") ||
                matchesTextOrDesc(it, "Pause", "Pause video", "বিরতি")
        }.firstOrNull()

        if (pauseBtn != null) {
            clickNode(pauseBtn)
            Log.i(TAG, "Tapped YouTube pause button on stop")
        } else {
            // Send back key to back out of full screen / playback
            MiraAccessibilityService.instance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            Log.i(TAG, "Sent back action for YouTube stop")
        }
    }

    /**
     * MAPS Screen Automation:
     * Open Maps -> tap search -> type destination -> tap first result -> tap Directions/Start.
     */
    private suspend fun runMapsFlow(context: Context, destination: String) {
        val query = destination.ifBlank { "Nearest coffee shop" }
        lastPackageRun = "com.google.android.apps.maps"

        updateStep(1, 4, "Opening Google Maps")
        showNotification(context, "Maps", "Step 1/4: Opening Maps")
        if (!launchApp(context, "com.google.android.apps.maps")) {
            throw IllegalStateException("Google Maps is not installed on this device")
        }
        pollForNode(timeoutMs = 8000L, description = "Google Maps window") {
            it.packageName == "com.google.android.apps.maps"
        }
        delay(700L)

        updateStep(2, 4, "Tapping search bar")
        showNotification(context, "Maps", "Step 2/4: Tapping search")
        val searchBox = pollForNode(timeoutMs = 6000L, description = "Maps search bar") {
            matchesId(it, "search_omnibox_text_box", "textbox", "search") ||
                matchesTextOrDesc(it, "Search here", "Search", "এখানে খুঁজুন")
        }
        clickNode(searchBox)
        delay(400L)

        updateStep(3, 4, "Searching for '$query'")
        showNotification(context, "Maps", "Step 3/4: Searching for '$query'")
        val searchInput = pollForNode(timeoutMs = 6000L, description = "Maps destination input") {
            (it.className?.contains("EditText") == true) ||
                matchesId(it, "search_omnibox_edit_text", "search_text")
        }
        setNodeText(searchInput, query)
        delay(1200L)

        // Tap first suggestion
        val firstResult = findNodes(getRootNode()) {
            it.packageName == "com.google.android.apps.maps" && (
                matchesId(it, "title", "suggestion", "text") ||
                    matchesTextOrDesc(it, query)
            )
        }.firstOrNull()
        if (firstResult != null) {
            clickNode(firstResult)
        }
        delay(1200L)

        // Step 4: Tap Directions or Start if present
        updateStep(4, 4, "Loading directions")
        showNotification(context, "Maps", "Step 4/4: Loading directions")
        val directionsBtn = findNodes(getRootNode()) {
            matchesTextOrDesc(it, "Directions", "Start", "দিকনির্দেশ", "শুরু", "Navigate")
        }.firstOrNull()
        if (directionsBtn != null) {
            clickNode(directionsBtn)
        }

        _status.value = AutomationStatus.Success("Maps", "Directions loaded for '$query'")
        _currentStepText.value = "Directions loaded for '$query'"
    }

    /**
     * CALL Screen Automation:
     * Open dialer -> type number -> tap call.
     */
    private suspend fun runCallFlow(context: Context, number: String) {
        val num = number.ifBlank { "911" }
        lastPackageRun = "call"

        updateStep(1, 2, "Opening dialer")
        showNotification(context, "Phone Call", "Step 1/2: Opening dialer")
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(num)}")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        delay(1000L)

        updateStep(2, 2, "Placing call to $num")
        showNotification(context, "Phone Call", "Step 2/2: Placing call")
        val callBtn = findNodes(getRootNode()) {
            matchesId(it, "dial_button", "call", "fab") ||
                matchesTextOrDesc(it, "Call", "কল", "Dial")
        }.firstOrNull()
        if (callBtn != null) {
            clickNode(callBtn)
        }

        _status.value = AutomationStatus.Success("Call", "Dialer active for $num")
        _currentStepText.value = "Dialer active for $num"
    }

    /**
     * SMS Screen Automation:
     * Open Messages -> tap compose/new -> type recipient -> type body -> tap send.
     */
    private suspend fun runSmsFlow(context: Context, recipient: String, body: String) {
        val target = recipient.ifBlank { "+1234567890" }
        val msgText = body.ifBlank { "Hello from Mira AI" }
        lastPackageRun = "sms"

        updateStep(1, 4, "Opening Messages")
        showNotification(context, "Messages", "Step 1/4: Opening Messages")
        val launched = launchApp(context, "com.google.android.apps.messaging")
        if (!launched) {
            val fallbackIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(target)}")).apply {
                putExtra("sms_body", msgText)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(fallbackIntent)
            _status.value = AutomationStatus.Success("SMS", "Drafted SMS to $target")
            return
        }
        delay(800L)

        updateStep(2, 4, "Creating new message")
        showNotification(context, "Messages", "Step 2/4: Creating new message")
        val composeBtn = findNodes(getRootNode()) {
            matchesId(it, "start_chat_fab", "compose", "fab") ||
                matchesTextOrDesc(it, "Start chat", "New conversation", "Compose", "নতুন বার্তা")
        }.firstOrNull()
        if (composeBtn != null) {
            clickNode(composeBtn)
            delay(800L)
        }

        updateStep(3, 4, "Entering recipient $target")
        showNotification(context, "Messages", "Step 3/4: Entering recipient")
        val recipientInput = findNodes(getRootNode()) {
            it.className?.contains("EditText") == true
        }.firstOrNull()
        if (recipientInput != null) {
            setNodeText(recipientInput, target)
            delay(800L)
        }

        updateStep(4, 4, "Typing message and sending")
        showNotification(context, "Messages", "Step 4/4: Sending message")
        val bodyInput = findNodes(getRootNode()) {
            matchesId(it, "compose_message_text", "message_text") ||
                (it.className?.contains("EditText") == true)
        }.firstOrNull()
        if (bodyInput != null) {
            setNodeText(bodyInput, msgText)
            delay(400L)
        }

        val sendBtn = findNodes(getRootNode()) {
            matchesId(it, "send_message_button", "send") ||
                matchesTextOrDesc(it, "Send", "পাঠান")
        }.firstOrNull()
        if (sendBtn != null) {
            clickNode(sendBtn)
        }

        _status.value = AutomationStatus.Success("SMS", "SMS automation completed for $target")
        _currentStepText.value = "SMS sent to $target"
    }

    /**
     * WEB_SEARCH Screen Automation:
     * Open Chrome -> tap address bar -> type query -> submit.
     */
    private suspend fun runWebSearchFlow(context: Context, query: String) {
        val q = query.ifBlank { "Latest science news" }
        lastPackageRun = "com.android.chrome"

        updateStep(1, 3, "Opening Chrome browser")
        showNotification(context, "Web Search", "Step 1/3: Opening Chrome")
        val launched = launchApp(context, "com.android.chrome")
        if (!launched) {
            // Fallback to intent
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=${Uri.encode(q)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(browserIntent)
            _status.value = AutomationStatus.Success("Search", "Opened web search for '$q'")
            return
        }
        delay(800L)

        updateStep(2, 3, "Tapping address bar")
        showNotification(context, "Web Search", "Step 2/3: Tapping address bar")
        val addressBar = pollForNode(timeoutMs = 6000L, description = "Chrome address bar") {
            matchesId(it, "url_bar", "search_box_text") ||
                matchesTextOrDesc(it, "Search or type URL", "Search")
        }
        clickNode(addressBar)
        delay(400L)

        updateStep(3, 3, "Searching for '$q'")
        showNotification(context, "Web Search", "Step 3/3: Searching for '$q'")
        val searchInput = pollForNode(timeoutMs = 6000L, description = "Address bar input") {
            (it.className?.contains("EditText") == true) || matchesId(it, "url_bar")
        }
        setNodeText(searchInput, "https://duckduckgo.com/?q=${Uri.encode(q)}")
        delay(500L)

        _status.value = AutomationStatus.Success("Search", "Web search loaded for '$q'")
        _currentStepText.value = "Web search loaded for '$q'"
    }

    // ==========================================
    // ACCESSIBILITY NODE HELPER PRIMITIVES
    // ==========================================

    fun getRootNode(): AccessibilityNodeInfo? {
        val service = MiraAccessibilityService.instance ?: return null
        service.rootInActiveWindow?.let { return it }

        try {
            val windows = service.windows
            for (w in windows) {
                val r = w.root
                if (r != null) return r
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not retrieve windows from service: ${e.message}")
        }
        return null
    }

    suspend fun pollForNode(
        timeoutMs: Long = 8000L,
        pollIntervalMs: Long = 300L,
        description: String,
        matcher: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            currentCoroutineContext().ensureActive()
            val root = getRootNode()
            if (root != null) {
                val matched = findNodes(root, predicate = matcher)
                if (matched.isNotEmpty()) {
                    return matched.first()
                }
            }
            delay(pollIntervalMs)
        }
        throw TimeoutException("Timed out waiting for '$description' after ${timeoutMs / 1000}s")
    }

    fun findNodes(
        root: AccessibilityNodeInfo?,
        maxDepth: Int = 40,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val results = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)

        while (queue.isNotEmpty()) {
            val (node, depth) = queue.removeFirst()
            if (predicate(node)) {
                results.add(node)
            }
            if (depth < maxDepth) {
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    if (child != null) {
                        queue.add(child to depth + 1)
                    }
                }
            }
        }
        return results
    }

    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                val performed = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (performed) return true
            }
            current = current.parent
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun launchApp(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(launchIntent)
            return true
        }
        return false
    }

    fun matchesTextOrDesc(node: AccessibilityNodeInfo, vararg queries: String): Boolean {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        return queries.any { q ->
            text.contains(q, ignoreCase = true) || desc.contains(q, ignoreCase = true)
        }
    }

    fun matchesId(node: AccessibilityNodeInfo, vararg idSubstrings: String): Boolean {
        val viewId = node.viewIdResourceName ?: ""
        return idSubstrings.any { sub ->
            viewId.contains(sub, ignoreCase = true)
        }
    }

    private fun updateStep(stepIndex: Int, totalSteps: Int, stepDescription: String) {
        _status.value = AutomationStatus.Running(currentMacroTitle, stepIndex, totalSteps, stepDescription)
        _currentStepText.value = "Step $stepIndex/$totalSteps: $stepDescription"
        Log.i(TAG, "Automation Step $stepIndex/$totalSteps: $stepDescription")
    }

    // ==========================================
    // ONGOING NOTIFICATION WITH STOP ACTION
    // ==========================================

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mira Screen Automation",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shows live status while Mira AI automates on-screen actions"
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(context: Context, title: String, stepText: String) {
        val stopIntent = Intent(context, MacroStopReceiver::class.java)
        val stopPendingIntent = PendingIntent.getBroadcast(
            context,
            201,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(context, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            context,
            202,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Mira is controlling the screen")
            .setContentText("$title — $stepText")
            .setSmallIcon(R.drawable.ic_notification_mira)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun dismissNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }
}
