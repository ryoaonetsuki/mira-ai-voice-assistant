// Ported from Jarvis (github.com/AbuZar-Ansarii/Jarvis), MIT Licence, Copyright (c) 2026 AbuZar Ansarii — Dart → Kotlin port for Mira.
package io.github.ryoaonetsuki.mira.automation.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

interface SkillStore {
  fun loadRaw(): String?
  fun saveRaw(json: String)
}

class SharedPreferencesSkillStore(
  private val context: Context
) : SkillStore {
  private val prefs by lazy {
    val targetContext = runCatching { context.applicationContext }.getOrNull() ?: context
    targetContext.getSharedPreferences("mira_skills", Context.MODE_PRIVATE)
  }

  override fun loadRaw(): String? {
    return prefs.getString(SkillMemoryService.STORAGE_KEY, null)
  }

  override fun saveRaw(json: String) {
    prefs.edit().putString(SkillMemoryService.STORAGE_KEY, json).apply()
  }
}

/**
 * Local "macro replay" memory: stores successful action sequences per goal
 * so repeated tasks skip LLM calls entirely.
 *
 * Storage: SharedPreferences key `skill_memory_v1` (JSON list). Kept
 * deliberately simple — skills are small and few.
 */
class SkillMemoryService(
  private val store: SkillStore
) {
  private val lock = Any()
  private var cache: MutableList<Skill>? = null
  private val _skillsFlow = MutableStateFlow<List<Skill>>(emptyList())
  val skillsFlow: StateFlow<List<Skill>> = _skillsFlow.asStateFlow()

  init {
    synchronized(lock) {
      load()
    }
  }

  private fun load(): MutableList<Skill> {
    cache?.let { return it }
    val raw = try {
      store.loadRaw()
    } catch (e: Throwable) {
      logWarning("Failed to load raw skill memory: ${e.message}")
      null
    }
    if (raw.isNullOrBlank()) {
      val emptyList = mutableListOf<Skill>()
      cache = emptyList
      _skillsFlow.value = emptyList
      return emptyList
    }
    return try {
      val array = JSONArray(raw)
      val list = mutableListOf<Skill>()
      for (i in 0 until array.length()) {
        val obj = array.optJSONObject(i)
        if (obj != null) {
          list.add(Skill.fromJson(obj))
        }
      }
      cache = list
      _skillsFlow.value = list.toList()
      list
    } catch (e: Throwable) {
      logWarning("Corrupt skill memory JSON, resetting to empty: ${e.message}")
      val emptyList = mutableListOf<Skill>()
      cache = emptyList
      _skillsFlow.value = emptyList
      emptyList
    }
  }

  private fun persist() {
    val skills = cache ?: return
    try {
      val array = JSONArray()
      for (skill in skills) {
        array.put(skill.toJson())
      }
      store.saveRaw(array.toString())
      _skillsFlow.value = skills.toList()
    } catch (e: Throwable) {
      logWarning("Failed to persist skill memory: ${e.message}")
    }
  }

  fun all(): List<Skill> = synchronized(lock) {
    return load().toList()
  }

  fun getAllSkills(): List<Skill> = all()

  /**
   * Finds a stored skill matching [goal]: exact raw match first,
   * then exact normalized match, then token Jaccard similarity >= 0.75.
   */
  fun findMatch(goal: String): Skill? = synchronized(lock) {
    val clean = goal.trim()
    if (clean.isEmpty()) return null
    val skills = load()

    // 1. Exact raw match first
    for (s in skills) {
      if (s.goal.equals(clean, ignoreCase = true)) return s
    }

    // 2. Exact normalized match
    val normalized = Skill.normalize(clean)
    if (normalized.isEmpty()) return null
    for (s in skills) {
      if (s.normalizedGoal == normalized) return s
    }

    // 3. Token Jaccard similarity >= MATCH_THRESHOLD
    var best: Skill? = null
    var bestScore = 0.0
    for (s in skills) {
      val score = Skill.similarity(clean, s.goal)
      if (score > bestScore) {
        bestScore = score
        best = s
      }
    }
    return if (bestScore >= MATCH_THRESHOLD) best else null
  }

  /**
   * Stores (or refreshes) the action sequence for [goal]. Terminal `done`
   * steps are stripped since replay ends when the list is exhausted.
   */
  fun saveOrUpdate(goal: String, steps: List<TaskStep>): Skill = synchronized(lock) {
    val skills = load()
    val cleaned = steps.filter { it.action != TaskStep.ACTION_DONE && it.isSupported }
    val normalized = Skill.normalize(goal)

    if (cleaned.isEmpty()) {
      logWarning("saveOrUpdate skipped: no valid steps provided for goal '$goal'")
      val existingIndex = skills.indexOfFirst { it.normalizedGoal == normalized }
      if (existingIndex >= 0) return skills[existingIndex]
      return Skill(goal = goal, steps = emptyList())
    }

    val existingIndex = skills.indexOfFirst { it.normalizedGoal == normalized }
    if (existingIndex >= 0) {
      val existing = skills[existingIndex]
      val updated = Skill(
        id = existing.id,
        goal = goal,
        steps = cleaned,
        successCount = existing.successCount,
        lastUsedAt = System.currentTimeMillis(),
        createdAt = existing.createdAt
      )
      skills[existingIndex] = updated
      persist()
      return updated
    }

    val skill = Skill(
      goal = goal,
      steps = cleaned
    )
    skills.add(0, skill)
    while (skills.size > MAX_SKILLS) {
      skills.removeAt(skills.lastIndex)
    }
    persist()
    return skill
  }

  fun markReplayOutcome(skillId: String, success: Boolean): Unit = synchronized(lock) {
    val skills = load()
    val index = skills.indexOfFirst { it.id == skillId }
    if (index < 0) return
    val existing = skills[index]
    val now = System.currentTimeMillis()
    if (success) {
      existing.consecutiveFailures = 0
      existing.successCount += 1
      existing.lastUsedAt = now
      skills[index] = existing.copy(
        consecutiveFailures = 0,
        successCount = existing.successCount,
        lastUsedAt = now
      )
    } else {
      // Do not delete a macro on a single transient failure (e.g. feed changed, slow network).
      // Remove only after MAX_CONSECUTIVE_FAILURES (3) consecutive failures.
      existing.consecutiveFailures += 1
      existing.lastFailedAt = now
      if (existing.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
        skills.removeAt(index)
      } else {
        skills[index] = existing.copy(
          consecutiveFailures = existing.consecutiveFailures,
          lastFailedAt = now
        )
      }
    }
    persist()
  }

  fun renameSkill(skillId: String, newGoal: String): Boolean = synchronized(lock) {
    val trimmed = newGoal.trim()
    if (trimmed.isEmpty()) return false
    val skills = load()
    val index = skills.indexOfFirst { it.id == skillId }
    if (index < 0) return false

    val existing = skills[index]
    val updated = Skill(
      id = existing.id,
      goal = trimmed,
      steps = existing.steps,
      successCount = existing.successCount,
      consecutiveFailures = existing.consecutiveFailures,
      lastFailedAt = existing.lastFailedAt,
      lastUsedAt = existing.lastUsedAt,
      createdAt = existing.createdAt,
      normalizedGoal = Skill.normalize(trimmed)
    )
    skills[index] = updated
    persist()
    return true
  }

  fun deleteSkill(skillId: String): Boolean = synchronized(lock) {
    val skills = load()
    val removed = skills.removeAll { it.id == skillId }
    if (removed) {
      persist()
    }
    return removed
  }

  fun delete(skillId: String): Unit {
    deleteSkill(skillId)
  }

  fun clear(): Unit = synchronized(lock) {
    val skills = load()
    skills.clear()
    persist()
  }

  private fun logWarning(msg: String) {
    try {
      Log.w(TAG, msg)
    } catch (_: Throwable) {
      // Ignored in pure JVM test environments where Log is not mocked
    }
  }

  companion object {
    private const val TAG = "SkillMemoryService"
    const val STORAGE_KEY: String = "skill_memory_v1"
    const val MATCH_THRESHOLD: Double = 0.75
    const val MAX_SKILLS: Int = 100
    const val MAX_CONSECUTIVE_FAILURES: Int = 3

    @Volatile
    private var instance: SkillMemoryService? = null

    fun getInstance(context: Context): SkillMemoryService {
      return instance ?: synchronized(this) {
        instance ?: SkillMemoryService(SharedPreferencesSkillStore(context)).also {
          instance = it
        }
      }
    }
  }
}
