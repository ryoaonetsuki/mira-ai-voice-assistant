package io.github.ryoaonetsuki.mira.notifications

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import io.github.ryoaonetsuki.mira.MainActivity
import io.github.ryoaonetsuki.mira.R
import io.github.ryoaonetsuki.mira.service.MicService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class NotificationActionsTest {

    @Test
    fun testTaskCompletionNotifier_BuildOpenIntent_Project() {
        val context: Context = RuntimeEnvironment.getApplication()
        val projectName = "portfolio_site"
        val intent = TaskCompletionNotifier.buildOpenIntent(context, projectName, isProject = true)

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("mira://project/$projectName", intent.data.toString())
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun testTaskCompletionNotifier_BuildOpenIntent_File() {
        val context: Context = RuntimeEnvironment.getApplication()
        val fileName = "script.py"
        val intent = TaskCompletionNotifier.buildOpenIntent(context, fileName, isProject = false)

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("mira://file/$fileName", intent.data.toString())
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun testTaskCompletionNotifier_BuildChatOpenIntent() {
        val context: Context = RuntimeEnvironment.getApplication()
        val intent = TaskCompletionNotifier.buildChatOpenIntent(context)

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("mira://chat", intent.data.toString())
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun testMicService_ActionConstants_And_Intents() {
        assertEquals("io.github.ryoaonetsuki.mira.action.START_VOICE", MicService.ACTION_START)
        assertEquals("io.github.ryoaonetsuki.mira.action.STOP_VOICE", MicService.ACTION_STOP)
        assertEquals("io.github.ryoaonetsuki.mira.action.TOGGLE_MUTE", MicService.ACTION_TOGGLE_MUTE)

        val context: Context = RuntimeEnvironment.getApplication()
        val stopIntent = Intent(context, MicService::class.java).apply { action = MicService.ACTION_STOP }
        assertEquals(MicService.ACTION_STOP, stopIntent.action)
        assertEquals(MicService::class.java.name, stopIntent.component?.className)

        val muteIntent = Intent(context, MicService::class.java).apply { action = MicService.ACTION_TOGGLE_MUTE }
        assertEquals(MicService.ACTION_TOGGLE_MUTE, muteIntent.action)
        assertEquals(MicService::class.java.name, muteIntent.component?.className)
    }

    @Test
    fun testTaskCompletionNotifier_UsesNewSmallIcon() {
        val context: Context = RuntimeEnvironment.getApplication()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        TaskCompletionNotifier.notifyChatReply(context, "Hello from Mira")

        val shadowManager = Shadows.shadowOf(notificationManager)
        val notif = shadowManager.allNotifications.lastOrNull()
        assertNotNull("Notification should have been posted", notif)
        assertEquals(
            "Task notification must use R.drawable.ic_notification_mira",
            R.drawable.ic_notification_mira,
            notif?.icon
        )
        assertNotEquals(
            "Task notification must NOT use old logo_chosen",
            R.drawable.logo_chosen,
            notif?.icon
        )
    }
}
