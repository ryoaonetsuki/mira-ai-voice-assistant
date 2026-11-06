package io.github.ryoaonetsuki.mira.data.preferences

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecurePreferences(context: Context) {

    private val sharedPreferences: SharedPreferences? by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_FILENAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "EncryptedSharedPreferences init failed: ${e.message}", e)
            null
        }
    }

    fun isSecureStorageAvailable(): Boolean = sharedPreferences != null

    fun getGeminiApiKey(): String? {
        val sp = sharedPreferences ?: return null
        val key = sp.getString(KEY_GEMINI_API_KEY, null)
        return if (!key.isNullOrBlank()) key.trim() else null
    }

    fun setGeminiApiKey(key: String): Boolean {
        val sp = sharedPreferences ?: return false
        sp.edit().putString(KEY_GEMINI_API_KEY, key.trim()).apply()
        return true
    }

    fun clearGeminiApiKey(): Boolean {
        val sp = sharedPreferences ?: return false
        sp.edit().remove(KEY_GEMINI_API_KEY).apply()
        return true
    }

    fun getGroqApiKey(): String? {
        val sp = sharedPreferences ?: return null
        val key = sp.getString(KEY_GROQ_API_KEY, null)
        return if (!key.isNullOrBlank()) key.trim() else null
    }

    fun setGroqApiKey(key: String): Boolean {
        val sp = sharedPreferences ?: return false
        val trimmed = key.trim()
        if (trimmed.isBlank()) {
            return clearGroqApiKey()
        }
        sp.edit().putString(KEY_GROQ_API_KEY, trimmed).apply()
        Log.d(TAG, "Groq API key saved (length=${trimmed.length})")
        return true
    }

    fun clearGroqApiKey(): Boolean {
        val sp = sharedPreferences ?: return false
        sp.edit().remove(KEY_GROQ_API_KEY).apply()
        return true
    }

    fun getConnectorToken(connectorId: String): String? {
        return sharedPreferences?.getString("token_$connectorId", null)
    }

    fun setConnectorToken(connectorId: String, token: String): Boolean {
        val sp = sharedPreferences ?: return false
        sp.edit().putString("token_$connectorId", token.trim()).apply()
        return true
    }

    fun removeConnectorToken(connectorId: String): Boolean {
        val sp = sharedPreferences ?: return false
        sp.edit().remove("token_$connectorId").apply()
        return true
    }

    fun getTelegramChatId(): String? {
        return sharedPreferences?.getString(KEY_TELEGRAM_CHAT_ID, null)
    }

    fun setTelegramChatId(chatId: String): Boolean {
        val sp = sharedPreferences ?: return false
        sp.edit().putString(KEY_TELEGRAM_CHAT_ID, chatId.trim()).apply()
        return true
    }

    fun getConnectorLastUsed(connectorId: String): Long {
        return sharedPreferences?.getLong("last_used_$connectorId", 0L) ?: 0L
    }

    fun setConnectorLastUsed(connectorId: String, epochMillis: Long = System.currentTimeMillis()): Boolean {
        val sp = sharedPreferences ?: return false
        sp.edit().putLong("last_used_$connectorId", epochMillis).apply()
        return true
    }

    fun getPinCode(): String? {
        return sharedPreferences?.getString(KEY_PIN_CODE, null)
    }

    fun setPinCode(pin: String): Boolean {
        val sp = sharedPreferences ?: return false
        sp.edit().putString(KEY_PIN_CODE, pin.trim()).apply()
        return true
    }

    fun isFaceLockEnabled(): Boolean {
        return sharedPreferences?.getBoolean(KEY_FACE_LOCK, false) ?: false
    }

    fun setFaceLockEnabled(enabled: Boolean): Boolean {
        val sp = sharedPreferences ?: return false
        sp.edit().putBoolean(KEY_FACE_LOCK, enabled).apply()
        return true
    }

    fun getEnrolledFaceName(): String? {
        return sharedPreferences?.getString(KEY_FACE_NAME, null)
    }

    fun setEnrolledFaceName(name: String): Boolean {
        val sp = sharedPreferences ?: return false
        sp.edit().putString(KEY_FACE_NAME, name.trim()).apply()
        return true
    }

    fun getLicenceKey(): String? {
        val sp = sharedPreferences ?: return null
        val key = sp.getString(KEY_LICENCE_KEY, null)
        return if (!key.isNullOrBlank()) key.trim() else null
    }

    fun setLicenceKey(key: String): Boolean {
        val sp = sharedPreferences ?: return false
        sp.edit().putString(KEY_LICENCE_KEY, key.trim()).apply()
        return true
    }

    fun clearLicenceKey(): Boolean {
        val sp = sharedPreferences ?: return false
        sp.edit().remove(KEY_LICENCE_KEY).apply()
        return true
    }

    fun getCachedRemoteConfig(): io.github.ryoaonetsuki.mira.backend.RemoteAppConfig? {
        val sp = sharedPreferences ?: return null
        val raw = sp.getString(KEY_CACHED_REMOTE_CONFIG, null) ?: return null
        return try {
            val obj = org.json.JSONObject(raw)
            io.github.ryoaonetsuki.mira.backend.RemoteAppConfig(
                killSwitch = obj.optBoolean("killSwitch", false),
                maintenanceMode = obj.optBoolean("maintenanceMode", false),
                maintenanceMessage = obj.optString("maintenanceMessage", ""),
                forcedUpdateVersion = obj.optString("forcedUpdateVersion", ""),
                forcedUpdateMessage = obj.optString("forcedUpdateMessage", ""),
                bannerText = obj.optString("bannerText", ""),
                licenceEnabled = obj.optBoolean("licenceEnabled", false)
            )
        } catch (_: Exception) {
            null
        }
    }

    fun setCachedRemoteConfig(config: io.github.ryoaonetsuki.mira.backend.RemoteAppConfig): Boolean {
        val sp = sharedPreferences ?: return false
        return try {
            val obj = org.json.JSONObject().apply {
                put("killSwitch", config.killSwitch)
                put("maintenanceMode", config.maintenanceMode)
                put("maintenanceMessage", config.maintenanceMessage)
                put("forcedUpdateVersion", config.forcedUpdateVersion)
                put("forcedUpdateMessage", config.forcedUpdateMessage)
                put("bannerText", config.bannerText)
                put("licenceEnabled", config.licenceEnabled)
            }
            sp.edit().putString(KEY_CACHED_REMOTE_CONFIG, obj.toString()).apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "SecurePreferences"
        private const val PREFS_FILENAME = "mira_encrypted_prefs"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_GROQ_API_KEY = "groq_api_key"
        private const val KEY_PIN_CODE = "pin_code"
        private const val KEY_TELEGRAM_CHAT_ID = "telegram_chat_id"
        private const val KEY_FACE_LOCK = "face_lock_enabled"
        private const val KEY_FACE_NAME = "enrolled_face_name"
        private const val KEY_LICENCE_KEY = "licence_key"
        private const val KEY_CACHED_REMOTE_CONFIG = "cached_remote_config"

        /**
         * Checks whether a raw Groq API key looks plausible.
         * Valid Groq keys start with 'gsk_' and have length >= 20.
         */
        fun isPlausibleGroqKey(raw: String): Boolean {
            val trimmed = raw.trim()
            return trimmed.startsWith("gsk_") && trimmed.length >= 20
        }
    }
}
