package com.persianai.assistant.config

import android.content.Context
import android.content.SharedPreferences

/**
 * سیستم Feature Flag برای مدیریت قابلیت‌های برنامه
 * با قابلیت غیرفعال‌سازی بخش‌های خاص (مانند تماس، STT، TTS)
 */
class FeatureFlags(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("feature_flags", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_CALL_ENABLED = "feature_call_enabled"
        private const val KEY_STT_ENABLED = "feature_stt_enabled"
        private const val KEY_TTS_ENABLED = "feature_tts_enabled"
        private const val KEY_NAVIGATION_ENABLED = "feature_navigation_enabled"
        private const val KEY_MUSIC_ENABLED = "feature_music_enabled"
        private const val KEY_WEATHER_ENABLED = "feature_weather_enabled"
        private const val KEY_AI_CHAT_ENABLED = "feature_ai_chat_enabled"
        private const val KEY_FINANCE_ENABLED = "feature_finance_enabled"
        private const val KEY_REMINDERS_ENABLED = "feature_reminders_enabled"
        private const val KEY_BACKUP_ENABLED = "feature_backup_enabled"
        private const val KEY_INTERNATIONAL_CALL_ENABLED = "feature_international_call_enabled"

        // Static feature flags - controlled by build config or remote
        const val FEATURE_CSV_EXPORT = true
        const val FEATURE_CSV_IMPORT = true
        const val FEATURE_GOOGLE_DRIVE_BACKUP = true
        const val FEATURE_LOCAL_BACKUP = true
        const val FEATURE_MPANDROID_CHART = true
        const val FEATURE_REMINDER_AI_NATURAL_LANGUAGE = true
    }

    // ==================== بخش تماس ====================
    
    fun isCallEnabled(): Boolean = prefs.getBoolean(KEY_CALL_ENABLED, false) // پیش‌فرض غیرفعال
    
    fun setCallEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CALL_ENABLED, enabled).apply()
    }
    
    fun isInternationalCallEnabled(): Boolean = prefs.getBoolean(KEY_INTERNATIONAL_CALL_ENABLED, false)
    
    fun setInternationalCallEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_INTERNATIONAL_CALL_ENABLED, enabled).apply()
    }

    // ==================== بخش STT (گفتار به متن) ====================
    
    fun isSTTEnabled(): Boolean = prefs.getBoolean(KEY_STT_ENABLED, false) // پیش‌فرض غیرفعال
    
    fun setSTTEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STT_ENABLED, enabled).apply()
    }

    // ==================== بخش TTS (متن به گفتار) ====================
    
    fun isTTSEnabled(): Boolean = prefs.getBoolean(KEY_TTS_ENABLED, false) // پیش‌فرض غیرفعال
    
    fun setTTSEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TTS_ENABLED, enabled).apply()
    }

    // ==================== سایر بخش‌ها ====================
    
    fun isNavigationEnabled(): Boolean = prefs.getBoolean(KEY_NAVIGATION_ENABLED, true)
    fun setNavigationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NAVIGATION_ENABLED, enabled).apply()
    }
    
    fun isMusicEnabled(): Boolean = prefs.getBoolean(KEY_MUSIC_ENABLED, true)
    fun setMusicEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MUSIC_ENABLED, enabled).apply()
    }
    
    fun isWeatherEnabled(): Boolean = prefs.getBoolean(KEY_WEATHER_ENABLED, true)
    fun setWeatherEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WEATHER_ENABLED, enabled).apply()
    }
    
    fun isAIChatEnabled(): Boolean = prefs.getBoolean(KEY_AI_CHAT_ENABLED, true)
    fun setAIChatEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AI_CHAT_ENABLED, enabled).apply()
    }
    
    fun isFinanceEnabled(): Boolean = prefs.getBoolean(KEY_FINANCE_ENABLED, true)
    fun setFinanceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FINANCE_ENABLED, enabled).apply()
    }
    
    fun isRemindersEnabled(): Boolean = prefs.getBoolean(KEY_REMINDERS_ENABLED, true)
    fun setRemindersEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REMINDERS_ENABLED, enabled).apply()
    }
    
    fun isBackupEnabled(): Boolean = prefs.getBoolean(KEY_BACKUP_ENABLED, true)
    fun setBackupEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BACKUP_ENABLED, enabled).apply()
    }

    // ==================== متدهای کمکی ====================
    
    /**
     * آیا حداقل یکی از ویژگی‌های صوتی فعال است؟
     */
    fun isVoiceFeatureEnabled(): Boolean {
        return isCallEnabled() || isSTTEnabled() || isTTSEnabled()
    }

    /**
     * بازنشانی همه feature flags به حالت پیش‌فرض
     */
    fun resetAll() {
        prefs.edit().clear().apply()
    }

    /**
     * دریافت همه تنظیمات به صورت Map
     */
    fun getAllFlags(): Map<String, Boolean> {
        return mapOf(
            "call" to isCallEnabled(),
            "stt" to isSTTEnabled(),
            "tts" to isTTSEnabled(),
            "navigation" to isNavigationEnabled(),
            "music" to isMusicEnabled(),
            "weather" to isWeatherEnabled(),
            "ai_chat" to isAIChatEnabled(),
            "finance" to isFinanceEnabled(),
            "reminders" to isRemindersEnabled(),
            "backup" to isBackupEnabled()
        )
    }
}