package io.github.ryoaonetsuki.mira.automation.agent

import android.content.SharedPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TapNumberSettingsTest {

    private class FakeSharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = values.toMap()
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValue: Set<String>?): Set<String>? = values[key] as? Set<String> ?: defValue
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(values)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}

        private class FakeEditor(private val values: MutableMap<String, Any?>) : SharedPreferences.Editor {
            override fun putString(key: String, value: String?): SharedPreferences.Editor { values[key] = value; return this }
            override fun putStringSet(key: String, value: Set<String>?): SharedPreferences.Editor { values[key] = value; return this }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor { values[key] = value; return this }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor { values[key] = value; return this }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor { values[key] = value; return this }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { values[key] = value; return this }
            override fun remove(key: String): SharedPreferences.Editor { values.remove(key); return this }
            override fun clear(): SharedPreferences.Editor { values.clear(); return this }
            override fun commit(): Boolean = true
            override fun apply() {}
        }
    }

    @Test
    fun defaultIsFalse() {
        val prefs = FakeSharedPreferences()
        val settings = TapNumberSettings(prefs)
        assertFalse(settings.get())
        assertFalse(settings.isTapNumbersEnabled)
    }

    @Test
    fun setTruePersistsAndUpdatesFlow() {
        val prefs = FakeSharedPreferences()
        val settings = TapNumberSettings(prefs)
        settings.set(true)
        assertTrue(settings.get())
        assertTrue(settings.isTapNumbersEnabled)
        assertTrue(prefs.getBoolean(TapNumberSettings.KEY_TAP_NUMBERS_ENABLED, false))
    }

    @Test
    fun setFalseTurnsOffAgain() {
        val prefs = FakeSharedPreferences()
        val settings = TapNumberSettings(prefs)
        settings.set(true)
        settings.set(false)
        assertFalse(settings.get())
        assertFalse(settings.isTapNumbersEnabled)
        assertFalse(prefs.getBoolean(TapNumberSettings.KEY_TAP_NUMBERS_ENABLED, false))
    }
}
