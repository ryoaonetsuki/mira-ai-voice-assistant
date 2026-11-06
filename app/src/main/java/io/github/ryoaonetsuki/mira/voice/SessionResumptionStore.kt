package io.github.ryoaonetsuki.mira.voice

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Manages persisting and retrieving Gemini Live session resumption handles
 * (sessionResumptionUpdate.newHandle).
 *
 * CRITICAL: The raw handle value must NEVER be logged to maintain BYOK and session security.
 * Only lengths or non-sensitive status are logged.
 */
class SessionResumptionStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun getHandle(): String? {
        val handle = prefs.getString(KEY_RESUMPTION_HANDLE, null)
        if (!handle.isNullOrBlank()) {
            Log.d(TAG, "Loaded session resumption handle (length=${handle.length})")
            return handle
        }
        return null
    }

    fun saveHandle(handle: String) {
        if (handle.isBlank()) return
        prefs.edit().putString(KEY_RESUMPTION_HANDLE, handle).apply()
        Log.i(TAG, "Saved session resumption handle (length=${handle.length})")
    }

    fun clearHandle() {
        prefs.edit().remove(KEY_RESUMPTION_HANDLE).apply()
        Log.d(TAG, "Cleared stored session resumption handle")
    }

    companion object {
        private const val TAG = "MiraResumptionStore"
        private const val PREFS_NAME = "mira_session_resumption"
        private const val KEY_RESUMPTION_HANDLE = "resumption_handle"
    }
}
