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
  var consecutiveFailures: Int = 0,
  var lastFailedAt: Long = 0L,
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
    json.put("consecutiveFailures", consecutiveFailures)
    json.put("lastFailedAt", lastFailedAt)
    json.put("lastUsedAt", Instant.ofEpochMilli(lastUsedAt).toString())
    json.put("createdAt", Instant.ofEpochMilli(createdAt).toString())
    return json
  }

  companion object {
    private val BANGLA_SUFFIXES = listOf(
        "গুলো", "গুলা", "খানি", "খানা", "দের", "ের", "এর", "তে", "টা", "টি", "কে", "র", "ে", "এ"
    )

    private val STANDALONE_PARTICLES = setOf(
        "তো", "না", "গো", "প্লিজ", "দয়া", "দয়া", "করে", "please", "pls"
    )

    private fun stripSuffix(token: String): String {
        for (suffix in BANGLA_SUFFIXES) {
            if (token.endsWith(suffix) && token.length - suffix.length >= 3) {
                return token.substring(0, token.length - suffix.length)
            }
        }
        if (token.endsWith("ta") && token.length - 2 >= 3) {
            return token.substring(0, token.length - 2)
        }
        return token
    }

    fun normalize(input: String): String {
        var text = input.lowercase().trim().replace("-", " ").replace(Regex("\\s+"), " ")

        // Treat verb equivalents (open / khulo, search / khojo, play / chalao, etc.) as identical intent tokens
        text = text
            .replace("ওপেন করো", "খুলো")
            .replace("ওপেন কর", "খুলো")
            .replace("খোলো", "খুলো")
            .replace("খোল", "খুলো")
            .replace("চালু করো", "খুলো")
            .replace("চালু কর", "খুলো")
            .replace("চালু", "খুলো")
            .replace("open koro", "খুলো")
            .replace("open kor", "খুলো")
            .replace("open", "খুলো")
            .replace("khulo", "খুলো")
            .replace("kholo", "খুলো")
            .replace("khol", "খুলো")
            .replace("ওপেন", "খুলো")
            .replace("সার্চ করো", "খুঁজো")
            .replace("সার্চ কর", "খুঁজো")
            .replace("সার্চ", "খুঁজো")
            .replace("খুজো", "খুঁজো")
            .replace("search koro", "খুঁজো")
            .replace("search kor", "খুঁজো")
            .replace("search", "খুঁজো")
            .replace("প্লে করো", "চালাও")
            .replace("প্লে কর", "চালাও")
            .replace("প্লে", "চালাও")
            .replace("বাজাও", "চালাও")
            .replace("play koro", "চালাও")
            .replace("play", "চালাও")
            .replace("সেন্ড করো", "পাঠাও")
            .replace("সেন্ড কর", "পাঠাও")
            .replace("সেন্ড", "পাঠাও")
            .replace("send koro", "পাঠাও")
            .replace("send", "পাঠাও")
            .replace("ফোন করো", "কল")
            .replace("কল করো", "কল")
            .replace("call koro", "কল")
            .replace("call", "কল")
            .replace("বন্ধ করো", "বন্ধ")
            .replace("বন্ধ কর", "বন্ধ")
            .replace("close koro", "বন্ধ")
            .replace("close", "বন্ধ")

        // Strip particles and suffixes from tokens
        val words = text.split(" ")
            .map { it.trim() }
            .filter { it.isNotBlank() && it !in STANDALONE_PARTICLES }
            .map { stripSuffix(it) }

        return words.joinToString(" ")
    }

    private fun tokens(normalizedOrRaw: String): Set<String> {
      return normalize(normalizedOrRaw)
        .split(" ")
        .filter { it.isNotBlank() && (it.length > 2 || (it.length >= 2 && it.any { c -> c in '\u0980'..'\u09FF' }) || it == "কল" || it == "বন্ধ") }
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
      val consecutiveFailures = json.optInt("consecutiveFailures", 0).coerceAtLeast(0)
      val lastFailedAt = json.optLong("lastFailedAt", 0L).coerceAtLeast(0L)
      val lastUsedAt = parseTimestamp(json.opt("lastUsedAt"))
      val createdAt = parseTimestamp(json.opt("createdAt"))

      return Skill(
        id = id,
        goal = goal,
        steps = stepsList,
        successCount = successCount,
        consecutiveFailures = consecutiveFailures,
        lastFailedAt = lastFailedAt,
        lastUsedAt = lastUsedAt,
        createdAt = createdAt
      )
    }
  }
}
