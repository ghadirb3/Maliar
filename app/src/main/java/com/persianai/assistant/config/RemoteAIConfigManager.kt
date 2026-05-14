package com.persianai.assistant.config

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.persianai.assistant.models.AIModel
import com.persianai.assistant.models.AIProvider
import com.persianai.assistant.utils.PreferencesManager
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class RemoteAIConfigManager private constructor(private val context: Context) {
    private val prefs = PreferencesManager(context)
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private const val DEFAULT_CONFIG_URL = "https://abrehamrahi.ir/o/public/eWygRXtp/"

    companion object {
        @Volatile
        private var INSTANCE: RemoteAIConfigManager? = null

        fun getInstance(context: Context): RemoteAIConfigManager {
            return INSTANCE ?: synchronized(this) {
                val instance = RemoteAIConfigManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }

    // اولویت‌های اصلاح شده بر اساس AIModel.kt
    fun getTextModelPriority(): List<AIModel> = listOf(
        AIModel.LIARA_GPT_5_NANO,
        AIModel.GAPGPT_GPT5_NANO,
        AIModel.GPT_4O_MINI,
        AIModel.IVIRA_GPT5_NANO
    )

    fun getSTTModelPriority(): List<AIModel> = listOf(
        AIModel.LIARA_GEMINI_FLASH,
        AIModel.GAPGPT_WHISPER,
        AIModel.GPT_4O_MINI
    )

    fun getTTSModelPriority(): List<AIModel> = listOf(
        AIModel.GAPGPT_TTS_MINI,
        AIModel.LIARA_GPT_5_NANO
    )

    suspend fun fetchAndCacheConfig() {
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(DEFAULT_CONFIG_URL).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            prefs.saveRemoteAIConfigJson(body)
                            Log.d("RemoteConfig", "Config updated")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("RemoteConfig", "Error: ${e.message}")
            }
        }
    }

    fun getApiKeyForModel(model: AIModel): String? {
        // ابتدا سعی میکند از تنظیمات دانلود شده بخواند، در غیر این صورت از کلیدهای پیش فرض
        val configJson = prefs.getRemoteAIConfigJson()
        return if (configJson != null) {
            // منطق استخراج کلید از JSON (بر اساس ساختار سرور شما)
            prefs.getString("${model.provider.name.lowercase()}_api_key", null)
        } else {
            when (model.provider) {
                AIProvider.LIARA -> prefs.getString("liara_api_key", null)
                AIProvider.GAPGPT -> prefs.getString("gapgpt_api_key", null)
                else -> prefs.getString("default_api_key", null)
            }
        }
    }
}
