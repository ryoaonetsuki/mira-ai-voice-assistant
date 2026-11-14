package io.github.ryoaonetsuki.mira.automation.agent

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TapNumberSettings internal constructor(
    private val getFlag: () -> Boolean,
    private val setFlag: (Boolean) -> Unit
) {
    private val _tapNumbersEnabledFlow = MutableStateFlow(getFlag())
    val tapNumbersEnabledFlow: StateFlow<Boolean> = _tapNumbersEnabledFlow.asStateFlow()

    val isTapNumbersEnabled: Boolean
        get() = _tapNumbersEnabledFlow.value

    fun get(): Boolean = _tapNumbersEnabledFlow.value

    fun set(enabled: Boolean) {
        _tapNumbersEnabledFlow.value = enabled
        setFlag(enabled)
    }

    constructor(prefs: SharedPreferences) : this(
        getFlag = { prefs.getBoolean(KEY_TAP_NUMBERS_ENABLED, false) },
        setFlag = { enabled -> prefs.edit().putBoolean(KEY_TAP_NUMBERS_ENABLED, enabled).apply() }
    )

    companion object {
        const val PREFS_NAME = "mira_tap_numbers"
        const val KEY_TAP_NUMBERS_ENABLED = "tap_numbers_enabled"

        @Volatile
        private var instance: TapNumberSettings? = null

        fun getInstance(context: Context): TapNumberSettings {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val targetContext = runCatching { context.applicationContext }.getOrNull() ?: context
                    val prefs = targetContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    TapNumberSettings(prefs).also { instance = it }
                }
            }
        }
    }
}
