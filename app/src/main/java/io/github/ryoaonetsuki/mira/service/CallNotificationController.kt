package io.github.ryoaonetsuki.mira.service

import android.app.Notification
import io.github.ryoaonetsuki.mira.voice.pipecat.SessionManagerState

/**
 * Manages foreground state tracking and CallStyle notification posting decisions for [MicService].
 *
 * Requirements:
 * 1. CallStyle notifications must ONLY be posted/updated via [startForegroundAction] while [isForeground] is true.
 * 2. Explicitly track [isForeground]: set to true in [startForegroundWithNotification], set to false in [dismissNotification] BEFORE calling stopForeground.
 * 3. If [isForeground] is false, notification posting is a strict no-op (never plain notify, never fallback plain notification).
 * 4. Stop sequence (STOPPED / ERROR) dismisses first and NEVER posts a notification afterwards.
 */
class CallNotificationController(
    private val startForegroundAction: (id: Int, notification: Notification) -> Unit,
    private val stopForegroundAction: () -> Unit,
    private val cancelNotificationAction: (id: Int) -> Unit
) {
    @Volatile
    var isForeground: Boolean = false
        private set

    /**
     * Enters foreground mode and posts the CallStyle notification.
     */
    fun startForegroundWithNotification(notification: Notification, notificationId: Int = NOTIFICATION_ID) {
        isForeground = true
        startForegroundAction(notificationId, notification)
    }

    /**
     * Posts/updates the CallStyle notification ONLY if the service is currently in foreground.
     * Returns true if posted via startForegroundAction, false if ignored because service is not in foreground.
     */
    fun postCallNotificationIfForeground(
        notificationId: Int = NOTIFICATION_ID,
        notificationProvider: () -> Notification
    ): Boolean {
        if (!isForeground) {
            return false
        }
        val notification = notificationProvider()
        startForegroundAction(notificationId, notification)
        return true
    }

    /**
     * Dismisses the notification: sets [isForeground] = false BEFORE stopping foreground and cancelling.
     */
    fun dismissNotification(notificationId: Int = NOTIFICATION_ID) {
        isForeground = false
        stopForegroundAction()
        cancelNotificationAction(notificationId)
    }

    /**
     * Evaluates whether a session state transition should dismiss or post/update a notification.
     * Returns true if notification was posted/updated, false if dismissed or skipped.
     */
    fun handleSessionState(
        state: SessionManagerState,
        notificationId: Int = NOTIFICATION_ID,
        notificationProvider: () -> Notification
    ): Boolean {
        return when (state) {
            SessionManagerState.STOPPED, SessionManagerState.ERROR -> {
                dismissNotification(notificationId)
                false
            }
            else -> {
                postCallNotificationIfForeground(notificationId, notificationProvider)
            }
        }
    }

    companion object {
        const val NOTIFICATION_ID = 1001
    }
}
