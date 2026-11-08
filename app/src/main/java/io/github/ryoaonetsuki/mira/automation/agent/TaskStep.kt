// Ported from Jarvis (github.com/AbuZar-Ansarii/Jarvis), MIT Licence, Copyright (c) 2026 AbuZar Ansarii — Dart → Kotlin port for Mira.
package io.github.ryoaonetsuki.mira.automation.agent

import org.json.JSONObject

/**
 * One atomic action decided by the LLM (or replayed from skill memory).
 *
 * Wire format expected from the model:
 * ```json
 * {
 *   "action": "click_text" | "click_at" | "type_text" | "press_enter" |
 *             "scroll" | "swipe" | "press_back" | "press_home" |
 *             "open_app" | "wait" | "done",
 *   "params": { ... },
 *   "reasoning": "short explanation",
 *   "is_complete": false
 * }
 * ```
 */
data class TaskStep(
  val action: String,
  val params: Map<String, Any?> = emptyMap(),
  val reasoning: String = "",
  val isComplete: Boolean = false
) {

  val isSupported: Boolean
    get() = supportedActions.contains(action)

  /**
   * Stable signature used by the recovery engine to detect repeated actions.
   */
  val signature: String
    get() {
      val sortedParams = JSONObject()
      for (key in params.keys.sorted()) {
        val value = params[key]
        if (value == null) {
          sortedParams.put(key, JSONObject.NULL)
        } else {
          sortedParams.put(key, value)
        }
      }
      return "$action:$sortedParams"
    }

  /**
   * Numeric param with forgiving parsing (int / double / numeric string).
   */
  fun intParam(key: String): Int? {
    val v = params[key] ?: return null
    return when (v) {
      is Int -> v
      is Long -> v.toInt()
      is Number -> kotlin.math.round(v.toDouble()).toInt()
      is String -> {
        v.toIntOrNull() ?: v.toDoubleOrNull()?.let { kotlin.math.round(it).toInt() }
      }
      else -> null
    }
  }

  fun stringParam(key: String): String? {
    val v = params[key] ?: return null
    val s = v.toString()
    return if (s.isEmpty()) null else s
  }

  fun toJson(): JSONObject {
    val json = JSONObject()
    json.put("action", action)
    val paramsJson = JSONObject()
    for ((key, value) in params) {
      if (value == null) {
        paramsJson.put(key, JSONObject.NULL)
      } else {
        paramsJson.put(key, value)
      }
    }
    json.put("params", paramsJson)
    json.put("reasoning", reasoning)
    json.put("is_complete", isComplete)
    return json
  }

  override fun toString(): String {
    return "TaskStep($action, params=$params, complete=$isComplete)"
  }

  companion object {
    const val ACTION_CLICK_TEXT: String = "click_text"
    const val ACTION_CLICK_AT: String = "click_at"
    const val ACTION_TYPE_TEXT: String = "type_text"
    const val ACTION_PRESS_ENTER: String = "press_enter"
    const val ACTION_SCROLL: String = "scroll"
    const val ACTION_SWIPE: String = "swipe"
    const val ACTION_PRESS_BACK: String = "press_back"
    const val ACTION_PRESS_HOME: String = "press_home"
    const val ACTION_OPEN_APP: String = "open_app"
    const val ACTION_WAIT: String = "wait"
    const val ACTION_DONE: String = "done"

    // Lowercase constant aliases matching Dart naming conventions
    const val actionClickText: String = ACTION_CLICK_TEXT
    const val actionClickAt: String = ACTION_CLICK_AT
    const val actionTypeText: String = ACTION_TYPE_TEXT
    const val actionPressEnter: String = ACTION_PRESS_ENTER
    const val actionScroll: String = ACTION_SCROLL
    const val actionSwipe: String = ACTION_SWIPE
    const val actionPressBack: String = ACTION_PRESS_BACK
    const val actionPressHome: String = ACTION_PRESS_HOME
    const val actionOpenApp: String = ACTION_OPEN_APP
    const val actionWait: String = ACTION_WAIT
    const val actionDone: String = ACTION_DONE

    val supportedActions: Set<String> = setOf(
      ACTION_CLICK_TEXT,
      ACTION_CLICK_AT,
      ACTION_TYPE_TEXT,
      ACTION_PRESS_ENTER,
      ACTION_SCROLL,
      ACTION_SWIPE,
      ACTION_PRESS_BACK,
      ACTION_PRESS_HOME,
      ACTION_OPEN_APP,
      ACTION_WAIT,
      ACTION_DONE
    )

    fun fromJson(json: JSONObject): TaskStep {
      var rawAction = (json.opt("action") ?: "").toString().trim().lowercase()
      val params = mutableMapOf<String, Any?>()
      val rawParams = json.opt("params")
      if (rawParams is JSONObject) {
        val keys = rawParams.keys()
        while (keys.hasNext()) {
          val key = keys.next()
          val value = rawParams.opt(key)
          params[key] = if (value == JSONObject.NULL) null else value
        }
      } else if (rawParams is Map<*, *>) {
        for ((k, v) in rawParams) {
          if (k != null) {
            params[k.toString()] = if (v == JSONObject.NULL) null else v
          }
        }
      }

      // Normalize action aliases commonly produced by various LLM models
      if (rawAction == "open" ||
          rawAction == "launch" ||
          rawAction == "start" ||
          rawAction == "launch_app" ||
          rawAction == "openapp") {
        rawAction = ACTION_OPEN_APP
        if (!params.containsKey("app")) {
          params["app"] = params["name"]
            ?: params["package"]
            ?: params["app_name"]
            ?: params["target"]
            ?: json.opt("app")
            ?: ""
        }
      } else if (rawAction == "click" ||
          rawAction == "tap" ||
          rawAction == "press" ||
          rawAction == "touch") {
        if (params.containsKey("x") || params.containsKey("y") || params.containsKey("coordinates")) {
          rawAction = ACTION_CLICK_AT
        } else if (params.containsKey("app")) {
          rawAction = ACTION_OPEN_APP
        } else {
          rawAction = ACTION_CLICK_TEXT
          if (!params.containsKey("text")) {
            params["text"] = params["label"]
              ?: params["target"]
              ?: params["name"]
              ?: params["value"]
              ?: json.opt("text")
              ?: ""
          }
        }
      } else if (rawAction == "type" ||
          rawAction == "input" ||
          rawAction == "write" ||
          rawAction == "enter_text" ||
          rawAction == "set_text") {
        rawAction = ACTION_TYPE_TEXT
        if (!params.containsKey("text")) {
          params["text"] = params["input"]
            ?: params["query"]
            ?: params["value"]
            ?: params["content"]
            ?: json.opt("text")
            ?: ""
        }
      } else if (rawAction == "enter" || rawAction == "search" || rawAction == "submit") {
        rawAction = ACTION_PRESS_ENTER
      } else if (rawAction == "back") {
        rawAction = ACTION_PRESS_BACK
      } else if (rawAction == "home") {
        rawAction = ACTION_PRESS_HOME
      } else if (rawAction == "finish" ||
          rawAction == "complete" ||
          rawAction == "completed" ||
          rawAction == "success" ||
          rawAction == "end") {
        rawAction = ACTION_DONE
      }

      val reasoning = (json.opt("reasoning") ?: "").toString().let { if (it == "null") "" else it }
      val isComplete = json.optBoolean("is_complete", false) ||
          json.optBoolean("isComplete", false) ||
          rawAction == ACTION_DONE

      return TaskStep(
        action = rawAction,
        params = params,
        reasoning = reasoning,
        isComplete = isComplete
      )
    }

    /**
     * Robustly extracts the first JSON object from raw LLM output, tolerating
     * markdown fences, leading prose, trailing commentary, and natural language.
     */
    fun tryParse(aiText: String): TaskStep? {
      var text = aiText.trim()
      if (text.isEmpty()) return null

      // 1. Strip markdown code fences if present.
      val fenceRegex = Regex("""```(?:json)?\s*([\s\S]*?)```""", RegexOption.IGNORE_CASE)
      val fenceMatch = fenceRegex.find(text)
      if (fenceMatch != null) {
        text = fenceMatch.groupValues[1].trim()
      }

      fun decode(candidate: String): JSONObject? {
        return try {
          val trimmed = candidate.trim()
          if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
          val decoded = JSONObject(trimmed)
          if (decoded.has("action")) decoded else null
        } catch (_: Throwable) {
          null
        }
      }

      // 2. Direct decode attempt
      var parsed = decode(text)

      // 3. Fall back to scanning for balanced { ... } blocks containing "action"
      if (parsed == null) {
        var start = 0
        while (start < text.length) {
          val braceStart = text.indexOf('{', start)
          if (braceStart == -1) break
          var depth = 0
          var end = -1
          for (i in braceStart until text.length) {
            if (text[i] == '{') depth++
            if (text[i] == '}') {
              depth--
              if (depth == 0) {
                end = i
                break
              }
            }
          }
          if (end > braceStart) {
            val candidate = text.substring(braceStart, end + 1)
            if (candidate.contains("\"action\"") || candidate.contains("'action'")) {
              parsed = decode(candidate)
              if (parsed != null) break
            }
            start = braceStart + 1
          } else {
            break
          }
        }
      }

      // 4. Widest balanced { ... } fallback
      if (parsed == null) {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) {
          parsed = decode(text.substring(start, end + 1))
        }
      }

      // 5. If JSON was found, return normalized TaskStep
      if (parsed != null) {
        val step = fromJson(parsed)
        if (step.isSupported) return step
      }

      // 6. Intelligent heuristic fallback for natural language / conversational output from models
      val lower = text.lowercase()
      if (lower.contains("open ") || lower.contains("launch ")) {
        val match = Regex(
          """(?:open|launch)\s+(?:the\s+)?([a-zA-Z0-9\s]+?)(?:\s+to|\s+app|\s+and|\.|\n|$)""",
          RegexOption.IGNORE_CASE
        ).find(text)
        if (match != null) {
          val app = match.groupValues[1].trim()
          if (app.isNotEmpty() && app.length < 35) {
            return TaskStep(
              action = ACTION_OPEN_APP,
              params = mapOf("app" to app),
              reasoning = "Extracted action: open $app"
            )
          }
        }
      }

      if (lower.contains("click ") || lower.contains("tap ") || lower.contains("press ")) {
        val match = Regex(
          """(?:click|tap|press)\s+(?:on\s+)?(?:the\s+)?["']?([^"'.\n]+)["']?""",
          RegexOption.IGNORE_CASE
        ).find(text)
        if (match != null) {
          var target = match.groupValues[1].trim()
          // Clean up prefixes like [0] (Button) Search -> Search
          target = target
            .replace(Regex("""^\([a-zA-Z]+\)\s*"""), "")
            .replace(Regex("""^\[\d+\]\s*"""), "")
            .replace(Regex("""^\([a-zA-Z]+\)\s*"""), "")
            .replace(Regex("""^["'\[\(]+|["'\]\)]+$"""), "")
            .trim()
          if (target.isNotEmpty() && target.length < 50) {
            return TaskStep(
              action = ACTION_CLICK_TEXT,
              params = mapOf("text" to target),
              reasoning = "Extracted action: click $target"
            )
          }
        }
      }

      if (lower.contains("type ") || lower.contains("enter ") || lower.contains("search for ")) {
        val match = Regex(
          """(?:type|enter|search for)\s+["']?([^"'.\n]+)["']?""",
          RegexOption.IGNORE_CASE
        ).find(text)
        if (match != null) {
          val query = match.groupValues[1].trim()
          if (query.isNotEmpty() && query.length < 50) {
            return TaskStep(
              action = ACTION_TYPE_TEXT,
              params = mapOf("text" to query),
              reasoning = "Extracted action: type $query"
            )
          }
        }
      }

      if (lower.contains("task complete") ||
          lower.contains("task completed") ||
          lower.contains("goal complete") ||
          lower.contains("successfully installed") ||
          lower.contains("finished")) {
        return TaskStep(
          action = ACTION_DONE,
          reasoning = "Task marked complete in response",
          isComplete = true
        )
      }

      return null
    }
  }
}
