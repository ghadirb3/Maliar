package com.persianai.assistant.config

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.persianai.assistant.models.AIModel
import com.persianai.assistant.models.AIProvider
import com.persianai.assistant.utils.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class RemoteAIConfigManager private constructor(private val context: Context) {
    private val prefs = PreferencesManager(context)
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "RemoteAIConfig"
        private const val DEFAULT_CONFIG_URL = "https://abrehamrahi.ir/o/public/eWygRXtp/"

        @Volatile
        private var INSTANCE: RemoteAIConfigManager? = null

        fun getInstance(context: Context): RemoteAIConfigManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RemoteAIConfigManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        private val FALLBACK_MODEL_PRIORITY = listOf(
            AIModel.GAPGPT_GPT_5_NANO,             // اولویت ۱: GAPGPT (LIARA 410 می‌دهد)
            AIModel.GAPGPT_GPT_4O_MINI,            // اولویت ۲: GAPGPT
            AIModel.LIARA_GPT_5_NANO,              // اولویت ۳: LIARA
            AIModel.LIARA_GEMINI_FLASH,            // اولویت ۴: LIARA
            AIModel.LIARA_GPT_4O_MINI,             // اولویت ۵: LIARA
            AIModel.GPT_4O_MINI                    // fallback عمومی OpenAI
        )
        private val FALLBACK_STT_PRIORITY = listOf("liara", "gapgpt", "openai")
        private val FALLBACK_TTS_PRIORITY = listOf("gapgpt", "haaniye", "android")
    }

    data class RemoteAIConfig(
        val messages: Messages? = null,
        val ai_text_models: List<ModelConfig>? = null,
        val speech_to_text_models: List<ModelConfig>? = null,
        val text_to_speech_models: List<ModelConfig>? = null,
        val special_messages: SpecialMessages? = null
    )

    data class Messages(
        val welcome: String? = null,
        val global_announcement: String? = null,
        val offline_message: String? = null
    )

    data class SpecialMessages(
        val enabled: Boolean? = null,
        val dates: Map<String, String>? = null,
        val default: String? = null
    )

    data class ModelConfig(
        val name: String,
        val provider: String,
        val base_url: String? = null,
        val priority: Int? = null,
        val enabled: Boolean = true
    )

    fun getEffectiveConfigUrl(): String =
        prefs.getRemoteAIConfigUrl()?.takeIf { it.isNotBlank() } ?: DEFAULT_CONFIG_URL

    suspend fun refreshAndCache(): RemoteAIConfig? = withContext(Dispatchers.IO) {
        val url = getEffectiveConfigUrl()
        try {
            val req = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful || body.isNullOrBlank()) {
                    Log.w(TAG, "remote config http ${resp.code}")
                    return@withContext null
                }
                prefs.saveRemoteAIConfigJson(body)
                gson.fromJson(body, RemoteAIConfig::class.java)
            }
        } catch (e: Exception) {
            Log.w(TAG, "remote config fetch failed: ${e.message}")
            null
        }
    }

    suspend fun fetchAndCacheConfig() {
        refreshAndCache()
    }

    fun loadCached(): RemoteAIConfig? {
        val json = prefs.getRemoteAIConfigJson() ?: return null
        return try {
            gson.fromJson(json, RemoteAIConfig::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "remote config parse failed: ${e.message}")
            null
        }
    }

    fun getModelPriority(): List<AIModel> {
        val remoteModels = loadCached()
            ?.ai_text_models
            ?.filter { it.enabled }
            ?.sortedBy { it.priority ?: 999 }
            .orEmpty()

        if (remoteModels.isEmpty()) return FALLBACK_MODEL_PRIORITY

        return remoteModels.mapNotNull { config ->
            AIModel.values().find { model ->
                model.modelId.equals(config.name, ignoreCase = true) &&
                    model.provider.name.equals(config.provider, ignoreCase = true)
            } ?: AIModel.values().find { model ->
                model.modelId.equals(config.name, ignoreCase = true)
            }
        }.ifEmpty { FALLBACK_MODEL_PRIORITY }
    }

    fun getTextModelPriority(): List<AIModel> = getModelPriority()

    fun getSTTPriority(): List<String> {
        val remoteSTT = loadCached()
            ?.speech_to_text_models
            ?.filter { it.enabled }
            ?.sortedBy { it.priority ?: 999 }
        if (remoteSTT.isNullOrEmpty()) return FALLBACK_STT_PRIORITY
        return remoteSTT.map { it.provider.lowercase() }.distinct()
    }

    fun getTTSPriority(): List<String> {
        val remoteTTS = loadCached()
            ?.text_to_speech_models
            ?.filter { it.enabled }
            ?.sortedBy { it.priority ?: 999 }
        if (remoteTTS.isNullOrEmpty()) return FALLBACK_TTS_PRIORITY
        return remoteTTS.map { it.provider.lowercase() }.distinct()
    }

    fun getSTTModelPriority(): List<AIModel> = listOf(
        AIModel.GAPGPT_WHISPER_1,             // اولویت ۱: GapGPT whisper-1 (Liara STT منسوخ شده)
        AIModel.GAPGPT_WHISPER               // اولویت ۲: GapGPT gapgpt/whisper-1
    )

    fun getTTSModelPriority(): List<AIModel> = listOf(
        AIModel.GAPGPT_GEMINI_PRO_TTS,          // اولویت ۱: gemini-2.5-pro-preview-tts (کیفیت بالا)
        AIModel.GAPGPT_GEMINI_FLASH_TTS,        // اولویت ۲: gemini-2.5-flash-preview-tts
        AIModel.GAPGPT_TTS_MINI                 // اولویت ۳: gpt-4o-mini-tts
    )

    fun getApiKeyForModel(model: AIModel): String? {
        return when (model.provider) {
            AIProvider.LIARA -> prefs.getString("liara_api_key", null)
            AIProvider.GAPGPT -> prefs.getString("gapgpt_api_key", null)
            AIProvider.OPENAI -> prefs.getString("openai_api_key", null)
            else -> prefs.getString("${model.provider.name.lowercase()}_api_key", null)
                ?: prefs.getString("default_api_key", null)
        }
    }
}
