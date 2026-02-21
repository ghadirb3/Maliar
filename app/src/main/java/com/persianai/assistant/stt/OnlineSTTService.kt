package com.persianai.assistant.stt

import android.content.Context
import android.util.Log
import com.persianai.assistant.integration.IviraIntegrationManager
import com.persianai.assistant.models.APIKey
import com.persianai.assistant.models.AIProvider
import com.persianai.assistant.utils.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * سرویس STT آنلاین با اولویت Liara و fallback به GapGPT
 */
class OnlineSTTService(private val context: Context) {
    
    private val TAG = "OnlineSTTService"
    
    // HttpClient با تنظیمات مشابه AIClient چت آنلاین
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)  // مانند AIClient
        .readTimeout(120, TimeUnit.SECONDS)     // مانند AIClient
        .writeTimeout(120, TimeUnit.SECONDS)    // مانند AIClient
        .retryOnConnectionFailure(true)         // مانند AIClient
        .build()
    
    private val iviraManager = IviraIntegrationManager(context)
    private val prefsManager = PreferencesManager(context)
    
    /**
     * تبدیل گفتار به متن با معماری مشابه AIClient چت آنلاین
     * اولویت: Liara → GapGPT → OpenAI با مدیریت کامل خطا
     */
    suspend fun transcribeAudio(audioFile: File): STTResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "🎤 Starting STT with AIClient-like architecture")
        
        // دریافت API Keys از Ivira مانند AIClient
        val apiKeys = getSTTApiKeys()
        if (apiKeys.isEmpty()) {
            Log.e(TAG, "❌ No STT API keys found")
            return@withContext STTResult.error("هیچ API key برای STT یافت نشد")
        }
        
        // اولویت‌بندی دقیق مانند AIClient
        val sttProviders = prioritizeProviders(apiKeys)
        
        // تلاش با هر provider با مدیریت خطا مانند AIClient
        for (provider in sttProviders) {
            try {
                Log.d(TAG, "🔄 Trying STT with provider: ${provider.provider}")
                val result = transcribeWithProvider(audioFile, provider)
                if (result.isSuccess) {
                    Log.d(TAG, "✅ STT success with ${provider.provider}: ${result.text}")
                    return@withContext result
                } else {
                    Log.w(TAG, "⚠️ STT failed with ${provider.provider}: ${result.error}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "❌ STT exception with ${provider.provider}: ${e.message}")
            }
        }
        
        Log.e(TAG, "❌ All STT providers failed")
        STTResult.error("تمام سرویس‌های STT در دسترس نیستند")
    }
    
    /**
     * دریافت API Keys برای STT از PreferencesManager (همان سیستم چت آنلاین)
     */
    private fun getSTTApiKeys(): List<APIKey> {
        return try {
            // استفاده از PreferencesManager که کلیدها را از لینک abrehamrahi بارگیری می‌کند
            val apiKeys = prefsManager.getAPIKeys()
            
            // فیلتر فقط provider های STT معتبر
            val sttKeys = apiKeys.filter { it.provider in setOf(
                AIProvider.LIARA,
                AIProvider.GAPGPT,
                AIProvider.OPENAI
            ) }
            
            Log.d(TAG, "✅ Found ${sttKeys.size} STT API keys: ${sttKeys.map { it.provider }}")
            return sttKeys
            
        } catch (e: Exception) {
            Log.e(TAG, "Error getting STT API keys", e)
            emptyList()
        }
    }
    
    /**
     * اولویت‌بندی providers مانند AIClient
     */
    private fun prioritizeProviders(apiKeys: List<APIKey>): List<APIKey> {
        val priority = mutableListOf<APIKey>()
        
        // اولویت اول: Liara (مانند چت آنلاین)
        apiKeys.filter { it.provider == AIProvider.LIARA }.let { keys ->
            if (keys.isNotEmpty()) {
                priority.addAll(keys)
            }
        }
        
        // اولویت دوم: GapGPT (مانند چت آنلاین)
        apiKeys.filter { it.provider == AIProvider.GAPGPT }.let { keys ->
            if (keys.isNotEmpty()) {
                priority.addAll(keys)
            }
        }
        
        // اولویت سوم: OpenAI (اگر برای Whisper استفاده شود)
        apiKeys.filter { it.provider == AIProvider.OPENAI }.let { keys ->
            if (keys.isNotEmpty()) {
                priority.addAll(keys)
            }
        }
        
        return priority
    }
    
    /**
     * STT با provider مشخص
     */
    private suspend fun transcribeWithProvider(audioFile: File, apiKey: APIKey): STTResult {
        return when (apiKey.provider) {
            AIProvider.LIARA -> transcribeWithLiara(audioFile, apiKey.key)
            AIProvider.GAPGPT -> transcribeWithGapGPT(audioFile, apiKey.key)
            AIProvider.OPENAI -> transcribeWithOpenAI(audioFile, apiKey.key)
            else -> STTResult.error("Provider ${apiKey.provider} not supported for STT")
        }
    }
    
    /**
     * STT با استفاده از Liara (google/gemini-2.0-flash-001)
     */
    private suspend fun transcribeWithLiara(audioFile: File, apiKey: String): STTResult {
        
        // تبدیل فایل صوتی به base64
        val audioBase64 = audioFile.readBytes().let { 
            android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)
        }
        
        val requestBody = JSONObject().apply {
            put("model", "google/gemini-2.0-flash-001")
            put("audio", audioBase64)
            put("language", "fa")
        }.toString().toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url("https://ai.liara.ir/api/69467b6ba99a2016cac892e1/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()
        
        return try {
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            
            if (response.isSuccessful) {
                val json = JSONObject(responseBody)
                val text = json.optString("text", "")
                if (text.isNotBlank()) {
                    STTResult.success(text)
                } else {
                    STTResult.error("Empty response from Liara")
                }
            } else {
                STTResult.error("Liara API error: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Liara STT error", e)
            STTResult.error("Liara STT failed: ${e.message}")
        }
    }
    
    /**
     * STT با استفاده از GapGPT (gapgpt/whisper-1)
     */
    private suspend fun transcribeWithGapGPT(audioFile: File, apiKey: String): STTResult {
        
        // تبدیل فایل صوتی به multipart form
        val audioBytes = audioFile.readBytes()
        val audioRequestBody = audioBytes
            .toRequestBody("audio/wav".toMediaType(), 0, audioBytes.size)
        
        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", "gapgpt/whisper-1")
            .addFormDataPart("language", "fa")
            .addFormDataPart("file", audioFile.name, audioRequestBody)
            .build()
        
        val request = Request.Builder()
            .url("https://api.gapgpt.app/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(multipartBody)
            .build()
        
        return try {
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            
            if (response.isSuccessful) {
                val json = JSONObject(responseBody)
                val text = json.optString("text", "")
                if (text.isNotBlank()) {
                    STTResult.success(text)
                } else {
                    STTResult.error("Empty response from GapGPT")
                }
            } else {
                STTResult.error("GapGPT API error: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "GapGPT STT error", e)
            STTResult.error("GapGPT STT failed: ${e.message}")
        }
    }
    
    /**
     * STT با OpenAI Whisper (fallback)
     */
    private suspend fun transcribeWithOpenAI(audioFile: File, apiKey: String): STTResult {
        val audioBytes = audioFile.readBytes()
        val audioRequestBody = audioBytes
            .toRequestBody("audio/wav".toMediaType(), 0, audioBytes.size)
        
        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("language", "fa")
            .addFormDataPart("file", audioFile.name, audioRequestBody)
            .build()
        
        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(multipartBody)
            .build()
        
        return try {
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            
            if (response.isSuccessful) {
                val json = JSONObject(responseBody)
                val text = json.optString("text", "")
                if (text.isNotBlank()) {
                    STTResult.success(text)
                } else {
                    STTResult.error("Empty response from OpenAI")
                }
            } else {
                STTResult.error("OpenAI API error: ${response.code} - $responseBody")
            }
        } catch (e: Exception) {
            Log.e(TAG, "OpenAI STT error", e)
            STTResult.error("OpenAI STT failed: ${e.message}")
        }
    }
}

/**
 * نتیجه STT
 */
data class STTResult(
    val isSuccess: Boolean,
    val text: String,
    val error: String? = null
) {
    companion object {
        fun success(text: String, error: String? = null) = STTResult(
            isSuccess = text.isNotBlank(),
            text = text,
            error = error
        )
        
        fun error(message: String) = STTResult(
            isSuccess = false,
            text = "",
            error = message
        )
    }
}
