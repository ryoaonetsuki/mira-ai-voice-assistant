// Ported from Jarvis (github.com/AbuZar-Ansarii/Jarvis), MIT Licence, Copyright (c) 2026 AbuZar Ansarii — Dart → Kotlin port for Mira.
package io.github.ryoaonetsuki.mira.automation.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class TaskHistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val goal: String,
    val status: TaskStatus,
    val stepsUsed: Int,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val stepDescriptions: List<String> = emptyList()
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("goal", goal)
        json.put("status", status.name)
        json.put("stepsUsed", stepsUsed)
        json.put("startedAtMs", startedAtMs)
        json.put("finishedAtMs", finishedAtMs)
        val arr = JSONArray()
        for (desc in stepDescriptions) {
            arr.put(desc)
        }
        json.put("stepDescriptions", arr)
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): TaskHistoryEntry {
            val id = json.optString("id", UUID.randomUUID().toString())
            val goal = json.optString("goal", "")
            val statusStr = json.optString("status", TaskStatus.FAILED.name)
            val status = try {
                TaskStatus.valueOf(statusStr)
            } catch (_: Throwable) {
                TaskStatus.FAILED
            }
            val stepsUsed = json.optInt("stepsUsed", 0)
            val startedAtMs = json.optLong("startedAtMs", 0L)
            val finishedAtMs = json.optLong("finishedAtMs", 0L)
            val arr = json.optJSONArray("stepDescriptions")
            val stepDescriptions = mutableListOf<String>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    stepDescriptions.add(arr.optString(i, ""))
                }
            }
            return TaskHistoryEntry(
                id = id,
                goal = goal,
                status = status,
                stepsUsed = stepsUsed,
                startedAtMs = startedAtMs,
                finishedAtMs = finishedAtMs,
                stepDescriptions = stepDescriptions
            )
        }
    }
}
