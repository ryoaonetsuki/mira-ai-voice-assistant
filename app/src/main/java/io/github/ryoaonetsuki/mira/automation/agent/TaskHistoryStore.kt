// Ported from Jarvis (github.com/AbuZar-Ansarii/Jarvis), MIT Licence, Copyright (c) 2026 AbuZar Ansarii — Dart → Kotlin port for Mira.
package io.github.ryoaonetsuki.mira.automation.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

interface TaskHistoryRawStore {
    fun loadRaw(): String?
    fun saveRaw(json: String)
}

class SharedPreferencesTaskHistoryRawStore(
    private val context: Context
) : TaskHistoryRawStore {
    private val prefs by lazy {
        val targetContext = runCatching { context.applicationContext }.getOrNull() ?: context
        targetContext.getSharedPreferences(TaskHistoryStore.PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun loadRaw(): String? {
        return prefs.getString(TaskHistoryStore.STORAGE_KEY, null)
    }

    override fun saveRaw(json: String) {
        prefs.edit().putString(TaskHistoryStore.STORAGE_KEY, json).apply()
    }
}

class TaskHistoryStore(
    private val rawStore: TaskHistoryRawStore
) {
    constructor(
        loadRaw: () -> String?,
        saveRaw: (String) -> Unit
    ) : this(object : TaskHistoryRawStore {
        override fun loadRaw(): String? = loadRaw()
        override fun saveRaw(json: String) = saveRaw(json)
    })

    private val lock = Any()
    private var cache: MutableList<TaskHistoryEntry>? = null
    private val _entriesFlow = MutableStateFlow<List<TaskHistoryEntry>>(emptyList())
    val entriesFlow: StateFlow<List<TaskHistoryEntry>> = _entriesFlow.asStateFlow()

    init {
        synchronized(lock) {
            load()
        }
    }

    private fun load(): MutableList<TaskHistoryEntry> {
        cache?.let { return it }
        val raw = try {
            rawStore.loadRaw()
        } catch (e: Throwable) {
            logWarning("Failed to load raw task history: ${e.message}")
            null
        }
        if (raw.isNullOrBlank()) {
            val emptyList = mutableListOf<TaskHistoryEntry>()
            cache = emptyList
            _entriesFlow.value = emptyList
            return emptyList
        }
        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<TaskHistoryEntry>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i)
                if (obj != null) {
                    list.add(TaskHistoryEntry.fromJson(obj))
                }
            }
            cache = list
            _entriesFlow.value = list.toList()
            list
        } catch (e: Throwable) {
            logWarning("Corrupt task history JSON, resetting to empty: ${e.message}")
            val emptyList = mutableListOf<TaskHistoryEntry>()
            cache = emptyList
            _entriesFlow.value = emptyList
            emptyList
        }
    }

    private fun persist() {
        val entries = cache ?: return
        try {
            val array = JSONArray()
            for (entry in entries) {
                array.put(entry.toJson())
            }
            rawStore.saveRaw(array.toString())
            _entriesFlow.value = entries.toList()
        } catch (e: Throwable) {
            logWarning("Failed to persist task history: ${e.message}")
        }
    }

    fun all(): List<TaskHistoryEntry> = synchronized(lock) {
        return load().toList()
    }

    fun record(entry: TaskHistoryEntry): Unit = synchronized(lock) {
        val list = load()
        list.add(0, entry)
        while (list.size > MAX_ENTRIES) {
            list.removeAt(list.lastIndex)
        }
        persist()
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        val list = load()
        val removed = list.removeAll { it.id == id }
        if (removed) {
            persist()
        }
        return removed
    }

    fun clear(): Unit = synchronized(lock) {
        val list = load()
        list.clear()
        persist()
    }

    private fun logWarning(msg: String) {
        try {
            Log.w(TAG, msg)
        } catch (_: Throwable) {
            // Test environment
        }
    }

    companion object {
        private const val TAG = "TaskHistoryStore"
        const val PREFS_NAME = "mira_task_history"
        const val STORAGE_KEY = "task_history_v1"
        const val MAX_ENTRIES = 50

        @Volatile
        private var instance: TaskHistoryStore? = null

        fun getInstance(context: Context): TaskHistoryStore {
            return instance ?: synchronized(this) {
                instance ?: TaskHistoryStore(SharedPreferencesTaskHistoryRawStore(context)).also {
                    instance = it
                }
            }
        }
    }
}
