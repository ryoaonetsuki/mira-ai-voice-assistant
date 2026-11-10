// Ported from Jarvis (github.com/AbuZar-Ansarii/Jarvis), MIT Licence, Copyright (c) 2026 AbuZar Ansarii — Dart → Kotlin port for Mira.
package io.github.ryoaonetsuki.mira.automation.agent

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SkillSettings internal constructor(
    private val getFlag: () -> Boolean,
    private val setFlag: (Boolean) -> Unit
) {
    private val _skillsEnabledFlow = MutableStateFlow(getFlag())
    val skillsEnabledFlow: StateFlow<Boolean> = _skillsEnabledFlow.asStateFlow()

    val isSkillsEnabled: Boolean
        get() = _skillsEnabledFlow.value

    fun get(): Boolean = _skillsEnabledFlow.value

    fun set(enabled: Boolean) {
        _skillsEnabledFlow.value = enabled
        setFlag(enabled)
    }

    constructor(prefs: SharedPreferences) : this(
        getFlag = { prefs.getBoolean(KEY_SKILLS_ENABLED, true) },
        setFlag = { enabled -> prefs.edit().putBoolean(KEY_SKILLS_ENABLED, enabled).apply() }
    )

    companion object {
        const val PREFS_NAME = "mira_skills"
        const val KEY_SKILLS_ENABLED = "skills_enabled"

        @Volatile
        private var instance: SkillSettings? = null

        fun getInstance(context: Context): SkillSettings {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val targetContext = runCatching { context.applicationContext }.getOrNull() ?: context
                    val prefs = targetContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    SkillSettings(prefs).also { instance = it }
                }
            }
        }
    }
}
