// Ported from Jarvis (github.com/AbuZar-Ansarii/Jarvis), MIT Licence, Copyright (c) 2026 AbuZar Ansarii — Dart → Kotlin port for Mira.
package io.github.ryoaonetsuki.mira.automation.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDataLayerTest {

  // --- In-Memory Fake SkillStore for testing ---
  private class InMemorySkillStore(var data: String? = null) : SkillStore {
    override fun loadRaw(): String? = data
    override fun saveRaw(json: String) {
      data = json
    }
  }

  // =========================================================================
  // 1. TaskStep Tests
  // =========================================================================

  @Test
  fun testTaskStep_TryParse_DirectJson() {
    val json = """{"action": "click_text", "params": {"text": "Submit"}, "reasoning": "Click submit button"}"""
    val step = TaskStep.tryParse(json)
    assertNotNull(step)
    assertEquals(TaskStep.ACTION_CLICK_TEXT, step?.action)
    assertEquals("Submit", step?.stringParam("text"))
    assertEquals("Click submit button", step?.reasoning)
    assertFalse(step?.isComplete ?: true)
  }

  @Test
  fun testTaskStep_TryParse_FencedJson() {
    val fenced = """
      ```json
      {
        "action": "click_text",
        "params": {"text": "OK"}
      }
      ```
    """.trimIndent()
    val step = TaskStep.tryParse(fenced)
    assertNotNull(step)
    assertEquals(TaskStep.ACTION_CLICK_TEXT, step?.action)
    assertEquals("OK", step?.stringParam("text"))
  }

  @Test
  fun testTaskStep_TryParse_ProseAroundJson() {
    val prose = """
      Here is the action you requested:
      {"action": "press_enter", "params": {}}
      Hope this helps resolve the task!
    """.trimIndent()
    val step = TaskStep.tryParse(prose)
    assertNotNull(step)
    assertEquals(TaskStep.ACTION_PRESS_ENTER, step?.action)
  }

  @Test
  fun testTaskStep_TryParse_AliasNormalization() {
    // tap -> click_text
    val tapStep = TaskStep.tryParse("""{"action": "tap", "params": {"label": "Next"}}""")
    assertNotNull(tapStep)
    assertEquals(TaskStep.ACTION_CLICK_TEXT, tapStep?.action)
    assertEquals("Next", tapStep?.stringParam("text"))

    // launch X -> open_app
    val launchStep = TaskStep.tryParse("""{"action": "launch", "params": {"name": "YouTube"}}""")
    assertNotNull(launchStep)
    assertEquals(TaskStep.ACTION_OPEN_APP, launchStep?.action)
    assertEquals("YouTube", launchStep?.stringParam("app"))

    // click with x/y -> click_at
    val clickAtStep = TaskStep.tryParse("""{"action": "click", "params": {"x": 100, "y": 200}}""")
    assertNotNull(clickAtStep)
    assertEquals(TaskStep.ACTION_CLICK_AT, clickAtStep?.action)
    assertEquals(100, clickAtStep?.intParam("x"))
    assertEquals(200, clickAtStep?.intParam("y"))

    // click with app -> open_app
    val clickAppStep = TaskStep.tryParse("""{"action": "click", "params": {"app": "Spotify"}}""")
    assertNotNull(clickAppStep)
    assertEquals(TaskStep.ACTION_OPEN_APP, clickAppStep?.action)
    assertEquals("Spotify", clickAppStep?.stringParam("app"))

    // type -> type_text
    val typeStep = TaskStep.tryParse("""{"action": "type", "params": {"input": "search query"}}""")
    assertNotNull(typeStep)
    assertEquals(TaskStep.ACTION_TYPE_TEXT, typeStep?.action)
    assertEquals("search query", typeStep?.stringParam("text"))

    // enter -> press_enter
    val enterStep = TaskStep.tryParse("""{"action": "enter"}""")
    assertNotNull(enterStep)
    assertEquals(TaskStep.ACTION_PRESS_ENTER, enterStep?.action)

    // back -> press_back
    val backStep = TaskStep.tryParse("""{"action": "back"}""")
    assertNotNull(backStep)
    assertEquals(TaskStep.ACTION_PRESS_BACK, backStep?.action)

    // home -> press_home
    val homeStep = TaskStep.tryParse("""{"action": "home"}""")
    assertNotNull(homeStep)
    assertEquals(TaskStep.ACTION_PRESS_HOME, homeStep?.action)

    // finish / complete -> done
    val finishStep = TaskStep.tryParse("""{"action": "finish"}""")
    assertNotNull(finishStep)
    assertEquals(TaskStep.ACTION_DONE, finishStep?.action)
    assertTrue(finishStep?.isComplete ?: false)

    val completeStep = TaskStep.tryParse("""{"action": "complete"}""")
    assertNotNull(completeStep)
    assertEquals(TaskStep.ACTION_DONE, completeStep?.action)
    assertTrue(completeStep?.isComplete ?: false)
  }

  @Test
  fun testTaskStep_TryParse_NaturalLanguageHeuristics() {
    // open YouTube -> open_app
    val openStep = TaskStep.tryParse("Please open YouTube to watch videos.")
    assertNotNull(openStep)
    assertEquals(TaskStep.ACTION_OPEN_APP, openStep?.action)
    assertEquals("YouTube", openStep?.stringParam("app"))

    // click Search -> click_text
    val clickStep = TaskStep.tryParse("click Search")
    assertNotNull(clickStep)
    assertEquals(TaskStep.ACTION_CLICK_TEXT, clickStep?.action)
    assertEquals("Search", clickStep?.stringParam("text"))

    // type cat videos -> type_text
    val typeStep = TaskStep.tryParse("type \"cat videos\"")
    assertNotNull(typeStep)
    assertEquals(TaskStep.ACTION_TYPE_TEXT, typeStep?.action)
    assertEquals("cat videos", typeStep?.stringParam("text"))

    // task complete -> done
    val doneStep = TaskStep.tryParse("The task completed successfully.")
    assertNotNull(doneStep)
    assertEquals(TaskStep.ACTION_DONE, doneStep?.action)
    assertTrue(doneStep?.isComplete ?: false)
  }

  @Test
  fun testTaskStep_TryParse_GarbageText_ReturnsNull() {
    val garbage = "I am pondering what action we might consider next, but not taking any action yet."
    assertNull(TaskStep.tryParse(garbage))
    assertNull(TaskStep.tryParse(""))
    assertNull(TaskStep.tryParse("   "))
  }

  @Test
  fun testTaskStep_TryParse_UnsupportedAction_ReturnsNull() {
    val unsupported = """{"action": "fly_to_moon", "params": {"speed": 100}}"""
    assertNull(TaskStep.tryParse(unsupported))
  }

  @Test
  fun testTaskStep_IntParamAndStringParamForgivingParsing() {
    val step = TaskStep(
      action = TaskStep.ACTION_WAIT,
      params = mapOf(
        "msInt" to 2000,
        "msLong" to 2000L,
        "msDouble" to 1999.8,
        "msStringInt" to "2000",
        "msStringDouble" to "1999.6",
        "emptyString" to "",
        "text" to "Sample"
      )
    )

    assertEquals(2000, step.intParam("msInt"))
    assertEquals(2000, step.intParam("msLong"))
    assertEquals(2000, step.intParam("msDouble"))
    assertEquals(2000, step.intParam("msStringInt"))
    assertEquals(2000, step.intParam("msStringDouble"))
    assertNull(step.intParam("nonExistent"))
    assertNull(step.intParam("text"))

    assertEquals("Sample", step.stringParam("text"))
    assertEquals("2000", step.stringParam("msInt"))
    assertNull(step.stringParam("emptyString"))
    assertNull(step.stringParam("nonExistent"))
  }

  @Test
  fun testTaskStep_Signature_Stable() {
    val step1 = TaskStep(
      action = TaskStep.ACTION_CLICK_AT,
      params = mapOf("x" to 100, "y" to 200)
    )
    val step2 = TaskStep(
      action = TaskStep.ACTION_CLICK_AT,
      params = mapOf("y" to 200, "x" to 100)
    )
    assertEquals(step1.signature, step2.signature)
    assertEquals("click_at:{\"x\":100,\"y\":200}", step1.signature)
  }

  @Test
  fun testTaskStep_ToJsonAndFromJson_RoundTrip() {
    val original = TaskStep(
      action = TaskStep.ACTION_CLICK_TEXT,
      params = mapOf("text" to "Login"),
      reasoning = "Tap login button",
      isComplete = false
    )
    val json = original.toJson()
    val restored = TaskStep.fromJson(json)
    assertEquals(original.action, restored.action)
    assertEquals(original.params["text"], restored.params["text"])
    assertEquals(original.reasoning, restored.reasoning)
    assertEquals(original.isComplete, restored.isComplete)
  }

  // =========================================================================
  // 2. Skill Tests
  // =========================================================================

  @Test
  fun testSkill_Similarity_IdenticalGoals() {
    val sim = Skill.similarity("open youtube", "open youtube")
    assertEquals(1.0, sim, 0.0001)
  }

  @Test
  fun testSkill_Similarity_UnrelatedGoalsLow() {
    val sim = Skill.similarity("open youtube", "check weather forecast in tokyo")
    assertTrue("Expected similarity < 0.3 for unrelated goals, was $sim", sim < 0.3)
  }

  @Test
  fun testSkill_Similarity_NormalizationCaseAndWhitespace() {
    val sim = Skill.similarity("  Open   YouTube  ", "open youtube")
    assertEquals(1.0, sim, 0.0001)
  }

  @Test
  fun testSkill_ToJsonAndFromJson_RoundTrip() {
    val original = Skill(
      goal = "play music on Spotify",
      steps = listOf(
        TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("app" to "Spotify")),
        TaskStep(action = TaskStep.ACTION_CLICK_TEXT, params = mapOf("text" to "Play"))
      ),
      successCount = 3
    )
    val json = original.toJson()
    val restored = Skill.fromJson(json)

    assertEquals(original.id, restored.id)
    assertEquals(original.goal, restored.goal)
    assertEquals(original.normalizedGoal, restored.normalizedGoal)
    assertEquals(original.successCount, restored.successCount)
    assertEquals(2, restored.steps.size)
    assertEquals(TaskStep.ACTION_OPEN_APP, restored.steps[0].action)
    assertEquals(TaskStep.ACTION_CLICK_TEXT, restored.steps[1].action)
  }

  @Test
  fun testSkill_FromJson_TolerantOfMissingFields() {
    val emptyJson = JSONObject()
    val skill = Skill.fromJson(emptyJson)
    assertNotNull(skill.id)
    assertEquals("", skill.goal)
    assertTrue(skill.steps.isEmpty())
    assertEquals(0, skill.successCount)
  }

  // =========================================================================
  // 3. SkillMemoryService Tests
  // =========================================================================

  @Test
  fun testSkillMemoryService_SaveThenFindMatchExact() {
    val store = InMemorySkillStore()
    val service = SkillMemoryService(store)
    val steps = listOf(
      TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("app" to "YouTube")),
      TaskStep(action = TaskStep.ACTION_DONE)
    )

    val saved = service.saveOrUpdate("Open YouTube", steps)
    // Terminal done step must be stripped
    assertEquals(1, saved.steps.size)
    assertEquals(TaskStep.ACTION_OPEN_APP, saved.steps[0].action)

    val match = service.findMatch("open youtube")
    assertNotNull(match)
    assertEquals(saved.id, match?.id)
    assertEquals("Open YouTube", match?.goal)
  }

  @Test
  fun testSkillMemoryService_FindMatchBySimilarity() {
    val store = InMemorySkillStore()
    val service = SkillMemoryService(store)
    val steps = listOf(
      TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("app" to "YouTube"))
    )

    service.saveOrUpdate("open youtube and watch videos", steps)

    // Token sets:
    // A: {"open", "youtube", "and", "watch", "videos"} (size 5)
    // B: {"open", "youtube", "and", "watch"} (size 4)
    // Intersection: 4, Union: 5 -> Jaccard = 0.80 >= 0.75
    val match = service.findMatch("open youtube and watch")
    assertNotNull(match)
    assertEquals("open youtube and watch videos", match?.goal)
  }

  @Test
  fun testSkillMemoryService_NoMatchBelowThreshold() {
    val store = InMemorySkillStore()
    val service = SkillMemoryService(store)
    service.saveOrUpdate(
      "open youtube",
      listOf(TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("app" to "YouTube")))
    )

    val match = service.findMatch("book hotel room in paris")
    assertNull(match)
  }

  @Test
  fun testSkillMemoryService_SaveOrUpdateRefreshesExisting() {
    val store = InMemorySkillStore()
    val service = SkillMemoryService(store)

    val step1 = TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("app" to "News"))
    val first = service.saveOrUpdate("read daily news", listOf(step1))
    service.markReplayOutcome(first.id, true)

    val matchedAfterFirst = service.findMatch("read daily news")
    assertEquals(1, matchedAfterFirst?.successCount)

    val step2 = TaskStep(action = TaskStep.ACTION_SCROLL, params = mapOf("direction" to "down"))
    val updated = service.saveOrUpdate("Read Daily News", listOf(step1, step2))

    assertEquals(first.id, updated.id)
    assertEquals(1, updated.successCount)
    assertEquals(first.createdAt, updated.createdAt)
    assertEquals(2, updated.steps.size)
    assertEquals(1, service.all().size)
  }

  @Test
  fun testSkillMemoryService_MarkReplayOutcomeSuccessIncrements() {
    val store = InMemorySkillStore()
    val service = SkillMemoryService(store)
    val skill = service.saveOrUpdate(
      "play music",
      listOf(TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("app" to "Spotify")))
    )
    assertEquals(0, skill.successCount)

    service.markReplayOutcome(skill.id, true)
    assertEquals(1, service.findMatch("play music")?.successCount)

    service.markReplayOutcome(skill.id, true)
    assertEquals(2, service.findMatch("play music")?.successCount)
  }

  @Test
  fun testSkillMemoryService_MarkReplayOutcomeFailureDeletes() {
    val store = InMemorySkillStore()
    val service = SkillMemoryService(store)
    val skill = service.saveOrUpdate(
      "play podcast",
      listOf(TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("app" to "Podcasts")))
    )
    assertNotNull(service.findMatch("play podcast"))

    service.markReplayOutcome(skill.id, false)
    assertNull(service.findMatch("play podcast"))
    assertTrue(service.all().isEmpty())
  }

  @Test
  fun testSkillMemoryService_CorruptStoredJson_ReturnsEmptyListWithoutThrowing() {
    val store = InMemorySkillStore("not valid json [ { }")
    val service = SkillMemoryService(store)
    val all = service.all()
    assertNotNull(all)
    assertTrue(all.isEmpty())
  }

  @Test
  fun testSkillMemoryService_Max100Cap() {
    val store = InMemorySkillStore()
    val service = SkillMemoryService(store)
    val dummyStep = listOf(TaskStep(action = TaskStep.ACTION_PRESS_HOME))

    for (i in 1..105) {
      service.saveOrUpdate("uniqueword$i", dummyStep)
    }

    val all = service.all()
    assertEquals(100, all.size)
    // Latest inserted skill must be at index 0
    assertEquals("uniqueword105", all.first().goal)
    // Oldest skills beyond 100 must have been dropped
    assertNull(service.findMatch("uniqueword1"))
    assertNull(service.findMatch("uniqueword5"))
    assertNotNull(service.findMatch("uniqueword6"))
  }

  // =========================================================================
  // 4. RecoveryEngine Tests
  // =========================================================================

  @Test
  fun testRecoveryEngine_ThreeIdenticalActions_TriggersRecovery() {
    val engine = RecoveryEngine()
    val step = TaskStep(
      action = TaskStep.ACTION_CLICK_TEXT,
      params = mapOf("text" to "Retry")
    )

    assertFalse(engine.shouldRecover)
    engine.recordAction(step)
    assertFalse(engine.isRepeatingActions)
    assertFalse(engine.shouldRecover)

    engine.recordAction(step)
    assertFalse(engine.isRepeatingActions)
    assertFalse(engine.shouldRecover)

    engine.recordAction(step)
    assertTrue(engine.isRepeatingActions)
    assertTrue(engine.shouldRecover)
  }

  @Test
  fun testRecoveryEngine_ThreeUnchangedScreens_TriggersRecovery() {
    val engine = RecoveryEngine()
    val screenDump = "Window: Home [Button: Search, Button: Profile]"

    engine.recordScreen(screenDump) // Initial screen recorded
    assertFalse(engine.isScreenStuck)
    assertFalse(engine.shouldRecover)

    engine.recordScreen(screenDump) // Unchanged screen 1
    assertFalse(engine.isScreenStuck)
    assertFalse(engine.shouldRecover)

    engine.recordScreen(screenDump) // Unchanged screen 2
    assertFalse(engine.isScreenStuck)
    assertFalse(engine.shouldRecover)

    engine.recordScreen(screenDump) // Unchanged screen 3 (threshold reached)
    assertTrue(engine.isScreenStuck)
    assertTrue(engine.shouldRecover)
  }

  @Test
  fun testRecoveryEngine_SuggestRecoveryRotationOrder() {
    val engine = RecoveryEngine()

    // 1st recovery suggestion: scroll down
    val s1 = engine.suggestRecovery()
    assertEquals(TaskStep.ACTION_SCROLL, s1.action)
    assertEquals("down", s1.stringParam("direction"))

    // 2nd recovery suggestion: wait 2000ms
    val s2 = engine.suggestRecovery()
    assertEquals(TaskStep.ACTION_WAIT, s2.action)
    assertEquals(2000, s2.intParam("ms"))

    // 3rd recovery suggestion: press back
    val s3 = engine.suggestRecovery()
    assertEquals(TaskStep.ACTION_PRESS_BACK, s3.action)

    // 4th recovery suggestion: rotates back to scroll down
    val s4 = engine.suggestRecovery()
    assertEquals(TaskStep.ACTION_SCROLL, s4.action)
    assertEquals("down", s4.stringParam("direction"))
  }

  @Test
  fun testRecoveryEngine_ResetClearsState() {
    val engine = RecoveryEngine()
    val step = TaskStep(action = TaskStep.ACTION_PRESS_BACK)
    repeat(3) { engine.recordAction(step) }
    assertTrue(engine.shouldRecover)

    engine.reset()
    assertFalse(engine.isRepeatingActions)
    assertFalse(engine.isScreenStuck)
    assertFalse(engine.shouldRecover)

    // Verify recovery rotation is also reset
    val s1 = engine.suggestRecovery()
    assertEquals(TaskStep.ACTION_SCROLL, s1.action)
  }
}
