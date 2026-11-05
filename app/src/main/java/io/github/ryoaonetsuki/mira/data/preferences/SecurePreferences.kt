package io.github.ryoaonetsuki.mira.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecurePreferences(context: Context) {

    private val sharedPreferences: SharedPreferences by lazy {
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
            // Fallback for devices with keystore issues
            context.getSharedPreferences("mira_secure_prefs_fallback", Context.MODE_PRIVATE)
        }
    }

    fun getGeminiApiKey(): String? {
        val key = sharedPreferences.getString(KEY_GEMINI_API_KEY, null)
        return if (!key.isNullOrBlank()) key.trim() else null
    }

    fun setGeminiApiKey(key: String) {
        sharedPreferences.edit().putString(KEY_GEMINI_API_KEY, key.trim()).apply()
    }

    fun clearGeminiApiKey() {
        sharedPreferences.edit().remove(KEY_GEMINI_API_KEY).apply()
    }

    fun getDeepgramKey(): String? {
        val key = sharedPreferences.getString(KEY_DEEPGRAM_KEY, null)
        return if (!key.isNullOrBlank()) key.trim() else null
    }

    fun setDeepgramKey(key: String) {
        sharedPreferences.edit().putString(KEY_DEEPGRAM_KEY, key.trim()).apply()
    }

    fun getConnectorToken(connectorId: String): String? {
        return sharedPreferences.getString("token_$connectorId", null)
    }

    fun setConnectorToken(connectorId: String, token: String) {
        sharedPreferences.edit().putString("token_$connectorId", token.trim()).apply()
    }

    fun removeConnectorToken(connectorId: String) {
        sharedPreferences.edit().remove("token_$connectorId").apply()
    }

    fun getPinCode(): String? {
        return sharedPreferences.getString(KEY_PIN_CODE, null)
    }

    fun setPinCode(pin: String) {
        sharedPreferences.edit().putString(KEY_PIN_CODE, pin.trim()).apply()
    }

    fun isFaceLockEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_FACE_LOCK, false)
    }

    fun setFaceLockEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_FACE_LOCK, enabled).apply()
    }

    fun getEnrolledFaceName(): String? {
        return sharedPreferences.getString(KEY_FACE_NAME, null)
    }

    fun setEnrolledFaceName(name: String) {
        sharedPreferences.edit().putString(KEY_FACE_NAME, name.trim()).apply()
    }

    companion object {
        private const val PREFS_FILENAME = "mira_encrypted_prefs"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_DEEPGRAM_KEY = "deepgram_key"
        private const val KEY_PIN_CODE = "pin_code"
        private const val KEY_FACE_LOCK = "face_lock_enabled"
        private const val KEY_FACE_NAME = "enrolled_face_name"
    }
}
