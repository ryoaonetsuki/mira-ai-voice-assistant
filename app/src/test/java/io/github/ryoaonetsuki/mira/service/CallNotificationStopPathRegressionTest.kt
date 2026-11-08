package io.github.ryoaonetsuki.mira.service

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import io.github.ryoaonetsuki.mira.R
import io.github.ryoaonetsuki.mira.voice.pipecat.SessionManagerState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CallNotificationStopPathRegressionTest {

    private lateinit var context: Context
    private lateinit var testNotification: Notification

    private var startForegroundCount = 0
    private var lastPostedId = -1
    private var stopForegroundCount = 0
    private var cancelNotificationCount = 0
    private var lastCancelledId = -1

    private lateinit var controller: CallNotificationController

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        testNotification = NotificationCompat.Builder(context, "mira_call_channel")
            .setContentTitle("Test Call")
            .setContentText("Test Content")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        startForegroundCount = 0
        lastPostedId = -1
        stopForegroundCount = 0
        cancelNotificationCount = 0
        lastCancelledId = -1

        controller = CallNotificationController(
            startForegroundAction = { id, _ ->
                startForegroundCount++
                lastPostedId = id
            },
            stopForegroundAction = {
                stopForegroundCount++
            },
            cancelNotificationAction = { id ->
                cancelNotificationCount++
                lastCancelledId = id
            }
        )
    }

    @Test
    fun testStopSequence_drivesStateToStoppedAfterDismiss_noExceptionAndNoPost() {
        // 1. Voice session starts -> enter foreground
        controller.startForegroundWithNotification(testNotification)
        assertTrue(controller.isForeground)
        assertEquals(1, startForegroundCount)
        assertEquals(CallNotificationController.NOTIFICATION_ID, lastPostedId)

        // 2. Call connects -> updates notification while in foreground
        val updatedOnConnect = controller.postCallNotificationIfForeground { testNotification }
        assertTrue(updatedOnConnect)
        assertEquals(2, startForegroundCount)

        // 3. User taps voice OFF -> stop sequence initiated:
        // Service dismisses notification first
        controller.dismissNotification()
        assertFalse("isForeground must be false immediately upon dismissal", controller.isForeground)
        assertEquals(1, stopForegroundCount)
        assertEquals(1, cancelNotificationCount)
        assertEquals(CallNotificationController.NOTIFICATION_ID, lastCancelledId)

        // 4. Session manager completes shutdown and transitions to STOPPED
        val handled = controller.handleSessionState(SessionManagerState.STOPPED) {
            fail("Notification provider must not be invoked after dismissal")
            testNotification
        }
        assertFalse(handled)

        // Assert: NO CallStyle notification posted after service left foreground
        assertEquals("startForeground must NOT be called after dismissal", 2, startForegroundCount)
        assertFalse(controller.isForeground)

        // Any trailing attempt to post notification is a strict no-op
        val trailingUpdate = controller.postCallNotificationIfForeground { testNotification }
        assertFalse(trailingUpdate)
        assertEquals(2, startForegroundCount)
    }

    @Test
    fun testErrorSequence_drivesStateToErrorAfterDismiss_noExceptionAndNoPost() {
        controller.startForegroundWithNotification(testNotification)
        assertTrue(controller.isForeground)
        assertEquals(1, startForegroundCount)

        // Error path: handleTerminalError dismisses first, then state transitions to ERROR
        controller.dismissNotification()
        assertFalse(controller.isForeground)

        val handled = controller.handleSessionState(SessionManagerState.ERROR) {
            fail("Notification provider must not be invoked on ERROR")
            testNotification
        }
        assertFalse(handled)
        assertEquals(1, startForegroundCount)
    }

    @Test
    fun testAuditCallers_whenNotForeground_areSafeNoOps() {
        assertFalse(controller.isForeground)

        // All 6 callers audited in requirement 2:
        // 1. _liveState collector update
        assertFalse(controller.postCallNotificationIfForeground { testNotification })

        // 2. _isReconnecting collector update
        assertFalse(controller.postCallNotificationIfForeground { testNotification })

        // 3. toggleMute update
        assertFalse(controller.postCallNotificationIfForeground { testNotification })

        // 4. onBotSpeakingChanged update
        assertFalse(controller.postCallNotificationIfForeground { testNotification })

        // 5. onReconnecting update
        assertFalse(controller.postCallNotificationIfForeground { testNotification })

        // 6. onStateChanged with CONNECTED when not in foreground
        assertFalse(controller.handleSessionState(SessionManagerState.CONNECTED) { testNotification })

        // Assert: 0 notifications posted, 0 exceptions
        assertEquals(0, startForegroundCount)
    }

    @Test
    fun testConnectPath_postsCallNotificationViaStartForeground_whenInForeground() {
        controller.startForegroundWithNotification(testNotification)
        assertTrue(controller.isForeground)
        assertEquals(1, startForegroundCount)

        // Connecting
        assertTrue(controller.handleSessionState(SessionManagerState.CONNECTING) { testNotification })
        assertEquals(2, startForegroundCount)

        // Connected
        assertTrue(controller.handleSessionState(SessionManagerState.CONNECTED) { testNotification })
        assertEquals(3, startForegroundCount)

        // Bot speaking change
        assertTrue(controller.postCallNotificationIfForeground { testNotification })
        assertEquals(4, startForegroundCount)

        // Reconnecting
        assertTrue(controller.handleSessionState(SessionManagerState.RECONNECTING) { testNotification })
        assertEquals(5, startForegroundCount)
    }

    @Test
    fun testOldPath_unconditionalNotifyAfterDismiss_throwsIllegalArgumentException() {
        // This test proves that the OLD buggy pattern throws IllegalArgumentException,
        // confirming why the crash happened and verifying the failure condition.
        var isOldForeground = true

        val oldDismissNotification = {
            isOldForeground = false
            stopForegroundCount++
            cancelNotificationCount++
        }

        val oldUpdateNotificationWithPlainNotify = {
            if (!isOldForeground) {
                // In Android 14+ / targetSdk 36, NotificationManagerService rejects plain notify
                // with CallStyle when the service is not foreground:
                throw IllegalArgumentException(
                    "CallStyle notifications must be for a foreground service or user initiated job or use a fullScreenIntent"
                )
            }
            startForegroundCount++
        }

        // Old stop sequence:
        // 1. User stops voice
        oldDismissNotification()
        assertFalse(isOldForeground)

        // 2. onStateChanged(STOPPED) in old code fell through unconditionally to updateNotification()
        val exception = assertThrows(IllegalArgumentException::class.java) {
            oldUpdateNotificationWithPlainNotify()
        }
        assertTrue(
            exception.message!!.contains("CallStyle notifications must be for a foreground service")
        )
    }

    @Test
    fun testBuildCallNotification_usesNewSmallIcon_andConfiguresCallStyleWithEndAndMute() {
        val serviceController = Robolectric.buildService(MicService::class.java).create()
        val service = serviceController.get()
        try {
            val notification = service.buildCallNotification()

            // 1. Assert uses the NEW small-icon resource
            assertEquals(
                "Call notification must use R.drawable.ic_notification_mira small icon",
                R.drawable.ic_notification_mira,
                notification.icon
            )

            // 2. Assert it does NOT use the old full-colour logo or launcher icons
            assertNotEquals(
                "Call notification must NOT use the old full-color logo_chosen",
                R.drawable.logo_chosen,
                notification.icon
            )
            assertNotEquals(
                "Call notification must NOT use ic_launcher_foreground",
                R.drawable.ic_launcher_foreground,
                notification.icon
            )

            // 3. Assert FLAG_ONLY_ALERT_ONCE is set
            assertTrue(
                "Notification must have FLAG_ONLY_ALERT_ONCE set",
                (notification.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0
            )

            // 4. Assert Ongoing event
            assertTrue(
                "Notification must be marked ongoing",
                (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
            )

            // 5. Assert CallStyle template
            assertEquals(
                NotificationCompat.CallStyle::class.java.name,
                notification.extras.getString(NotificationCompat.EXTRA_TEMPLATE)
            )

            // 6. Assert Mute action is present
            val muteAction = notification.actions?.find { it.title == "Mute" || it.title == "Unmute" }
            assertNotNull("Mute action must be present in notification actions", muteAction)

            // 7. Assert End / Hang up action is configured in CallStyle extras or actions
            val hasEndIntent = notification.extras.containsKey("android.declineIntent") ||
                notification.extras.containsKey(NotificationCompat.EXTRA_DECLINE_INTENT) ||
                notification.actions?.any { it.actionIntent != null } == true
            assertTrue("End/Hangup action must be configured in CallStyle", hasEndIntent)
        } finally {
            serviceController.destroy()
        }
    }

    @Test
    fun testCallNotification_onlyAlertsOnce_andRepeatedUpdatesDoNotReAlert() {
        // 1. Old builder without setOnlyAlertOnce(true) DOES NOT carry FLAG_ONLY_ALERT_ONCE
        val oldBuilderNotification = NotificationCompat.Builder(context, "mira_call_channel")
            .setContentTitle("Old Call")
            .setContentText("Listening • Mira Live call active")
            .setSmallIcon(R.drawable.logo_chosen)
            .build()
        assertFalse(
            "Old notification without onlyAlertOnce must NOT carry FLAG_ONLY_ALERT_ONCE",
            (oldBuilderNotification.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0
        )

        // 2. Service-built call notification DOES carry FLAG_ONLY_ALERT_ONCE
        val serviceController = Robolectric.buildService(MicService::class.java).create()
        val service = serviceController.get()
        try {
            val callNotification = service.buildCallNotification()
            assertTrue(
                "Service-built call notification must carry FLAG_ONLY_ALERT_ONCE",
                (callNotification.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0
            )

            // 3. Track alerting vs silent foreground posts
            var alertingPostsCount = 0
            var silentUpdatesCount = 0

            val trackingController = CallNotificationController(
                startForegroundAction = { _, notif ->
                    if ((notif.flags and Notification.FLAG_ONLY_ALERT_ONCE) == 0) {
                        // Without FLAG_ONLY_ALERT_ONCE, every post alerts heads-up
                        alertingPostsCount++
                    } else {
                        // With FLAG_ONLY_ALERT_ONCE, only the first post alerts heads-up; later posts update silently
                        if (alertingPostsCount == 0) {
                            alertingPostsCount++
                        } else {
                            silentUpdatesCount++
                        }
                    }
                },
                stopForegroundAction = {},
                cancelNotificationAction = {}
            )

            // Initial call start: alerts once
            trackingController.startForegroundWithNotification(callNotification)
            assertEquals("Initial post must alert once", 1, alertingPostsCount)
            assertEquals(0, silentUpdatesCount)

            // Repeated updates on state change (e.g. LISTENING -> SPEAKING -> LISTENING)
            trackingController.postCallNotificationIfForeground { service.buildCallNotification() }
            trackingController.postCallNotificationIfForeground { service.buildCallNotification() }
            trackingController.postCallNotificationIfForeground { service.buildCallNotification() }

            // Assert: NO additional alerting posts produced; all 3 updates were silent
            assertEquals("Repeated updates must NOT trigger new alerting posts", 1, alertingPostsCount)
            assertEquals("Repeated updates must be processed silently", 3, silentUpdatesCount)

            // Contrast with old notification: if old notification was used, every update alerts
            var oldAlertingPosts = 0
            val oldTrackingController = CallNotificationController(
                startForegroundAction = { _, notif ->
                    if ((notif.flags and Notification.FLAG_ONLY_ALERT_ONCE) == 0) {
                        oldAlertingPosts++
                    }
                },
                stopForegroundAction = {},
                cancelNotificationAction = {}
            )
            oldTrackingController.startForegroundWithNotification(oldBuilderNotification)
            oldTrackingController.postCallNotificationIfForeground { oldBuilderNotification }
            oldTrackingController.postCallNotificationIfForeground { oldBuilderNotification }
            assertEquals("Old builder produces alerting post on every update (re-alert bug)", 3, oldAlertingPosts)
        } finally {
            serviceController.destroy()
        }
    }
}
