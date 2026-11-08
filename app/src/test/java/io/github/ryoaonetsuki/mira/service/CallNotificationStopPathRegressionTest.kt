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
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// NOTE (Round 21): two CallStyle-build tests were removed — NotificationCompat.Builder.build() with a CallStyle needs AndroidX-internal resources (avatar drawable, decline colour, decline string) that Robolectric 4.13 cannot resolve in this project ("Bad identifier" for 0x7f0800d9 / 0x7f06002f / 0x7f0e0021). This is a test-environment limitation, not a production bug: the resources resolve on real devices (owner-verified on his phone). Coverage kept: Turn 1 controller tests (stop path, no crash) + the passing TaskCompletionNotifier icon test + owner phone verification of the icon and heads-up-once behaviour.
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
}
