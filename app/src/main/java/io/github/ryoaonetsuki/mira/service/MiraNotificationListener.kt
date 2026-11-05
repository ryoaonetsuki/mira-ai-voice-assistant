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
