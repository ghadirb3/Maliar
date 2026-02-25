package com.persianai.assistant.utils

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.persianai.assistant.models.AIModel
import com.persianai.assistant.models.APIKey

/**
 * مدیریت تنظیمات برنامه
 */
class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val base64Flags = android.util.Base64.NO_WRAP

    companion object {
        private const val PREFS_NAME = "ai_assistant_prefs"
        private const val KEY_API_KEYS = "api_keys"
        private const val KEY_PROVISIONING_KEY = "provisioning_key_enc"
        private const val KEY_AUTO_PROVISIONING = "auto_provisioning"
        private const val KEY_SELECTED_MODEL = "selected_model"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
        private const val KEY_WORKING_MODE = "working_mode"
        private const val KEY_PROVIDER_PREF = "provider_pref"
        private const val KEY_WELCOME_COMPLETED = "welcome_completed"
        private const val KEY_TTS_ENABLED = "tts_enabled"
        private const val KEY_OFFLINE_MODEL_DOWNLOADED = "offline_model_downloaded"
        private const val KEY_PARENTAL_ENABLED = "parental_enabled"
        private const val KEY_PARENTAL_KEYWORDS = "parental_keywords"
        private const val KEY_RECORDING_MODE = "recording_mode"
        private const val KEY_ACCESSIBILITY_ENABLED = "accessibility_enabled"
        private const val KEY_PERSISTENT_STATUS_NOTIFICATION = "persistent_status_notification"
        private const val KEY_PERSISTENT_NOTIFICATION_ACTIONS = "persistent_notification_actions"
        private const val KEY_OFFLINE_PROMPT_SHOWN = "offline_prompt_shown"
        private const val KEY_OFFLINE_MODEL_TYPE = "offline_model_type"
        private const val KEY_START_DESTINATION = "start_destination"

        // کاربر بتواند مسیر پوشه مدل‌ها را دستی انتخاب کند
        private const val KEY_CUSTOM_VOSK_DIR = "custom_vosk_dir"
        private const val KEY_CUSTOM_COQUI_DIR = "custom_coqui_dir"
        private const val KEY_CUSTOM_LLM_DIR = "custom_llm_dir"

        private const val KEY_REMOTE_AI_CONFIG_URL = "remote_ai_config_url"
        private const val KEY_REMOTE_AI_CONFIG_JSON = "remote_ai_config_json"
        private const val KEY_REMOTE_AI_CONFIG_TS = "remote_ai_config_ts"
        private const val KEY_CALL_MODE = "call_mode"
        private const val KEY_DIRECT_CALL_ENABLED = "direct_call_enabled"
        
        const val DEFAULT_SYSTEM_PROMPT = """OUTPUT ONLY JSON. NO TEXT.

ACTIONS:
{"action":"open_app","app_name":"NAME"}
{"action":"send_telegram","phone":"NUMBER","message":"TEXT"}
{"action":"send_whatsapp","phone":"NUMBER","message":"TEXT"}
{"action":"send_sms","phone":"NUMBER","message":"TEXT"}

EXAMPLES:
"تلگرام باز کن" → {"action":"open_app","app_name":"تلگرام"}
"پیام بده سلام" → {"action":"send_telegram","phone":"UNKNOWN","message":"سلام"}
"به احمد بگو برگشتم" → {"action":"send_telegram","phone":"UNKNOWN","message":"برگشتم"}
"پیامک بفرست در راهم" → {"action":"send_sms","phone":"UNKNOWN","message":"در راهم"}
"واتساپ بفرست رسیدم" → {"action":"send_whatsapp","phone":"UNKNOWN","message":"رسیدم"}

If no phone number, use "UNKNOWN".
NEVER: "متاسفانه", "نمیتوانم"
JSON ONLY."""
    }

    fun saveAPIKeys(keys: List<APIKey>) {
        val json = gson.toJson(keys)
        prefs.edit().putString(KEY_API_KEYS, json).apply()
    }

    fun getAPIKeys(): List<APIKey> {
        val json = prefs.getString(KEY_API_KEYS, null) ?: return emptyList()
        val type = object : TypeToken<List<APIKey>>() {}.type
        return gson.fromJson(json, type)
    }

    fun hasAPIKeys(): Boolean {
        return prefs.contains(KEY_API_KEYS)
    }

    /**
     * Provisioning key (ذخیره به صورت Base64 برای جلوگیری از لاگ ساده)
     */
    fun saveProvisioningKey(key: String) {
        if (key.isBlank()) {
            prefs.edit().remove(KEY_PROVISIONING_KEY).apply()
        } else {
            val enc = android.util.Base64.encodeToString(key.trim().toByteArray(Charsets.UTF_8), base64Flags)
            prefs.edit().putString(KEY_PROVISIONING_KEY, enc).apply()
        }
    }

    fun hasStartDestinationSet(): Boolean = prefs.contains(KEY_START_DESTINATION)

    fun setStartDestination(dest: StartDestination) {
        prefs.edit().putString(KEY_START_DESTINATION, dest.name).apply()
    }

    fun getStartDestination(): StartDestination {
        val name = prefs.getString(KEY_START_DESTINATION, StartDestination.ASSISTANT.name)
        return try {
            StartDestination.valueOf(name ?: StartDestination.ASSISTANT.name)
        } catch (_: Exception) {
            StartDestination.ASSISTANT
        }
    }

    fun getProvisioningKey(): String? {
        val enc = prefs.getString(KEY_PROVISIONING_KEY, null) ?: return null
        return try {
            String(android.util.Base64.decode(enc, base64Flags), Charsets.UTF_8).trim().ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    fun setAutoProvisioning(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_PROVISIONING, enabled).apply()
    }

    fun isAutoProvisioningEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_PROVISIONING, true)
    }

    fun clearAPIKeys() {
        prefs.edit().remove(KEY_API_KEYS).apply()
    }

    fun saveSelectedModel(model: AIModel) {
        prefs.edit().putString(KEY_SELECTED_MODEL, model.modelId).apply()
    }

    fun getSelectedModel(): AIModel {
        val modelId = prefs.getString(KEY_SELECTED_MODEL, null)
        return if (modelId != null) {
            AIModel.fromModelId(modelId) ?: AIModel.getDefaultModel()
        } else {
            AIModel.getDefaultModel()
        }
    }

    fun saveSystemPrompt(prompt: String) {
        // غیرفعال - system prompt hardcoded است
    }

    fun getSystemPrompt(): String {
        // همیشه از DEFAULT_SYSTEM_PROMPT استفاده می‌کند
        return DEFAULT_SYSTEM_PROMPT
    }

    fun saveTemperature(temperature: Float) {
        prefs.edit().putFloat(KEY_TEMPERATURE, temperature).apply()
    }

    fun getTemperature(): Float {
        return prefs.getFloat(KEY_TEMPERATURE, 0.3f)  // کم برای دقت بالا در اجرای دستورات
    }

    fun setServiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply()
    }

    fun isServiceEnabled(): Boolean {
        return prefs.getBoolean(KEY_SERVICE_ENABLED, false)
    }

    fun setPersistentStatusNotificationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PERSISTENT_STATUS_NOTIFICATION, enabled).apply()
    }

    fun isPersistentStatusNotificationEnabled(): Boolean {
        // When user enables the service, showing a silent ongoing notification is the safe default.
        return prefs.getBoolean(KEY_PERSISTENT_STATUS_NOTIFICATION, true)
    }

    fun setPersistentNotificationActionsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PERSISTENT_NOTIFICATION_ACTIONS, enabled).apply()
    }

    fun isPersistentNotificationActionsEnabled(): Boolean {
        // Actions are optional; keep them off by default to avoid surprising behavior.
        return prefs.getBoolean(KEY_PERSISTENT_NOTIFICATION_ACTIONS, false)
    }

    // Working Mode
    enum class WorkingMode {
        ONLINE,    // فقط آنلاین با API
        OFFLINE,   // فقط آفلاین با مدل محلی
        HYBRID     // ترکیبی (پیشنهادی)
    }

    enum class RecordingMode {
        FAST,      // بلافاصله ارسال
        PRECISE    // نیاز به تأیید قبل از ارسال
    }

    fun setWorkingMode(mode: WorkingMode) {
        // حالت کاربر را ذخیره می‌کنیم ولی رفتار اپ را همیشه HYBRID نگه می‌داریم تا آنلاین‌اول/آفلاین‌fallback خودکار باشد
        prefs.edit().putString(KEY_WORKING_MODE, mode.name).apply()
    }

    fun getWorkingMode(): WorkingMode {
        // حالت واقعی را همیشه HYBRID برمی‌گردانیم تا بدون نیاز به تنظیم دستی، آنلاین‌اول و سپس آفلاین انجام شود
        return WorkingMode.HYBRID
    }

    fun setRecordingMode(mode: RecordingMode) {
        prefs.edit().putString(KEY_RECORDING_MODE, mode.name).apply()
    }

    fun getRecordingMode(): RecordingMode {
        val name = prefs.getString(KEY_RECORDING_MODE, RecordingMode.FAST.name)
        return try {
            RecordingMode.valueOf(name ?: RecordingMode.FAST.name)
        } catch (_: Exception) {
            RecordingMode.FAST
        }
    }

    enum class ProviderPreference {
        AUTO,
        SMART_ROUTE, // OpenRouter بدون نام بردن
        OPENAI_ONLY
    }

    enum class StartDestination {
        DASHBOARD,
        ASSISTANT
    }

    fun setProviderPreference(pref: ProviderPreference) {
        prefs.edit().putString(KEY_PROVIDER_PREF, pref.name).apply()
    }

    fun getProviderPreference(): ProviderPreference {
        val name = prefs.getString(KEY_PROVIDER_PREF, ProviderPreference.AUTO.name)
        return try {
            ProviderPreference.valueOf(name!!)
        } catch (_: Exception) {
            ProviderPreference.AUTO
        }
    }

    // مسیرهای انتخابی کاربر برای مدل‌ها
    fun setCustomVoskDir(path: String?) {
        if (path.isNullOrBlank()) {
            prefs.edit().remove(KEY_CUSTOM_VOSK_DIR).apply()
        } else {
            prefs.edit().putString(KEY_CUSTOM_VOSK_DIR, path.trim()).apply()
        }
    }

    fun getCustomVoskDir(): String? = prefs.getString(KEY_CUSTOM_VOSK_DIR, null)

    fun setCustomCoquiDir(path: String?) {
        if (path.isNullOrBlank()) {
            prefs.edit().remove(KEY_CUSTOM_COQUI_DIR).apply()
        } else {
            prefs.edit().putString(KEY_CUSTOM_COQUI_DIR, path.trim()).apply()
        }
    }

    fun getCustomCoquiDir(): String? = prefs.getString(KEY_CUSTOM_COQUI_DIR, null)

    fun setCustomLlmDir(path: String?) {
        if (path.isNullOrBlank()) {
            prefs.edit().remove(KEY_CUSTOM_LLM_DIR).apply()
        } else {
            prefs.edit().putString(KEY_CUSTOM_LLM_DIR, path.trim()).apply()
        }
    }

    fun getCustomLlmDir(): String? = prefs.getString(KEY_CUSTOM_LLM_DIR, null)

    fun saveRemoteAIConfigUrl(url: String?) {
        if (url.isNullOrBlank()) {
            prefs.edit().remove(KEY_REMOTE_AI_CONFIG_URL).apply()
        } else {
            prefs.edit().putString(KEY_REMOTE_AI_CONFIG_URL, url.trim()).apply()
        }
    }

    fun getRemoteAIConfigUrl(): String? = prefs.getString(KEY_REMOTE_AI_CONFIG_URL, null)

    fun saveRemoteAIConfigJson(json: String?, fetchedAtMs: Long = System.currentTimeMillis()) {
        if (json.isNullOrBlank()) {
            prefs.edit()
                .remove(KEY_REMOTE_AI_CONFIG_JSON)
                .remove(KEY_REMOTE_AI_CONFIG_TS)
                .apply()
        } else {
            prefs.edit()
                .putString(KEY_REMOTE_AI_CONFIG_JSON, json)
                .putLong(KEY_REMOTE_AI_CONFIG_TS, fetchedAtMs)
                .apply()
        }
    }

    fun getRemoteAIConfigJson(): String? = prefs.getString(KEY_REMOTE_AI_CONFIG_JSON, null)

    fun getRemoteAIConfigFetchedAtMs(): Long = prefs.getLong(KEY_REMOTE_AI_CONFIG_TS, 0L)

    fun setWelcomeCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_WELCOME_COMPLETED, completed).apply()
    }

    fun hasCompletedWelcome(): Boolean {
        return prefs.getBoolean(KEY_WELCOME_COMPLETED, false)
    }

    // TTS
    fun setTTSEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TTS_ENABLED, enabled).apply()
    }

    fun isTTSEnabled(): Boolean {
        return prefs.getBoolean(KEY_TTS_ENABLED, true) // پیش‌فرض فعال
    }

    // Offline Model
    fun setOfflineModelDownloaded(downloaded: Boolean) {
        prefs.edit().putBoolean(KEY_OFFLINE_MODEL_DOWNLOADED, downloaded).apply()
    }

    fun isOfflineModelDownloaded(): Boolean {
        return prefs.getBoolean(KEY_OFFLINE_MODEL_DOWNLOADED, false)
    }

    fun setOfflinePromptShown(shown: Boolean) {
        prefs.edit().putBoolean(KEY_OFFLINE_PROMPT_SHOWN, shown).apply()
    }

    fun isOfflinePromptShown(): Boolean {
        return prefs.getBoolean(KEY_OFFLINE_PROMPT_SHOWN, false)
    }

    fun isParentalControlEnabled(): Boolean {
        return prefs.getBoolean(KEY_PARENTAL_ENABLED, false)
    }

    fun setParentalControlEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PARENTAL_ENABLED, enabled).apply()
    }

    fun getBlockedKeywords(): List<String> {
        val json = prefs.getString(KEY_PARENTAL_KEYWORDS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setBlockedKeywords(keywords: List<String>) {
        val clean = keywords.map { it.trim() }.filter { it.isNotEmpty() }
        val json = gson.toJson(clean)
        prefs.edit().putString(KEY_PARENTAL_KEYWORDS, json).apply()
    }

    fun addBlockedKeyword(keyword: String) {
        val clean = keyword.trim()
        if (clean.isEmpty()) return
        val current = getBlockedKeywords().toMutableList()
        if (current.any { it.equals(clean, ignoreCase = true) }) return
        current.add(clean)
        setBlockedKeywords(current)
    }

    fun removeBlockedKeyword(keyword: String) {
        val clean = keyword.trim()
        if (clean.isEmpty()) return
        val current = getBlockedKeywords().toMutableList()
        val updated = current.filterNot { it.equals(clean, ignoreCase = true) }
        setBlockedKeywords(updated)
    }
    
    // Offline Model Type
    enum class OfflineModelType(val displayName: String, val size: String, val description: String) {
        BASIC("TinyLlama 1.1B", "≈410 MB", "سبک و مناسب اکثر گوشی‌ها"),
        LITE("Qwen2.5 0.5B", "≈550 MB", "کیفیت متوسط؛ نیازمند RAM بیشتر"),
        FULL("Qwen2.5 1.5B", "≈1.0 GB", "قدرت بالاتر؛ مناسب گوشی‌های با RAM بالاتر")
    }
    
    fun setOfflineModelType(type: OfflineModelType) {
        prefs.edit().putString("offline_model_type", type.name).apply()
    }
    
    fun getOfflineModelType(): OfflineModelType {
        val typeName = prefs.getString("offline_model_type", OfflineModelType.BASIC.name)
        return try {
            OfflineModelType.valueOf(typeName!!)
        } catch (e: Exception) {
            OfflineModelType.BASIC
        }
    }
    
    /**
     * دریافت مقدار Integer
     */
    fun getInt(key: String, defaultValue: Int): Int {
        return prefs.getInt(key, defaultValue)
    }
    
    /**
     * دریافت مقدار Double
     */
    fun getDouble(key: String, defaultValue: Double): Double {
        return prefs.getFloat(key, defaultValue.toFloat()).toDouble()
    }
    
    // Call Mode Settings
    enum class CallMode(val displayName: String, val description: String) {
        DIALER("شماره‌گیر گوشی", "استفاده از برنامه تماس خود گوشی (امن‌تر)"),
        DIRECT("تماس مستقیم", "تماس مستقیم بدون نیاز به تأیید (نیاز به مجوز)")
    }
    
    fun setCallMode(mode: CallMode) {
        prefs.edit().putString(KEY_CALL_MODE, mode.name).apply()
    }
    
    fun getCallMode(): CallMode {
        val modeName = prefs.getString(KEY_CALL_MODE, CallMode.DIALER.name)
        return try {
            CallMode.valueOf(modeName!!)
        } catch (e: Exception) {
            CallMode.DIALER
        }
    }
    
    // Direct Call Settings
    fun setDirectCallEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DIRECT_CALL_ENABLED, enabled).apply()
    }
    
    fun getDirectCallEnabled(): Boolean {
        return prefs.getBoolean(KEY_DIRECT_CALL_ENABLED, false) // پیش‌فرض: صفحه شماره‌گیر
    }
}
