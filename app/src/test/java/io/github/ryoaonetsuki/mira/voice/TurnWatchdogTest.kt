package io.github.ryoaonetsuki.mira.voice

import io.github.ryoaonetsuki.mira.automation.MiraAutomationBridge
import io.github.ryoaonetsuki.mira.automation.agent.TaskStep
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnWatchdogTest {

    @Test
    fun testUserTurnWatchdog_nudgeFiresAfterSilenceOnFollowUpTurn() = runBlocking {
        var firedText: String? = null
        val watchdog = TurnWatchdog(
            scope = this,
            kind = WatchdogKind.USER_TURN
        ) { text ->
            firedText = text
        }

        // Arm on follow-up turn with short deadline
        watchdog.arm("search lofi on youtube", deadlineMs = 40L)
        assertEquals(WatchdogState.ARMED, watchdog.state)
        assertEquals("search lofi on youtube", watchdog.armedText)

        // Wait for watchdog to fire
        delay(80L)

        assertEquals(WatchdogState.FIRED, watchdog.state)
        assertEquals("search lofi on youtube", firedText)
    }

    @Test
    fun testUserTurnWatchdog_noNudgeWhenModelRespondsInTime() = runBlocking {
        var firedCount = 0
        val watchdog = TurnWatchdog(
            scope = this,
            kind = WatchdogKind.USER_TURN
        ) {
            firedCount++
        }

        watchdog.arm("play music", deadlineMs = 60L)
        assertEquals(WatchdogState.ARMED, watchdog.state)

        // Model responds in time (e.g. model transcript or bot audio arrives at 20ms)
        delay(20L)
        watchdog.cancel()
        assertEquals(WatchdogState.CANCELLED, watchdog.state)

        // Wait beyond deadline to ensure no fire
        delay(80L)
        assertEquals(0, firedCount)
        assertEquals(WatchdogState.CANCELLED, watchdog.state)
    }

    @Test
    fun testPostCallWatchdog_nudgeCarriesExecutedOutcome() = runBlocking {
        var firedOutcome: String? = null
        val watchdog = TurnWatchdog(
            scope = this,
            kind = WatchdogKind.POST_FUNCTION_CALL
        ) { outcome ->
            firedOutcome = outcome
        }

        val outcomeText = "Successfully opened com.google.android.youtube"
        watchdog.arm(outcomeText, deadlineMs = 40L)
        assertEquals(WatchdogState.ARMED, watchdog.state)

        delay(80L)
        assertEquals(WatchdogState.FIRED, watchdog.state)
        assertEquals(outcomeText, firedOutcome)

        val formattedNudge = TurnWatchdog.formatPostCallNudge(firedOutcome!!)
        assertTrue("Nudge must carry the executed outcome text", formattedNudge.contains(outcomeText))
        assertTrue("Nudge must ask the model to speak the result to the user", formattedNudge.contains("speak this outcome"))
    }

    @Test
    fun testRoutingDecision_noTtsWhileLiveConnected() {
        // While Live is connected, never route to TTS (always INJECT_LIVE_NUDGE)
        val actionLiveConnectedUser = WatchdogActionResolver.resolve(
            isLiveConnected = true,
            isUserInitiated = true
        )
        assertEquals(WatchdogAction.INJECT_LIVE_NUDGE, actionLiveConnectedUser)

        val actionLiveConnectedAuto = WatchdogActionResolver.resolve(
            isLiveConnected = true,
            isUserInitiated = false
        )
        assertEquals(WatchdogAction.INJECT_LIVE_NUDGE, actionLiveConnectedAuto)

        // While Live is disconnected, fallback TTS only if user initiated
        val actionDisconnectedUser = WatchdogActionResolver.resolve(
            isLiveConnected = false,
            isUserInitiated = true
        )
        assertEquals(WatchdogAction.RUN_DIRECT_PATH_WITH_TTS, actionDisconnectedUser)

        val actionDisconnectedAuto = WatchdogActionResolver.resolve(
            isLiveConnected = false,
            isUserInitiated = false
        )
        assertEquals(WatchdogAction.RUN_DIRECT_PATH_NO_TTS, actionDisconnectedAuto)
    }

    @Test
    fun testDirectActionToSteps_conversions() {
        // OpenApp
        val openSteps = TurnWatchdog.directActionToSteps(
            MiraAutomationBridge.DirectAction.OpenApp("com.google.android.youtube")
        )
        assertEquals(1, openSteps.size)
        assertEquals(TaskStep.ACTION_OPEN_APP, openSteps[0].action)
        assertEquals("com.google.android.youtube", openSteps[0].stringParam("app"))

        // PressHome
        val homeSteps = TurnWatchdog.directActionToSteps(MiraAutomationBridge.DirectAction.PressHome)
        assertEquals(1, homeSteps.size)
        assertEquals(TaskStep.ACTION_PRESS_HOME, homeSteps[0].action)

        // PressBack
        val backSteps = TurnWatchdog.directActionToSteps(MiraAutomationBridge.DirectAction.PressBack)
        assertEquals(1, backSteps.size)
        assertEquals(TaskStep.ACTION_PRESS_BACK, backSteps[0].action)

        // Scroll
        val scrollSteps = TurnWatchdog.directActionToSteps(MiraAutomationBridge.DirectAction.Scroll("down"))
        assertEquals(1, scrollSteps.size)
        assertEquals(TaskStep.ACTION_SCROLL, scrollSteps[0].action)

        // Search with targetApp
        val searchSteps = TurnWatchdog.directActionToSteps(
            MiraAutomationBridge.DirectAction.Search("lofi", "com.google.android.youtube")
        )
        assertEquals(3, searchSteps.size)
        assertEquals(TaskStep.ACTION_OPEN_APP, searchSteps[0].action)
        assertEquals(TaskStep.ACTION_TYPE_TEXT, searchSteps[1].action)
        assertEquals("lofi", searchSteps[1].stringParam("text"))
        assertEquals(TaskStep.ACTION_PRESS_ENTER, searchSteps[2].action)
    }

    @Test
    fun testExtractOutcomeFromMap() {
        assertEquals("Done", TurnWatchdog.extractOutcomeFromMap(mapOf("result" to "Done")))
        assertEquals("Completed task", TurnWatchdog.extractOutcomeFromMap(mapOf("message" to "Completed task")))
        assertEquals("Error: Missing query", TurnWatchdog.extractOutcomeFromMap(mapOf("error" to "Missing query")))
        assertEquals("Successfully opened com.whatsapp", TurnWatchdog.extractOutcomeFromMap(mapOf("success" to true, "package" to "com.whatsapp")))
        assertEquals("Failed to open com.whatsapp", TurnWatchdog.extractOutcomeFromMap(mapOf("success" to false, "package" to "com.whatsapp")))
    }

    @Test
    fun testUserTurnEscalator_firesAfterTimeout() = runBlocking {
        var escalatedReason: String? = null
        val escalator = UserTurnEscalator(
            scope = this,
            timeoutMs = 50L,
            onEscalate = { reason ->
                escalatedReason = reason
            }
        )

        escalator.arm("test-escalation")
        assertTrue("Escalator must be pending after arming", escalator.isPending)

        delay(80L)

        assertFalse("Escalator must no longer be pending after firing", escalator.isPending)
        assertEquals("test-escalation", escalatedReason ?: "")
    }

    @Test
    fun testUserTurnEscalator_cancelledByServerActivity() = runBlocking {
        var escalatedReason: String? = null
        val escalator = UserTurnEscalator(
            scope = this,
            timeoutMs = 80L,
            onEscalate = { reason ->
                escalatedReason = reason
            }
        )

        escalator.arm("test-escalation")
        assertTrue(escalator.isPending)

        // Server activity arrives at 20ms
        delay(20L)
        escalator.cancel()
        assertFalse(escalator.isPending)

        // Wait beyond deadline
        delay(100L)
        assertNull("Escalator must NOT have fired when cancelled by server activity", escalatedReason)
    }

    @Test
    fun testUserTurnEscalator_manualFire() {
        var escalatedReason: String? = null
        val escalator = UserTurnEscalator(
            scope = null,
            timeoutMs = 10_000L,
            onEscalate = { reason ->
                escalatedReason = reason
            }
        )

        escalator.arm("manual-test")
        assertTrue(escalator.isPending)

        escalator.fire("custom-escalate-reason")
        assertFalse(escalator.isPending)
        assertEquals("custom-escalate-reason", escalatedReason ?: "")
    }
}
