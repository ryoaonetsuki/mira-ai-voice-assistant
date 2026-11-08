// Ported from Jarvis (github.com/AbuZar-Ansarii/Jarvis), MIT Licence, Copyright (c) 2026 AbuZar Ansarii — Dart → Kotlin port for Mira.
package io.github.ryoaonetsuki.mira.automation.agent

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/**
 * A memorized, previously successful action sequence for a goal
 * ("macro replay"). Stored locally by `SkillMemoryService` so identical or
 * very similar goals can be executed without calling the LLM again.
 */
data class Skill(
  val id: String = UUID.randomUUID().toString(),
  val goal: String,
  val steps: List<TaskStep>,
  var successCount: Int = 0,
  var lastUsedAt: Long = System.currentTimeMillis(),
  val createdAt: Long = System.currentTimeMillis(),
  val normalizedGoal: String = normalize(goal)
) {

  fun toJson(): JSONObject {
    val json = JSONObject()
    json.put("id", id)
    json.put("goal", goal)
    val stepsArray = JSONArray()
    for (step in steps) {
      stepsArray.put(step.toJson())
    }
    json.put("steps", stepsArray)
    json.put("successCount", successCount)
    json.put("lastUsedAt", Instant.ofEpochMilli(lastUsedAt).toString())
    json.put("createdAt", Instant.ofEpochMilli(createdAt).toString())
    return json
  }

  companion object {
    fun normalize(input: String): String {
      return input.lowercase().trim().replace(Regex("\\s+"), " ")
    }

    private fun tokens(normalizedOrRaw: String): Set<String> {
      return normalize(normalizedOrRaw)
        .split(" ")
        .filter { it.length > 2 }
        .toSet()
    }

    /**
     * Jaccard similarity between token sets of two goals (tokens = words longer than 2 chars).
     * Automatically handles case and whitespace normalization.
     */
    fun similarity(a: String, b: String): Double {
      val tokensA = tokens(a)
      val tokensB = tokens(b)
      if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0
      val intersection = tokensA.intersect(tokensB).size
      val union = tokensA.union(tokensB).size
      return if (union == 0) 0.0 else intersection.toDouble() / union.toDouble()
    }

    private fun parseTimestamp(raw: Any?): Long {
      if (raw == null) return System.currentTimeMillis()
      if (raw is Number) return raw.toLong()
      val str = raw.toString().trim()
      if (str.isEmpty() || str == "null") return System.currentTimeMillis()
      str.toLongOrNull()?.let { return it }
      return try {
        Instant.parse(str).toEpochMilli()
      } catch (_: Throwable) {
        System.currentTimeMillis()
      }
    }

    fun fromJson(json: JSONObject): Skill {
      val id = json.optString("id").takeIf { it.isNotBlank() && it != "null" }
        ?: UUID.randomUUID().toString()
      val goal = json.optString("goal", "")
      val stepsList = mutableListOf<TaskStep>()
      val stepsArray = json.optJSONArray("steps")
      if (stepsArray != null) {
        for (i in 0 until stepsArray.length()) {
          val stepObj = stepsArray.optJSONObject(i)
          if (stepObj != null) {
            try {
              stepsList.add(TaskStep.fromJson(stepObj))
            } catch (_: Throwable) {}
          }
        }
      }
      val successCount = when (val sc = json.opt("successCount")) {
        is Number -> sc.toInt()
        is String -> sc.toIntOrNull() ?: 0
        else -> 0
      }
      val lastUsedAt = parseTimestamp(json.opt("lastUsedAt"))
      val createdAt = parseTimestamp(json.opt("createdAt"))

      return Skill(
        id = id,
        goal = goal,
        steps = stepsList,
        successCount = successCount,
        lastUsedAt = lastUsedAt,
        createdAt = createdAt
      )
    }
  }
}
