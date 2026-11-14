package io.github.ryoaonetsuki.mira.service

import org.junit.Test
import org.junit.Assert.*

class TaskSupervisorTest {

  @Test
  fun `same open_app intent different wording yields same key and ALREADY_RUNNING`() {
    val sup = TaskSupervisor()
    val key1 = sup.canonicalKey("open_app", mapOf("app" to "YouTube"), null, "com.google.android.youtube")
    val key2 = sup.canonicalKey("open_app", mapOf("app" to "youtube"), null, "com.google.android.youtube")
    assertNotNull(key1)
    assertEquals(key1, key2)

    assertEquals(DedupDecision.EXECUTE, sup.checkAndReserve(key1!!, 1000))
    assertEquals(DedupDecision.ALREADY_RUNNING, sup.checkAndReserve(key1!!, 1001))
  }

  @Test
  fun `different app yields different key and EXECUTE`() {
    val sup = TaskSupervisor()
    val key1 = sup.canonicalKey("open_app", mapOf("app" to "YouTube"), null, "com.google.android.youtube")
    val key2 = sup.canonicalKey("open_app", mapOf("app" to "Netflix"), null, "com.netflix.ninja")
    assertNotEquals(key1, key2)

    assertEquals(DedupDecision.EXECUTE, sup.checkAndReserve(key1!!, 1000))
    assertEquals(DedupDecision.EXECUTE, sup.checkAndReserve(key2!!, 1000))
  }

  @Test
  fun `done success within 45s is ALREADY_DONE after 45s is EXECUTE`() {
    val sup = TaskSupervisor()
    val key = sup.canonicalKey("web_search", mapOf("query" to "pizza"), null, null)!!
    assertEquals(DedupDecision.EXECUTE, sup.checkAndReserve(key, 1000))
    sup.markFinished(key, true, 2000)

    assertEquals(DedupDecision.ALREADY_DONE, sup.checkAndReserve(key, 2000 + 1000)) // within 45s
    assertEquals(DedupDecision.EXECUTE, sup.checkAndReserve(key, 2000 + 45_001))   // after 45s
  }

  @Test
  fun `done failed allows re-execute`() {
    val sup = TaskSupervisor()
    val key = sup.canonicalKey("web_search", mapOf("query" to "pizza"), null, null)!!
    assertEquals(DedupDecision.EXECUTE, sup.checkAndReserve(key, 1000))
    sup.markFinished(key, false, 2000)
    assertEquals(DedupDecision.EXECUTE, sup.checkAndReserve(key, 3000))
  }

  @Test
  fun `scroll in-flight only no post-success dedup`() {
    val sup = TaskSupervisor()
    val key = sup.canonicalKey("scroll", mapOf("direction" to "down"), "com.app", null)!!
    assertEquals(DedupDecision.EXECUTE, sup.checkAndReserve(key, 1000))
    assertEquals(DedupDecision.ALREADY_RUNNING, sup.checkAndReserve(key, 1001))
    sup.markFinished(key, true, 2000)
    assertEquals(DedupDecision.EXECUTE, sup.checkAndReserve(key, 3000)) // immediately after done
  }

  @Test
  fun `click_text same label different case spacing same key different label different key`() {
    val sup = TaskSupervisor()
    val key1 = sup.canonicalKey("click_text", mapOf("label" to "  Hello World  "), "com.app", null)
    val key2 = sup.canonicalKey("click_text", mapOf("label" to "hello world"), "com.app", null)
    assertNotNull(key1)
    assertEquals(key1, key2)

    val key3 = sup.canonicalKey("click_text", mapOf("label" to "Goodbye"), "com.app", null)
    assertNotEquals(key1, key3)
  }

  @Test
  fun `type_text different text different key same text while running ALREADY_RUNNING`() {
    val sup = TaskSupervisor()
    val key1 = sup.canonicalKey("type_text", mapOf("text" to "foo"), "com.app", null)
    val key2 = sup.canonicalKey("type_text", mapOf("text" to "bar"), "com.app", null)
    assertNotEquals(key1, key2)

    assertEquals(DedupDecision.EXECUTE, sup.checkAndReserve(key1!!, 1000))
    assertEquals(DedupDecision.ALREADY_RUNNING, sup.checkAndReserve(key1!!, 1001))
  }

  @Test
  fun `unknown tool canonicalKey returns null`() {
    val sup = TaskSupervisor()
    assertNull(sup.canonicalKey("unknown_tool", mapOf(), null, null))
  }

  @Test
  fun `markCancelled removes running record next checkAndReserve EXECUTE`() {
    val sup = TaskSupervisor()
    val key = sup.canonicalKey("web_search", mapOf("query" to "pizza"), null, null)!!
    assertEquals(DedupDecision.EXECUTE, sup.checkAndReserve(key, 1000))
    sup.markCancelled(key, 1500)
    assertEquals(DedupDecision.EXECUTE, sup.checkAndReserve(key, 2000))
  }

  @Test
  fun `isContinuationTool`() {
    val sup = TaskSupervisor()
    assertTrue(sup.isContinuationTool("scroll"))
    assertTrue(sup.isContinuationTool("click_text"))
    assertTrue(sup.isContinuationTool("type_text"))
    assertTrue(sup.isContinuationTool("press_back"))
    assertTrue(sup.isContinuationTool("get_screen"))
    assertTrue(sup.isContinuationTool("wait_for_text"))
    assertTrue(sup.isContinuationTool("tap_number"))

    assertFalse(sup.isContinuationTool("open_app"))
    assertFalse(sup.isContinuationTool("web_search"))
    assertFalse(sup.isContinuationTool("run_skill"))
  }

  @Test
  fun `classifyTurn no running goal to NEW_TASK`() {
    val sup = TaskSupervisor()
    assertEquals(TaskSupervisor.TurnClass.NEW_TASK, sup.classifyTurn("open youtube", null, null, null, null))
  }

  @Test
  fun `classifyTurn same intent normalized equal to SAME_INTENT`() {
    val sup = TaskSupervisor()
    assertEquals(TaskSupervisor.TurnClass.SAME_INTENT, sup.classifyTurn("open youtube", "open youtube", null, null, null))
  }

  @Test
  fun `classifyTurn same intent with particle to SAME_INTENT`() {
    val sup = TaskSupervisor()
    assertEquals(TaskSupervisor.TurnClass.SAME_INTENT, sup.classifyTurn("scroll down please", "scroll down", null, null, null))
  }

  @Test
  fun `classifyTurn Scroll to FOLLOW_UP`() {
    val sup = TaskSupervisor()
    assertEquals(TaskSupervisor.TurnClass.FOLLOW_UP, sup.classifyTurn("scroll down", "open youtube", null, "Scroll", null))
  }

  @Test
  fun `classifyTurn TapLabel to FOLLOW_UP`() {
    val sup = TaskSupervisor()
    assertEquals(TaskSupervisor.TurnClass.FOLLOW_UP, sup.classifyTurn("click submit", "open youtube", null, "TapLabel", null))
  }

  @Test
  fun `classifyTurn TapNumber to FOLLOW_UP`() {
    val sup = TaskSupervisor()
    assertEquals(TaskSupervisor.TurnClass.FOLLOW_UP, sup.classifyTurn("tap 3", "open youtube", null, "TapNumber", null))
  }

  @Test
  fun `classifyTurn PressBack to FOLLOW_UP`() {
    val sup = TaskSupervisor()
    assertEquals(TaskSupervisor.TurnClass.FOLLOW_UP, sup.classifyTurn("go back", "open youtube", null, "PressBack", null))
  }

  @Test
  fun `classifyTurn OpenApp different target to NEW_TASK`() {
    val sup = TaskSupervisor()
    assertEquals(TaskSupervisor.TurnClass.NEW_TASK, sup.classifyTurn("open netflix", "open youtube", "com.youtube", "OpenApp", "com.netflix"))
  }

  @Test
  fun `classifyTurn OpenApp same target to FOLLOW_UP`() {
    val sup = TaskSupervisor()
    // Goal differs from the new text ("watch netflix" vs "open netflix") so the
    // same-intent check does not fire first; the OpenApp same-target branch
    // is what must return FOLLOW_UP here.
    assertEquals(TaskSupervisor.TurnClass.FOLLOW_UP, sup.classifyTurn("open netflix", "watch netflix", "com.netflix", "OpenApp", "com.netflix"))
  }

  @Test
  fun `classifyTurn Search to NEW_TASK`() {
    val sup = TaskSupervisor()
    assertEquals(TaskSupervisor.TurnClass.NEW_TASK, sup.classifyTurn("search pizza", "open youtube", null, "Search", null))
  }

  @Test
  fun `classifyTurn Dial to NEW_TASK`() {
    val sup = TaskSupervisor()
    assertEquals(TaskSupervisor.TurnClass.NEW_TASK, sup.classifyTurn("call 555", "open youtube", null, "Dial", null))
  }

  @Test
  fun `classifyTurn null kind to FOLLOW_UP`() {
    val sup = TaskSupervisor()
    assertEquals(TaskSupervisor.TurnClass.FOLLOW_UP, sup.classifyTurn("do something", "open youtube", null, null, null))
  }

  @Test
  fun `cancelAll removes all RUNNING and returns keys`() {
    val sup = TaskSupervisor()
    val k1 = sup.canonicalKey("web_search", mapOf("query" to "pizza"), null, null)!!
    val k2 = sup.canonicalKey("web_search", mapOf("query" to "burger"), null, null)!!
    sup.checkAndReserve(k1, 1000)
    sup.checkAndReserve(k2, 1000)

    val cancelled = sup.cancelAll(2000)
    assertEquals(2, cancelled.size)
    assertTrue(cancelled.contains(k1))
    assertTrue(cancelled.contains(k2))

    assertEquals(DedupDecision.EXECUTE, sup.checkAndReserve(k1, 3000))
    assertEquals(DedupDecision.EXECUTE, sup.checkAndReserve(k2, 3000))
  }

  @Test
  fun `cachedResult stores and returns outcome`() {
    val sup = TaskSupervisor()
    val key = sup.canonicalKey("open_app", mapOf("app" to "YouTube"), null, "com.youtube")!!
    sup.checkAndReserve(key, 1000)
    sup.markFinished(key, true, 2000, "Opened YouTube")
    assertEquals("Opened YouTube", sup.cachedResult(key))
  }

  @Test
  fun `cachedResult returns null after failed finish`() {
    val sup = TaskSupervisor()
    val key = sup.canonicalKey("web_search", mapOf("query" to "pizza"), null, null)!!
    sup.checkAndReserve(key, 1000)
    sup.markFinished(key, false, 2000)
    assertNull(sup.cachedResult(key))
  }
}
