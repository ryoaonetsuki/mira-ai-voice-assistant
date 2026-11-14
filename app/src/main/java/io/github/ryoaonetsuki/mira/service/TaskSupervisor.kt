package io.github.ryoaonetsuki.mira.service

import io.github.ryoaonetsuki.mira.automation.agent.Skill

enum class DedupDecision { EXECUTE, ALREADY_RUNNING, ALREADY_DONE }

/**
 * Pure-Kotlin supervisor for semantic tool-call dedup.
 *
 * Tracks in-flight (RUNNING) and recently completed (DONE) tool calls so
 * repeated or semantically identical invocations are not executed twice.
 * No Android imports — safe to unit test on plain JVM.
 */
class TaskSupervisor {
  private val running = mutableMapOf<String, Long>()   // key -> startMs
  private val done = mutableMapOf<String, Long>()      // key -> finishMs (success only)
  private val cachedResults = mutableMapOf<String, String>()   // key -> outcome string (success only)

  /**
   * Builds a canonical dedup key for [toolName] with [args].
   * Returns null for tools that should always execute (unknown tools).
   * Key format: `toolName|canonicalTarget|payload`
   */
  fun canonicalKey(
    toolName: String,
    args: Map<String, Any?>,
    currentPackage: String?,
    canonicalTarget: String?
  ): String? {
    val target: String
    val payload: String?

    when (toolName) {
      "open_app" -> {
        target = canonicalTarget ?: Skill.normalize(args["app"] as? String ?: "")
        payload = null
      }
      "click_text" -> {
        target = currentPackage ?: ""
        val label = args["label"] as? String ?: args["text"] as? String ?: ""
        payload = Skill.normalize(label)
      }
      "type_text" -> {
        target = currentPackage ?: ""
        payload = args["text"] as? String ?: ""
      }
      "web_search" -> {
        target = ""
        payload = Skill.normalize(args["query"] as? String ?: "")
      }
      "scroll" -> {
        target = currentPackage ?: ""
        payload = args["direction"] as? String ?: ""
      }
      "tap_number" -> {
        target = currentPackage ?: ""
        payload = (args["number"] as? Number)?.toString() ?: args["number"] as? String ?: ""
      }
      "run_skill" -> {
        target = ""
        payload = Skill.normalize(args["goal"] as? String ?: "")
      }
      "click_at" -> {
        target = currentPackage ?: ""
        val x = (args["x"] as? Number)?.toInt() ?: 0
        val y = (args["y"] as? Number)?.toInt() ?: 0
        val qx = (x / 40) * 40
        val qy = (y / 40) * 40
        payload = "$qx,$qy"
      }
      else -> return null
    }

    return "$toolName|$target|$payload"
  }

  /**
   * Atomically checks [key] and reserves it if free.
   * - RUNNING -> ALREADY_RUNNING
   * - DONE-success within DONE_WINDOW_MS (except scroll/click_text) -> ALREADY_DONE
   * - otherwise -> record RUNNING at [nowMs] and return EXECUTE
   */
  fun checkAndReserve(key: String, nowMs: Long): DedupDecision = synchronized(this) {
    pruneDone(nowMs)

    val toolName = key.substringBefore('|')

    if (key in running) {
      DedupDecision.ALREADY_RUNNING
    } else if (toolName !in listOf("scroll", "click_text") && key in done &&
               nowMs - done[key]!! <= DONE_WINDOW_MS) {
      DedupDecision.ALREADY_DONE
    } else {
      running[key] = nowMs
      DedupDecision.EXECUTE
    }
  }

  /**
   * Marks [key] as finished. Success -> recorded as DONE at [nowMs].
   * Failure -> record removed so a retry can re-execute.
   */
  fun markFinished(key: String, success: Boolean, nowMs: Long, result: String? = null) = synchronized(this) {
    running.remove(key)
    if (success) {
      done[key] = nowMs
      result?.let { cachedResults[key] = it }
    } else {
      cachedResults.remove(key)
    }
  }

  fun cachedResult(key: String): String? = synchronized(this) {
    cachedResults[key]
  }

  /**
   * Removes the RUNNING record for [key] so a later retry is not blocked.
   */
  fun markCancelled(key: String, nowMs: Long) = synchronized(this) {
    running.remove(key)
  }

  /**
   * Removes ALL RUNNING records and returns their keys.
   */
  fun cancelAll(nowMs: Long): List<String> = synchronized(this) {
    val keys = running.keys.toList()
    running.clear()
    keys
  }

  /**
   * True for tools that represent ongoing UI interaction and should never
   * be post-success deduped.
   */
  fun isContinuationTool(toolName: String): Boolean {
    return toolName in listOf(
      "scroll", "click_text", "type_text", "press_back", "get_screen",
      "wait_for_text", "tap_number"
    )
  }

  enum class TurnClass { SAME_INTENT, FOLLOW_UP, NEW_TASK }

  /**
   * Pure classifier for whether a new user turn should cancel an in-flight task.
   */
  fun classifyTurn(
    normalizedNewText: String,
    runningGoalNormalized: String?,
    runningTarget: String?,
    parsedKind: String?,
    parsedTarget: String?
  ): TurnClass {
    if (runningGoalNormalized.isNullOrBlank()) return TurnClass.NEW_TASK

    val normNew = Skill.normalize(normalizedNewText)
    if (normNew == runningGoalNormalized || Skill.similarity(normNew, runningGoalNormalized) >= 0.85) {
      return TurnClass.SAME_INTENT
    }

    when (parsedKind) {
      "Scroll", "TapLabel", "TapNumber", "PressBack" -> return TurnClass.FOLLOW_UP
      "OpenApp" -> {
        if (parsedTarget == runningTarget) return TurnClass.FOLLOW_UP
        return TurnClass.NEW_TASK
      }
      "Search", "Dial" -> return TurnClass.NEW_TASK
      null -> return TurnClass.FOLLOW_UP
    }

    return TurnClass.FOLLOW_UP
  }

  private fun pruneDone(nowMs: Long) {
    val cutoff = nowMs - DONE_WINDOW_MS
    done.keys.filter { done[it]!! < cutoff }.forEach { done.remove(it) }
  }

  companion object {
    const val DONE_WINDOW_MS: Long = 45_000L
  }
}
