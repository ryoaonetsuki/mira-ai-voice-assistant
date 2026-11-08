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
 *
 * Round 19 Hardening:
 * - Enforces 2-hour (7,200,000 ms) TTL from timestamp of receipt.
 * - Atomic consumeHandle() for single-use consumption.
 * - Automatic eviction/clearing on expiration or consumption.
 */
class SessionResumptionStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val lock = Any()

    /**
     * Retrieves the stored handle if valid and within the 2-hour TTL.
     * If expired, clears the store and returns null.
     */
    fun getHandle(currentTimeMs: Long = System.currentTimeMillis()): String? {
        synchronized(lock) {
            val handle = prefs.getString(KEY_RESUMPTION_HANDLE, null)
            if (handle.isNullOrBlank()) {
                return null
            }

            val timestamp = prefs.getLong(KEY_HANDLE_TIMESTAMP, 0L)
            val age = currentTimeMs - timestamp
            if (timestamp <= 0L || age >= TTL_MS || age < 0L) {
                Log.d(TAG, "Stored session resumption handle expired (ageMs=$age, ttlMs=$TTL_MS); clearing")
                LiveVoiceDiagnostics.recordResumptionHandleEvent("expired (len=${handle.length})")
                clearHandle()
                return null
            }

            Log.d(TAG, "Loaded session resumption handle (length=${handle.length}, ageMs=$age)")
            return handle
        }
    }

    /**
     * Atomically retrieves and clears the stored handle if valid and within the 2-hour TTL.
     * Subsequent calls will return null (single-use guarantee).
     * If the handle is expired, clears the store and returns null.
     */
    fun consumeHandle(currentTimeMs: Long = System.currentTimeMillis()): String? {
        synchronized(lock) {
            val handle = prefs.getString(KEY_RESUMPTION_HANDLE, null)
            if (handle.isNullOrBlank()) {
                return null
            }

            val timestamp = prefs.getLong(KEY_HANDLE_TIMESTAMP, 0L)
            val age = currentTimeMs - timestamp
            // Always clear whether valid or expired, guaranteeing single-use
            clearHandle()

            if (timestamp <= 0L || age >= TTL_MS || age < 0L) {
                Log.d(TAG, "Consumed expired session resumption handle (ageMs=$age); rejected and cleared")
                LiveVoiceDiagnostics.recordResumptionHandleEvent("expired (len=${handle.length})")
                return null
            }

            Log.i(TAG, "Consumed valid session resumption handle (length=${handle.length}, ageMs=$age)")
            LiveVoiceDiagnostics.recordResumptionHandleEvent("consumed (len=${handle.length})")
            return handle
        }
    }

    /**
     * Persists the given handle along with its timestamp.
     */
    fun saveHandle(handle: String, timestampMs: Long = System.currentTimeMillis()) {
        if (handle.isBlank()) return
        synchronized(lock) {
            prefs.edit()
                .putString(KEY_RESUMPTION_HANDLE, handle)
                .putLong(KEY_HANDLE_TIMESTAMP, timestampMs)
                .apply()
            Log.i(TAG, "Saved session resumption handle (length=${handle.length}, timestamp=$timestampMs)")
            LiveVoiceDiagnostics.recordResumptionHandleEvent("saved (len=${handle.length})")
        }
    }

    /**
     * Clears both the handle and its timestamp from storage.
     */
    fun clearHandle() {
        synchronized(lock) {
            prefs.edit()
                .remove(KEY_RESUMPTION_HANDLE)
                .remove(KEY_HANDLE_TIMESTAMP)
                .apply()
            Log.d(TAG, "Cleared stored session resumption handle and timestamp")
            LiveVoiceDiagnostics.recordResumptionHandleEvent("cleared")
        }
    }

    companion object {
        private const val TAG = "MiraResumptionStore"
        const val PREFS_NAME = "mira_session_resumption"
        const val KEY_RESUMPTION_HANDLE = "resumption_handle"
        const val KEY_HANDLE_TIMESTAMP = "handle_timestamp"
        const val TTL_MS = 2 * 60 * 60 * 1000L // 2 hours = 7_200_000 ms
    }
}
