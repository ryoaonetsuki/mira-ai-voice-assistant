package io.github.ryoaonetsuki.mira.service

import ai.pipecat.client.types.Value
import android.util.Log
import io.github.ryoaonetsuki.mira.data.local.MiraDatabase
import io.github.ryoaonetsuki.mira.data.local.entities.MemoryEntity
import io.github.ryoaonetsuki.mira.voice.VoiceMemoryPreload
import java.util.Locale

private const val TAG = "VoiceMemoryTools"

internal suspend fun executeMemoryTool(
    db: MiraDatabase,
    name: String,
    args: Map<String, String>
): Value.Object? {
    val normName = name.lowercase(Locale.ROOT)
    if (normName !in setOf("remember_fact", "recall_memory", "forget_memory")) {
        return null
    }

    return try {
        when (normName) {
            "remember_fact" -> {
                val category = args["category"]?.trim().orEmpty().ifBlank { "General" }
                val fact = (args["fact"] ?: args["text"] ?: "").trim()
                if (fact.isBlank()) {
                    return Value.Object(
                        "result" to Value.Str("Nothing to remember: the fact was empty."),
                        "success" to Value.Bool(false)
                    )
                }

                if (VoiceMemoryPreload.isSensitiveFact(fact)) {
                    return Value.Object(
                        "result" to Value.Str("Not saved: that looks like a password, code or card number."),
                        "success" to Value.Bool(true)
                    )
                }

                if (db.memoryDao().getMemoryCount() >= VoiceMemoryPreload.MAX_FACTS) {
                    db.memoryDao().evictLowestMemory()
                }

                val now = System.currentTimeMillis()
                val existing = db.memoryDao().getMemoryByContent(fact)
                if (existing != null) {
                    db.memoryDao().updateMemory(
                        existing.copy(
                            category = category,
                            importance = 2,
                            origin = "explicit",
                            lastUsedAt = now
                        )
                    )
                } else {
                    db.memoryDao().insertMemory(
                        MemoryEntity(
                            content = fact,
                            category = category,
                            importance = 2,
                            origin = "explicit",
                            type = "fact",
                            lastUsedAt = now
                        )
                    )
                }

                Log.i(TAG, "MIRA_LIVE_VOICE: Saved fact (category=$category, length=${fact.length})")
                Value.Object(
                    "result" to Value.Str("Saved."),
                    "success" to Value.Bool(true)
                )
            }

            "recall_memory" -> {
                val query = (args["query"] ?: args["text"] ?: "").trim()
                if (query.isBlank()) {
                    return Value.Object(
                        "result" to Value.Str("Nothing to recall: the query was empty."),
                        "success" to Value.Bool(false)
                    )
                }

                val facts = db.memoryDao().getAllMemoriesOnce().map {
                    Triple(it.id, it.content, it.category)
                }
                val recalled = VoiceMemoryPreload.scoreFacts(query, facts)
                val now = System.currentTimeMillis()
                recalled.forEach {
                    db.memoryDao().markMemoryUsed(it.id, now)
                }

                val result = if (recalled.isEmpty()) {
                    "No saved memories match that."
                } else {
                    recalled.joinToString("\n") { "- [${it.category}] ${it.text}" }
                }

                Value.Object(
                    "result" to Value.Str(result),
                    "success" to Value.Bool(true)
                )
            }

            "forget_memory" -> {
                val fact = (args["fact"] ?: args["text"] ?: "").trim()
                if (fact.isBlank()) {
                    return Value.Object(
                        "result" to Value.Str("Nothing to forget: the fact was empty."),
                        "success" to Value.Bool(false)
                    )
                }

                val deleted = db.memoryDao().deleteMemoriesContaining(fact)
                val result = if (deleted > 0) {
                    "Deleted $deleted saved memories."
                } else {
                    "No saved memories matched that."
                }

                Value.Object(
                    "result" to Value.Str(result),
                    "success" to Value.Bool(true)
                )
            }

            else -> null
        }
    } catch (e: Throwable) {
        Value.Object("error" to Value.Str("Memory tool failed: ${e.message}"))
    }
}
