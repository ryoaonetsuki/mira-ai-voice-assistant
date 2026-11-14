package io.github.ryoaonetsuki.mira.service

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import io.github.ryoaonetsuki.mira.data.local.MiraDatabase
import io.github.ryoaonetsuki.mira.data.local.entities.NotificationEntity
import io.github.ryoaonetsuki.mira.data.preferences.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MiraNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val guard = WhatsAppAutoReplyGuard()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName
        if (packageName == "com.whatsapp" || packageName == "com.whatsapp.w4b") {
            handleWhatsAppNotification(sbn)
        }
    }

    private fun handleWhatsAppNotification(sbn: StatusBarNotification) {
        scope.launch {
            try {
                val userPrefs = UserPreferences(applicationContext)
                val isAutoReplyEnabled = userPrefs.whatsappAutoReplyFlow.first()
                if (!isAutoReplyEnabled) return@launch

                val notification = sbn.notification ?: return@launch
                val extras = notification.extras ?: return@launch
                val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
                val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

                if (title.isBlank() || text.isBlank()) return@launch

                // Don't reply to group summaries or empty messages
                if (title.contains("messages from") || title.contains("WhatsApp")) return@launch

                val template = userPrefs.autoReplyTemplateFlow.first()

                // Derive latestIncomingText from EXTRA_MESSAGES, fall back to EXTRA_TEXT
                val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.filterIsInstance<Notification.MessagingStyle.Message>() ?: emptyList()
                val latestIncomingText: String? = if (messages.isNotEmpty()) {
                    val last = messages.last()
                    if (last.sender == null) null else last.text?.toString()
                } else {
                    text
                }

                if (!guard.shouldReply(title, latestIncomingText, template, sbn.key, sbn.postTime, System.currentTimeMillis())) return@launch

                // Look for wearable action / quick reply action with RemoteInput
                val actions = notification.actions ?: return@launch
                for (action in actions) {
                    val remoteInputs = action.remoteInputs
                    if (remoteInputs != null && remoteInputs.isNotEmpty()) {
                        for (remoteInput in remoteInputs) {
                            val replyIntent = Intent()
                            val bundle = Bundle()
                            bundle.putCharSequence(remoteInput.resultKey, template)
                            RemoteInput.addResultsToIntent(arrayOf(remoteInput), replyIntent, bundle)

                            action.actionIntent.send(applicationContext, 0, replyIntent)
                            Log.i(TAG, "Sent WhatsApp auto-reply to '$title': $template")

                            // Save notification log to Room
                            val db = MiraDatabase.getInstance(applicationContext)
                            db.notificationDao().insertNotification(
                                NotificationEntity(
                                    title = "WhatsApp Auto-Reply",
                                    message = "Replied to $title: \"$template\"",
                                    type = "info"
                                )
                            )
                            return@launch
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to auto-reply to WhatsApp notification: ${e.message}", e)
            }
        }
    }

    companion object {
        private const val TAG = "MiraNotifListener"
    }
}

class WhatsAppAutoReplyGuard {
    companion object {
        const val RATE_LIMIT_MS = 60_000L
        const val DEDUP_WINDOW_MS = 300_000L
        const val RECENCY_MS = 120_000L
    }

    private val repliedKeys = mutableMapOf<String, Long>()
    private val conversationLastReply = mutableMapOf<String, Long>()

    fun shouldReply(
        conversation: String,
        latestIncomingText: String?,
        template: String,
        notificationKey: String,
        postTimeMs: Long,
        nowMs: Long
    ): Boolean {
        // (a) latestIncomingText null/blank → false
        if (latestIncomingText.isNullOrBlank()) return false
        // (b) blank template → false
        if (template.isBlank()) return false
        // (c) latestIncomingText.trim() == template.trim() → false
        if (latestIncomingText.trim() == template.trim()) return false
        // (d) stale notification → false
        if (nowMs - postTimeMs > RECENCY_MS || postTimeMs > nowMs) return false

        val key = "$conversation|${latestIncomingText.trim()}|$notificationKey"

        return synchronized(this) {
            // Prune entries older than DEDUP_WINDOW_MS
            val cutoff = nowMs - DEDUP_WINDOW_MS
            repliedKeys.entries.removeIf { it.value < cutoff }
            conversationLastReply.entries.removeIf { it.value < cutoff }

            // (e) dedup: same key replied within DEDUP_WINDOW_MS → false
            val lastReplied = repliedKeys[key]
            if (lastReplied != null && nowMs - lastReplied <= DEDUP_WINDOW_MS) return false

            // (f) rate limit: conversation replied within RATE_LIMIT_MS → false
            val lastConversationReply = conversationLastReply[conversation]
            if (lastConversationReply != null && nowMs - lastConversationReply <= RATE_LIMIT_MS) return false

            // Record this reply
            repliedKeys[key] = nowMs
            conversationLastReply[conversation] = nowMs
            true
        }
    }
}
