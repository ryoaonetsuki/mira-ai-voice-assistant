package io.github.ryoaonetsuki.mira.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore by preferencesDataStore(name = "mira_user_prefs")

class UserPreferences(private val context: Context) {

    val userNameFlow: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_USER_NAME] ?: "User"
    }

    val userGenderFlow: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_USER_GENDER] ?: "Not specified"
    }

    val userPhoneFlow: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_USER_PHONE] ?: ""
    }

    val personaFlow: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_PERSONA] ?: "Mira"
    }

    val assistantNameFlow: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_ASSISTANT_NAME] ?: "Mira"
    }

    val liveModelFlow: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_LIVE_MODEL] ?: "models/gemini-3.8-live"
    }

    val voiceNameFlow: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_VOICE_NAME] ?: "Aoede"
    }

    val languageFlow: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_LANGUAGE] ?: "Bangla (Bengali)"
    }

    val customInstructionsFlow: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_CUSTOM_INSTRUCTIONS] ?: ""
    }

    val secretModeFlow: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_SECRET_MODE] ?: "Friendly"
    }

    val isAdultConfirmedFlow: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_ADULT_CONFIRMED] ?: false
    }

    val themePaletteFlow: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_THEME_PALETTE] ?: "Midnight"
    }

    val orbStyleFlow: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_ORB_STYLE] ?: "Mira Neon"
    }

    val fastListeningFlow: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_FAST_LISTENING] ?: true
    }

    val echoGuardFlow: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_ECHO_GUARD] ?: true
    }

    val startOnBootFlow: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_START_ON_BOOT] ?: false
    }

    val earpieceModeFlow: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_EARPIECE_MODE] ?: false
    }

    val drivingModeFlow: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_DRIVING_MODE] ?: false
    }

    val sleepTimeoutFlow: Flow<Int> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_SLEEP_TIMEOUT] ?: 5
    }

    val autoMemoryFlow: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_AUTO_MEMORY] ?: true
    }

    val batterySaverFlow: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_BATTERY_SAVER] ?: false
    }

    val touchGuardFlow: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_TOUCH_GUARD] ?: false
    }

    val musicAppFlow: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_MUSIC_APP] ?: "Spotify"
    }

    val favoriteSongFlow: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_FAVORITE_SONG] ?: "Starboy"
    }

    val whatsappAutoReplyFlow: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_WHATSAPP_AUTO_REPLY] ?: false
    }

    val autoReplyTemplateFlow: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_AUTO_REPLY_TEMPLATE] ?: "Hi! I am away right now. Mira AI will notify me."
    }

    val userHandleFlow: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_USER_HANDLE] ?: "@mira_creator"
    }

    val captionVoiceFlow: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_CAPTION_VOICE] ?: "Witty, bold and tech-savvy"
    }

    val dailyStoryFlow: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_DAILY_STORY] ?: true
    }

    val webSearchEnabledFlow: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_WEB_SEARCH_ENABLED] ?: true
    }

    val deepThinkingEnabledFlow: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_DEEP_THINKING_ENABLED] ?: false
    }

    val alternativeVoiceEnabledFlow: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_ALTERNATIVE_VOICE_ENABLED] ?: false
    }

    val voiceRoutingFlow: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_VOICE_ROUTING] ?: "RELAY"
    }

    private suspend fun emitDefaults(throwable: Throwable) {
        if (throwable !is IOException) throw throwable
    }

    suspend fun setUserName(name: String) = context.dataStore.edit { it[KEY_USER_NAME] = name }
    suspend fun setUserGender(gender: String) = context.dataStore.edit { it[KEY_USER_GENDER] = gender }
    suspend fun setUserPhone(phone: String) = context.dataStore.edit { it[KEY_USER_PHONE] = phone }
    suspend fun setPersona(persona: String) = context.dataStore.edit { it[KEY_PERSONA] = persona }
    suspend fun setAssistantName(name: String) = context.dataStore.edit { it[KEY_ASSISTANT_NAME] = name }
    suspend fun setLiveModel(model: String) = context.dataStore.edit { it[KEY_LIVE_MODEL] = model }
    suspend fun setVoiceName(voice: String) = context.dataStore.edit { it[KEY_VOICE_NAME] = voice }
    suspend fun setLanguage(lang: String) = context.dataStore.edit { it[KEY_LANGUAGE] = lang }
    suspend fun setCustomInstructions(text: String) = context.dataStore.edit { it[KEY_CUSTOM_INSTRUCTIONS] = text }
    suspend fun setSecretMode(mode: String) = context.dataStore.edit { it[KEY_SECRET_MODE] = mode }
    suspend fun setAdultConfirmed(confirmed: Boolean) = context.dataStore.edit { it[KEY_ADULT_CONFIRMED] = confirmed }
    suspend fun setThemePalette(palette: String) = context.dataStore.edit { it[KEY_THEME_PALETTE] = palette }
    suspend fun setOrbStyle(style: String) = context.dataStore.edit { it[KEY_ORB_STYLE] = style }
    suspend fun setFastListening(enabled: Boolean) = context.dataStore.edit { it[KEY_FAST_LISTENING] = enabled }
    suspend fun setEchoGuard(enabled: Boolean) = context.dataStore.edit { it[KEY_ECHO_GUARD] = enabled }
    suspend fun setStartOnBoot(enabled: Boolean) = context.dataStore.edit { it[KEY_START_ON_BOOT] = enabled }
    suspend fun setEarpieceMode(enabled: Boolean) = context.dataStore.edit { it[KEY_EARPIECE_MODE] = enabled }
    suspend fun setDrivingMode(enabled: Boolean) = context.dataStore.edit { it[KEY_DRIVING_MODE] = enabled }
    suspend fun setSleepTimeout(minutes: Int) = context.dataStore.edit { it[KEY_SLEEP_TIMEOUT] = minutes }
    suspend fun setAutoMemory(enabled: Boolean) = context.dataStore.edit { it[KEY_AUTO_MEMORY] = enabled }
    suspend fun setBatterySaver(enabled: Boolean) = context.dataStore.edit { it[KEY_BATTERY_SAVER] = enabled }
    suspend fun setTouchGuard(enabled: Boolean) = context.dataStore.edit { it[KEY_TOUCH_GUARD] = enabled }
    suspend fun setMusicApp(app: String) = context.dataStore.edit { it[KEY_MUSIC_APP] = app }
    suspend fun setFavoriteSong(song: String) = context.dataStore.edit { it[KEY_FAVORITE_SONG] = song }
    suspend fun setWhatsappAutoReply(enabled: Boolean) = context.dataStore.edit { it[KEY_WHATSAPP_AUTO_REPLY] = enabled }
    suspend fun setAutoReplyTemplate(template: String) = context.dataStore.edit { it[KEY_AUTO_REPLY_TEMPLATE] = template }
    suspend fun setUserHandle(handle: String) = context.dataStore.edit { it[KEY_USER_HANDLE] = handle }
    suspend fun setCaptionVoice(voice: String) = context.dataStore.edit { it[KEY_CAPTION_VOICE] = voice }
    suspend fun setDailyStory(enabled: Boolean) = context.dataStore.edit { it[KEY_DAILY_STORY] = enabled }
    suspend fun setWebSearchEnabled(enabled: Boolean) = context.dataStore.edit { it[KEY_WEB_SEARCH_ENABLED] = enabled }
    suspend fun setDeepThinkingEnabled(enabled: Boolean) = context.dataStore.edit { it[KEY_DEEP_THINKING_ENABLED] = enabled }
    suspend fun setAlternativeVoiceEnabled(enabled: Boolean) = context.dataStore.edit { it[KEY_ALTERNATIVE_VOICE_ENABLED] = enabled }
    suspend fun setVoiceRouting(routing: String) = context.dataStore.edit { it[KEY_VOICE_ROUTING] = routing }

    companion object {
        private val KEY_USER_NAME = stringPreferencesKey("user_name")
        private val KEY_USER_GENDER = stringPreferencesKey("user_gender")
        private val KEY_USER_PHONE = stringPreferencesKey("user_phone")
        private val KEY_PERSONA = stringPreferencesKey("persona")
        private val KEY_ASSISTANT_NAME = stringPreferencesKey("assistant_name")
        private val KEY_LIVE_MODEL = stringPreferencesKey("live_model")
        private val KEY_VOICE_NAME = stringPreferencesKey("voice_name")
        private val KEY_LANGUAGE = stringPreferencesKey("language")
        private val KEY_CUSTOM_INSTRUCTIONS = stringPreferencesKey("custom_instructions")
        private val KEY_SECRET_MODE = stringPreferencesKey("secret_mode")
        private val KEY_ADULT_CONFIRMED = booleanPreferencesKey("adult_confirmed")
        private val KEY_THEME_PALETTE = stringPreferencesKey("theme_palette")
        private val KEY_ORB_STYLE = stringPreferencesKey("orb_style")
        private val KEY_FAST_LISTENING = booleanPreferencesKey("fast_listening")
        private val KEY_ECHO_GUARD = booleanPreferencesKey("echo_guard")
        private val KEY_START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        private val KEY_EARPIECE_MODE = booleanPreferencesKey("earpiece_mode")
        private val KEY_DRIVING_MODE = booleanPreferencesKey("driving_mode")
        private val KEY_SLEEP_TIMEOUT = intPreferencesKey("sleep_timeout")
        private val KEY_AUTO_MEMORY = booleanPreferencesKey("auto_memory")
        private val KEY_BATTERY_SAVER = booleanPreferencesKey("battery_saver")
        private val KEY_TOUCH_GUARD = booleanPreferencesKey("touch_guard")
        private val KEY_MUSIC_APP = stringPreferencesKey("music_app")
        private val KEY_FAVORITE_SONG = stringPreferencesKey("favorite_song")
        private val KEY_WHATSAPP_AUTO_REPLY = booleanPreferencesKey("whatsapp_auto_reply")
        private val KEY_AUTO_REPLY_TEMPLATE = stringPreferencesKey("auto_reply_template")
        private val KEY_USER_HANDLE = stringPreferencesKey("user_handle")
        private val KEY_CAPTION_VOICE = stringPreferencesKey("caption_voice")
        private val KEY_DAILY_STORY = booleanPreferencesKey("daily_story")
        private val KEY_WEB_SEARCH_ENABLED = booleanPreferencesKey("web_search_enabled")
        private val KEY_DEEP_THINKING_ENABLED = booleanPreferencesKey("deep_thinking_enabled")
        private val KEY_ALTERNATIVE_VOICE_ENABLED = booleanPreferencesKey("alternative_voice_enabled")
        private val KEY_VOICE_ROUTING = stringPreferencesKey("voice_routing")
    }
}
