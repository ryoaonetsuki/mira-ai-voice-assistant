package io.github.ryoaonetsuki.mira.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.ryoaonetsuki.mira.MainActivity
import io.github.ryoaonetsuki.mira.R
import io.github.ryoaonetsuki.mira.data.local.MiraDatabase
import io.github.ryoaonetsuki.mira.data.local.entities.NotificationEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Task completion notification system for Mira AI (ROUND 15 spec §5(b)).
 * Posts a real Android notification when Mira finishes building a website,
 * web app, or code file, especially while backgrounded.
 *
 * Title: "আপনার কাজ হয়ে গেছে"
 * Tapping notification opens the finished project or file in-app via deep link.
 */
object TaskCompletionNotifier {
    const val TAG = "TaskCompletionNotifier"
    const val CHANNEL_ID = "mira_task_completion_channel"
    const val CHANNEL_NAME = "Mira Tasks & Projects"
    const val NOTIFICATION_ID_BASE = 2000
    const val CHAT_NOTIFICATION_ID_BASE = 2500

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when Mira finishes building websites, files, or background projects"
                enableLights(true)
                enableVibration(true)
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    fun buildOpenIntent(context: Context, targetName: String, isProject: Boolean): Intent {
        val uriStr = if (isProject) {
            "mira://project/$targetName"
        } else {
            "mira://file/$targetName"
        }
        return Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(uriStr)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    }

    fun buildChatOpenIntent(context: Context): Intent {
        return Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("mira://chat")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    }

    fun notifyChatReply(
        context: Context,
        replyText: String
    ) {
        try {
            createNotificationChannel(context)

            val openIntent = buildChatOpenIntent(context)
            val pendingIntent = PendingIntent.getActivity(
                context,
                (System.currentTimeMillis() % 10000).toInt(),
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val cleanText = replyText
                .replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("\\s+"), " ")
                .trim()

            val body = when {
                cleanText.length > 100 -> cleanText.take(100).trimEnd() + "..."
                cleanText.isNotEmpty() -> cleanText
                else -> "নতুন বার্তা দেখতে ট্যাপ করুন"
            }

            val title = "Mira answered"

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(R.drawable.ic_notification_mira)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .build()

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val notifId = CHAT_NOTIFICATION_ID_BASE + (System.currentTimeMillis() % 500).toInt()
            manager?.notify(notifId, notification)

            // Save record to in-app Notification database
            try {
                CoroutineScope(Dispatchers.IO).launch {
                    val db = MiraDatabase.getInstance(context.applicationContext)
                    db.notificationDao().insertNotification(
                        NotificationEntity(
                            title = title,
                            message = body,
                            type = "chat"
                        )
                    )
                }
            } catch (e: Throwable) {
                Log.d(TAG, "Notification db insert skipped: ${e.message}")
            }

            Log.i(TAG, "Chat completion notification posted")
        } catch (e: Throwable) {
            Log.e(TAG, "Error posting chat completion notification: ${e.message}", e)
        }
    }

    fun notifyTaskComplete(
        context: Context,
        targetName: String,
        isProject: Boolean
    ) {
        try {
            createNotificationChannel(context)

            val openIntent = buildOpenIntent(context, targetName, isProject)
            val pendingIntent = PendingIntent.getActivity(
                context,
                (System.currentTimeMillis() % 10000).toInt(),
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val title = "আপনার কাজ হয়ে গেছে"
            val body = if (isProject) {
                "আপনার ওয়েবসাইট '$targetName' প্রস্তুত — দেখতে ট্যাপ করুন"
            } else {
                "আপনার ফাইলটি '$targetName' তৈরি হয়ে গেছে — দেখতে ট্যাপ করুন"
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(R.drawable.ic_notification_mira)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .build()

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val notifId = NOTIFICATION_ID_BASE + (System.currentTimeMillis() % 500).toInt()
            manager?.notify(notifId, notification)

            // Save record to in-app Notification database
            try {
                CoroutineScope(Dispatchers.IO).launch {
                    val db = MiraDatabase.getInstance(context.applicationContext)
                    db.notificationDao().insertNotification(
                        NotificationEntity(
                            title = title,
                            message = body,
                            type = if (isProject) "project" else "file"
                        )
                    )
                }
            } catch (e: Throwable) {
                Log.d(TAG, "Notification db insert skipped: ${e.message}")
            }

            Log.i(TAG, "Task completion notification posted for '$targetName' (isProject=$isProject)")
        } catch (e: Throwable) {
            Log.e(TAG, "Error posting task completion notification: ${e.message}", e)
        }
    }
}
