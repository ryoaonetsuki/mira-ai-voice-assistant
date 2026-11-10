// Ported from Jarvis (github.com/AbuZar-Ansarii/Jarvis), MIT Licence, Copyright (c) 2026 AbuZar Ansarii — Dart → Kotlin port for Mira.
package io.github.ryoaonetsuki.mira.automation.agent

import io.github.ryoaonetsuki.mira.automation.MiraAutomationBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JarvisExtrasTest {

    private class InMemorySkillStore(var data: String? = null) : SkillStore {
        override fun loadRaw(): String? = data
        override fun saveRaw(json: String) {
            data = json
        }
    }

    private class InMemoryTaskHistoryRawStore(var data: String? = null) : TaskHistoryRawStore {
        override fun loadRaw(): String? = data
        override fun saveRaw(json: String) {
            data = json
        }
    }

    private class FakeAutomationPort(
        var serviceEnabled: Boolean = true,
        var currentPkg: String = "com.test.app",
        var nodes: List<ScreenNode> = emptyList(),
        val onActionDispatched: ((String) -> Unit)? = null
    ) : AutomationPort {
        val dispatchedActions = mutableListOf<String>()

        override fun isServiceEnabled(): Boolean = serviceEnabled
        override fun dumpScreen(): List<ScreenNode> = nodes
        override fun formatNodes(nodes: List<ScreenNode>): String = ""
        override fun getCompressedScreenDescription(nodes: List<ScreenNode>, goal: String): String = ""

        override fun clickText(text: String, nodes: List<ScreenNode>): Boolean {
            dispatchedActions.add("clickText:$text")
            onActionDispatched?.invoke("clickText:$text")
            return true
        }

        override fun clickAt(x: Float, y: Float): Boolean {
            dispatchedActions.add("clickAt:$x,$y")
            onActionDispatched?.invoke("clickAt:$x,$y")
            return true
        }

        override fun typeText(text: String): Boolean {
            dispatchedActions.add("typeText:$text")
            onActionDispatched?.invoke("typeText:$text")
            return true
        }

        override fun pressEnter(): Boolean {
            dispatchedActions.add("pressEnter")
            onActionDispatched?.invoke("pressEnter")
            return true
        }

        override fun scroll(direction: String): Boolean {
            dispatchedActions.add("scroll:$direction")
            onActionDispatched?.invoke("scroll:$direction")
            return true
        }

        override fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): Boolean {
            dispatchedActions.add("swipe")
            onActionDispatched?.invoke("swipe")
            return true
        }

        override fun pressBack(): Boolean {
            dispatchedActions.add("pressBack")
            onActionDispatched?.invoke("pressBack")
            return true
        }

        override fun pressHome(): Boolean {
            dispatchedActions.add("pressHome")
            onActionDispatched?.invoke("pressHome")
            return true
        }

        override fun openApp(query: String): String? {
            dispatchedActions.add("openApp:$query")
            onActionDispatched?.invoke("openApp:$query")
            currentPkg = query
            return query
        }

        override fun getCurrentPackage(): String = currentPkg
    }

    // =========================================================================
    // Part 1: skillsEnabled Setting Tests
    // =========================================================================

    @Test
    fun testSkillsEnabled_DisabledGatesMatchingAndSaving() {
        val store = InMemorySkillStore()
        var enabled = false
        val service = SkillMemoryService(store, isSkillsEnabled = { enabled })

        val step = TaskStep(TaskStep.ACTION_OPEN_APP, mapOf("app" to "settings"))
        val savedSkill = service.saveOrUpdate("open settings", listOf(step))

        // When disabled, saveOrUpdate stores nothing to the store
        assertNull(store.data)
        assertTrue(savedSkill.steps.isEmpty())

        // findMatch returns null when disabled
        assertNull(service.findMatch("open settings"))

        // Enable skills and save again
        enabled = true
        val realSaved = service.saveOrUpdate("open settings", listOf(step))
        assertNotNull(store.data)
        assertEquals(1, realSaved.steps.size)
        assertNotNull(service.findMatch("open settings"))

        // Disable again: findMatch returns null even when stored
        enabled = false
        assertNull(service.findMatch("open settings"))

        // saveOrUpdate while disabled returns existing skill without updating
        val returnExisting = service.saveOrUpdate("open settings", listOf(step))
        assertEquals(realSaved.id, returnExisting.id)
    }

    @Test
    fun testSkillSettings_GetSetAndFlow() {
        var flag = true
        val settings = SkillSettings(
            getFlag = { flag },
            setFlag = { flag = it }
        )

        assertTrue(settings.isSkillsEnabled)
        assertEquals(true, settings.get())
        assertEquals(true, settings.skillsEnabledFlow.value)

        settings.set(false)
        assertFalse(settings.isSkillsEnabled)
        assertEquals(false, settings.get())
        assertEquals(false, settings.skillsEnabledFlow.value)
        assertFalse(flag)
    }

    // =========================================================================
    // Part 2: Pause / Resume & Timeout Tests
    // =========================================================================

    @Test
    fun testPauseResume_ExecutionDoesNotAdvanceWhilePaused() = runBlocking {
        var executorRef: TaskExecutor? = null
        var pauseTriggered = false

        val port = FakeAutomationPort(onActionDispatched = { _ ->
            if (!pauseTriggered) {
                pauseTriggered = true
                executorRef?.pause()
            }
        })

        val store = InMemorySkillStore()
        val skills = SkillMemoryService(store)
        val executor = TaskExecutor(
            automation = port,
            skills = skills,
            stepDelayMs = 0L
        )
        executorRef = executor

        val steps = listOf(
            TaskStep(TaskStep.ACTION_OPEN_APP, mapOf("app" to "com.test.app")),
            TaskStep(TaskStep.ACTION_OPEN_APP, mapOf("app" to "com.test.app")),
            TaskStep(TaskStep.ACTION_OPEN_APP, mapOf("app" to "com.test.app"))
        )
        val skill = Skill(goal = "test pause", steps = steps)

        var finalResult: TaskResult? = null
        val job = launch {
            finalResult = executor.replaySkillDirect(skill)
        }

        // Wait until pause is active
        while (!executor.isPaused) {
            delay(10L)
        }
        assertTrue(executor.isPaused)
        assertEquals(ExecutorState.PAUSED, executor.state)

        val pausedStep = executor.currentStep
        // Wait longer than await loop (200ms) to ensure currentStep does NOT advance while paused
        delay(250L)
        assertEquals(pausedStep, executor.currentStep)
        assertTrue(executor.isPaused)

        // Resume and verify completion
        executor.resume()
        assertFalse(executor.isPaused)

        job.join()
        assertNotNull(finalResult)
        assertEquals(TaskStatus.SUCCESS, finalResult?.status)
        assertEquals(3, finalResult?.stepsUsed)
    }

    @Test
    fun testPauseResume_PausedTimeDoesNotCountTowardTimeout() = runBlocking {
        var executorRef: TaskExecutor? = null
        var pauseTriggered = false

        val port = FakeAutomationPort(onActionDispatched = { _ ->
            if (!pauseTriggered) {
                pauseTriggered = true
                executorRef?.pause()
            }
        })

        val store = InMemorySkillStore()
        val skills = SkillMemoryService(store)
        // Set short taskTimeoutMs = 300ms
        val executor = TaskExecutor(
            automation = port,
            skills = skills,
            stepDelayMs = 0L,
            taskTimeoutMs = 300L
        )
        executorRef = executor

        val steps = listOf(
            TaskStep(TaskStep.ACTION_OPEN_APP, mapOf("app" to "com.test.app")),
            TaskStep(TaskStep.ACTION_OPEN_APP, mapOf("app" to "com.test.app"))
        )
        val skill = Skill(goal = "test pause timeout", steps = steps)

        var finalResult: TaskResult? = null
        val job = launch {
            finalResult = executor.replaySkillDirect(skill)
        }

        while (!executor.isPaused) {
            delay(10L)
        }
        assertTrue(executor.isPaused)

        // Pause for 450ms (longer than the 300ms timeout)
        delay(450L)

        // Job must NOT be cancelled because paused time does not count
        assertTrue(job.isActive)
        assertTrue(executor.isPaused)

        executor.resume()
        job.join()

        assertNotNull(finalResult)
        assertEquals(TaskStatus.SUCCESS, finalResult?.status)
    }

    @Test
    fun testAutomationBridge_PauseResumeAndStaleGuard() = runBlocking {
        var execRef: TaskExecutor? = null
        val port = FakeAutomationPort()
        val store = InMemorySkillStore()
        val skills = SkillMemoryService(store)

        var fakeClock = 1000L
        val bridge = MiraAutomationBridge(
            context = null,
            taskTimeoutMs = 5000L,
            clockMs = { fakeClock },
            executorFactory = {
                val exec = TaskExecutor(
                    automation = port,
                    skills = skills,
                    stepDelayMs = 0L,
                    clockMs = { fakeClock }
                )
                execRef = exec
                exec
            }
        )

        val skill = Skill(
            goal = "bridge test",
            steps = listOf(
                TaskStep(TaskStep.ACTION_OPEN_APP, mapOf("app" to "com.test.app")),
                TaskStep(TaskStep.ACTION_OPEN_APP, mapOf("app" to "com.test.app"))
            )
        )

        val job = launch {
            bridge.executeSkill(skill)
        }

        while (execRef == null) {
            delay(10L)
        }

        bridge.pause()
        assertTrue(bridge.isPaused)
        assertTrue(execRef!!.isPaused)

        // Advance wall clock past taskTimeoutMs (5000ms)
        fakeClock += 10000L

        // Stale guard must NEVER clear a paused executor
        assertTrue(bridge.isRunning)
        assertTrue(bridge.isPaused)

        bridge.resume()
        assertFalse(bridge.isPaused)

        job.join()
        assertFalse(bridge.isRunning)
    }

    // =========================================================================
    // Part 3: Task History Tests
    // =========================================================================

    @Test
    fun testTaskHistory_RecordsExecutionCorrectly() = runBlocking {
        val historyRaw = InMemoryTaskHistoryRawStore()
        val historyStore = TaskHistoryStore(historyRaw)
        val port = FakeAutomationPort()
        val store = InMemorySkillStore()
        val skills = SkillMemoryService(store)

        val executor = TaskExecutor(
            automation = port,
            skills = skills,
            stepDelayMs = 0L,
            historyStore = historyStore
        )

        val steps = listOf(
            TaskStep(TaskStep.ACTION_OPEN_APP, mapOf("app" to "com.test.app")),
            TaskStep(TaskStep.ACTION_PRESS_HOME, emptyMap())
        )
        val skill = Skill(goal = "open app and home", steps = steps)

        val result = executor.replaySkillDirect(skill)
        assertEquals(TaskStatus.SUCCESS, result.status)

        val entries = historyStore.all()
        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals("open app and home", entry.goal)
        assertEquals(TaskStatus.SUCCESS, entry.status)
        assertEquals(2, entry.stepsUsed)
        assertEquals(2, entry.stepDescriptions.size)
        assertTrue(entry.startedAtMs > 0L)
        assertTrue(entry.finishedAtMs >= entry.startedAtMs)
        assertEquals(1, historyStore.entriesFlow.value.size)
    }

    @Test
    fun testTaskHistory_ToleratesCorruptJson() {
        val historyRaw = InMemoryTaskHistoryRawStore(data = "{ corrupt json not an array")
        val historyStore = TaskHistoryStore(historyRaw)
        assertTrue(historyStore.all().isEmpty())
        assertEquals(0, historyStore.entriesFlow.value.size)

        // Can still record properly after corrupt recovery
        historyStore.record(
            TaskHistoryEntry(
                goal = "recovered goal",
                status = TaskStatus.SUCCESS,
                stepsUsed = 1,
                startedAtMs = 100L,
                finishedAtMs = 200L
            )
        )
        assertEquals(1, historyStore.all().size)
    }

    @Test
    fun testTaskHistory_MaxEntriesCapAt50() {
        val historyRaw = InMemoryTaskHistoryRawStore()
        val historyStore = TaskHistoryStore(historyRaw)

        for (i in 1..60) {
            historyStore.record(
                TaskHistoryEntry(
                    goal = "goal $i",
                    status = TaskStatus.SUCCESS,
                    stepsUsed = i,
                    startedAtMs = i.toLong(),
                    finishedAtMs = (i + 10).toLong()
                )
            )
        }

        val entries = historyStore.all()
        assertEquals(50, entries.size)
        // Newest first
        assertEquals("goal 60", entries[0].goal)
        assertEquals("goal 11", entries[49].goal)

        // Delete test
        val toDelete = entries[0].id
        val deleted = historyStore.delete(toDelete)
        assertTrue(deleted)
        assertEquals(49, historyStore.all().size)

        // Clear test
        historyStore.clear()
        assertTrue(historyStore.all().isEmpty())
    }
}
