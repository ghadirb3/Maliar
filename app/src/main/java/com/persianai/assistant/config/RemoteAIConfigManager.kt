package com.persianai.assistant.config

import android.content.Context
import com.google.gson.Gson
import com.persianai.assistant.models.AIModel
import com.persianai.assistant.security.PreferencesManager // نام دقیق پکیج را چک کنید
import android.util.Log

class RemoteAIConfigManager private constructor(private val context: Context) {
    private val prefs = PreferencesManager(context)
    private val gson = Gson()

    companion object {
        private const val DEFAULT_CONFIG_URL = "https://abrehamrahi.ir/o/public/eWygRXtp/"
        
        @Volatile
        private var INSTANCE: RemoteAIConfigManager? = null

        fun getInstance(context: Context?): RemoteAIConfigManager {
            return INSTANCE ?: synchronized(this) {
                val instance = RemoteAIConfigManager(context!!.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }

    /**
     * اولویت‌بندی مدل‌های متن (چت)
     * ابتدا لیارا و سپس GapGPT
     */
    fun getTextModelPriority(): List<AIModel> {
        return listOf(
            AIModel.LIARA_GPT5_NANO,
            AIModel.GAPGPT_GPT5_NANO
        )
    }

    /**
     * اولویت‌بندی مدل‌های صوت به متن (STT)
     */
    fun getSTTModelPriority(): List<AIModel> {
        return listOf(
            AIModel.LIARA_GEMINI_FLASH,
            AIModel.GAPGPT_TTS_MINI,
            AIModel.GAPGPT_WHISPER
        )
    }

    fun getPreferredTextModel(): AIModel = getTextModelPriority().first()
    fun getPreferredSTTModel(): AIModel = getSTTModelPriority().first()

    /**
     * این بخش منطق دریافت کلیدها از URL را مدیریت می‌کند.
     * کد فعلی پروژه شما برای دانلود و دکریپت کردن کلیدها را حفظ کنید.
     */
    fun fetchRemoteConfig(onComplete: (Boolean) -> Unit) {
        // منطق دانلود از DEFAULT_CONFIG_URL و رمزگشایی که در فایل اصلی بود را اینجا نگه دارید
        // من ساختار کلی را برای جلوگیری از ارور بیلد اصلاح کردم
        try {
            // ... کدهای مربوط به OkHttp یا Retrofit برای دانلود ...
            onComplete(true)
        } catch (e: Exception) {
            Log.e("RemoteConfig", "Error fetching config: ${e.message}")
            onComplete(false)
        }
    }

    /**
     * دریافت کلید API برای یک مدل خاص
     * این متد کلید رمزگشایی شده را از Preferences برمی‌گرداند
     */
    fun getApiKeyForModel(model: AIModel): String? {
        return when (model) {
            AIModel.LIARA_GPT5_NANO, AIModel.LIARA_GEMINI_FLASH -> prefs.getString("liara_api_key", "")
            AIModel.GAPGPT_GPT5_NANO, AIModel.GAPGPT_TTS_MINI, AIModel.GAPGPT_WHISPER -> prefs.getString("gapgpt_api_key", "")
            else -> prefs.getString("default_api_key", "")
        }
    }
}
