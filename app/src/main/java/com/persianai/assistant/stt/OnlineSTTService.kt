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
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * سرویس STT آنلاین با اولویت Liara و fallback به GapGPT
 */
class OnlineSTTService(private val context: Context) {
    
    private val TAG = "OnlineSTTService"
    
    // HttpClient با تنظیمات خیلی کوتاه برای جلوگیری از انتظار طولانی
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)    // کاهش به ۵ ثانیه
        .readTimeout(10, TimeUnit.SECONDS)       // کاهش به ۱۰ ثانیه
        .writeTimeout(10, TimeUnit.SECONDS)      // کاهش به ۱۰ ثانیه
        .retryOnConnectionFailure(true)
        .build()

    private val gapgptHttpClient = httpClient.newBuilder()
        .callTimeout(60, TimeUnit.SECONDS)   // کل timeout درخواست
        .readTimeout(60, TimeUnit.SECONDS)   // برای خواندن بدنه بزرگ
        .writeTimeout(60, TimeUnit.SECONDS)  // برای آپلود فایل
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()
    
    private var gapgptProbed = false
    
    private val iviraManager = IviraIntegrationManager(context)
    private val prefsManager = PreferencesManager(context)
    
    /**
     * تبدیل گفتار به متن با معماری مشابه AIClient چت آنلاین
     * اولویت: GapGPT → Liara → OpenAI با مدیریت کامل خطا و جابجایی کلیدها
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
        
        // ردیابی کلیدهای ناموفق برای جابجایی سریع
        val failedKeys = mutableSetOf<String>()
        
        // تلاش با هر provider با مدیریت خطا و جابجایی کلیدها
        for (provider in sttProviders) {
            // اگر این کلید قبلاً ناموفق بوده، از آن رد شو
            val keyId = "${provider.provider}_${provider.key.take(10)}"
            if (failedKeys.contains(keyId)) {
                Log.d(TAG, "⏭️ Skipping failed key: $keyId")
                continue
            }
            
            try {
                Log.d(TAG, "🔄 Trying STT with provider: ${provider.provider} (key: ${provider.key.take(10)}...)")
                val result = transcribeWithProvider(audioFile, provider)
                if (result.isSuccess) {
                    Log.d(TAG, "✅ STT success with ${provider.provider}: ${result.text}")
                    return@withContext result
                } else {
                    Log.w(TAG, "⚠️ STT failed with ${provider.provider}: ${result.error}")
                    // کلید ناموفق را به لیست اضافه کن تا دوباره امتحان نشود
                    failedKeys.add(keyId)
                }
            } catch (e: Exception) {
                Log.w(TAG, "❌ STT exception with ${provider.provider}: ${e.message}")
                // کلید ناموفق را به لیست اضافه کن تا دوباره امتحان نشود
                failedKeys.add(keyId)
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
     * اولویت‌بندی STT - اول OpenAI سپس GapGPT سپس Liara
     * OpenAI STT از whisper-1 استفاده می‌کند (پایدارترین)
     * GapGPT STT از whisper-1 استفاده می‌کند (سریع‌تر اما 429 می‌دهد)
     * Liara STT از chat completions استفاده می‌کند (کندتر و 410 می‌دهد)
     */
    private fun prioritizeProviders(apiKeys: List<APIKey>): List<APIKey> {
        val activeKeys = apiKeys.filter { it.isActive }
        
        // اولویت: OPENAI → GAPGPT → LIARA
        val openaiKeys = activeKeys.filter { it.provider == AIProvider.OPENAI }
        val gapgptKeys = activeKeys.filter { it.provider == AIProvider.GAPGPT }
        val liaraKeys = activeKeys.filter { it.provider == AIProvider.LIARA }
        
        return openaiKeys + gapgptKeys + liaraKeys
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
     * تست اتصال به GapGPT با GET /v1/models (برای تشخیص مشکل شبکه vs endpoint)
     */
    private suspend fun probeGapGPTConnectivity(apiKey: String) {
        try {
            Log.d(TAG, "🔍 Probing GapGPT connectivity with /v1/models")
            val request = Request.Builder()
                .url("https://api.gapgpt.app/v1/models")
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()
            
            val response = gapgptHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            
            if (response.isSuccessful) {
                Log.d(TAG, "✅ GapGPT connectivity OK: models endpoint succeeded (${response.code})")
                // لاگ تعداد مدل‌ها برای اطمینان
                try {
                    val json = JSONObject(body)
                    val data = json.optJSONArray("data")
                    Log.d(TAG, "📊 GapGPT models count: ${data?.length() ?: 0}")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not parse models response", e)
                }
            } else {
                Log.w(TAG, "⚠️ GapGPT connectivity issue: models endpoint failed (${response.code}) $body")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ GapGPT connectivity probe failed", e)
        }
    }
    
    /**
     * STT با استفاده از GapGPT (whisper-1 -> gapgpt/whisper-1)
     * اولویت: whisper-1 (قوی‌تر) -> gapgpt/whisper-1
     */
    private suspend fun transcribeWithGapGPT(audioFile: File, apiKey: String): STTResult {
        // Probe اتصال GapGPT (فقط یک بار)
        if (!gapgptProbed) {
            probeGapGPTConnectivity(apiKey)
            gapgptProbed = true
        }
        
        // تبدیل فایل صوتی به multipart form
        val audioBytes = audioFile.readBytes()
        val contentType = when {
            audioFile.name.endsWith(".mp3") -> "audio/mpeg"
            audioFile.name.endsWith(".wav") -> "audio/wav"
            audioFile.name.endsWith(".m4a") -> "audio/mp4"
            else -> "audio/wav"  // fallback
        }
        val audioRequestBody = audioBytes
            .toRequestBody(contentType.toMediaType(), 0, audioBytes.size)
        
        // تابع داخلی برای ساختن درخواست با مدل دلخواه
        fun buildRequest(modelName: String): Request {
            Log.d(TAG, "GapGPT STT request: file=${audioFile.name} bytes=${audioBytes.size} contentType=$contentType model=$modelName")
            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", modelName)          // ← اینجا مدل را می‌فرستیم
                .addFormDataPart("file", audioFile.name, audioRequestBody)
                .build()
            
            return Request.Builder()
                .url("https://api.gapgpt.app/v1/audio/transcriptions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(multipartBody)
                .build()
        }
        
        // تلاش اول: whisper-1 (قوی‌تر)
        // تلاش دوم: gapgpt/whisper-1 (اگر خطا بود)
        // تلاش سوم: retry با تاخیر کم (اگر 429 بود)
        return try {
            var request = buildRequest("whisper-1")
            var response = gapgptHttpClient.newCall(request).execute()
            var responseBody = response.body?.string() ?: ""
            
            if (response.isSuccessful) {
                val json = JSONObject(responseBody)
                val text = json.optString("text", "")
                return if (text.isNotBlank()) {
                    STTResult.success(text)
                } else {
                    STTResult.error("Empty response from GapGPT (whisper-1)")
                }
            }
            
            // اگر خطای 504 یا 400 بود، با gapgpt/whisper-1 دوباره امتحان کن
            if (response.code == 504 || response.code == 400) {
                Log.w(TAG, "GapGPT returned ${response.code}, retrying with gapgpt/whisper-1")
                request = buildRequest("gapgpt/whisper-1")
                response = gapgptHttpClient.newCall(request).execute()
                responseBody = response.body?.string() ?: ""
                
                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    val text = json.optString("text", "")
                    return if (text.isNotBlank()) {
                        STTResult.success(text)
                    } else {
                        STTResult.error("Empty response from GapGPT (gapgpt/whisper-1)")
                    }
                }
                
                return STTResult.error("GapGPT API error after retry: ${response.code}")
            }
            
            // Minimal backoff for 429: 2s, 5s, 10s (برای سرعت بیشتر)
            if (response.code == 429) {
                val delays = listOf(2000L, 5000L, 10000L)
                for ((i, delay) in delays.withIndex()) {
                    Log.w(TAG, "429 retry ${i+1}/${delays.size}, wait ${delay/1000}s")
                    kotlinx.coroutines.delay(delay)
                    request = buildRequest("whisper-1")
                    response = gapgptHttpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val json = JSONObject(response.body?.string() ?: "")
                        val text = json.optString("text", "")
                        return if (text.isNotBlank()) STTResult.success(text)
                        else STTResult.error("Empty response after retry")
                    }
                }
                return STTResult.error("GapGPT 429 after all retries")
            }
            
            // سایر خطاها
            STTResult.error("GapGPT API error: ${response.code}")
        } catch (e: Exception) {
            Log.e(TAG, "GapGPT STT error", e)
            STTResult.error("GapGPT STT failed: ${e.message}")
        }
    }
    
    /**
     * STT با استفاده از Liara (google/gemini-2.0-flash-001) via chat completions
     */
    private suspend fun transcribeWithLiara(audioFile: File, apiKey: String): STTResult {
        return try {
            // تبدیل فایل صوتی به base64
            val audioBytes = audioFile.readBytes()
            val audioBase64 = android.util.Base64.encodeToString(audioBytes, android.util.Base64.NO_WRAP)
            
            // ساخت درخواست مطابق مستند لیارا با JSONArray صحیح
            val requestBody = JSONObject().apply {
                put("model", "google/gemini-2.0-flash-001")
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", "What is the audio saying? Please transcribe the audio content in Persian.")
                            })
                            put(JSONObject().apply {
                                put("type", "input_audio")
                                put("input_audio", JSONObject().apply {
                                    put("data", audioBase64)
                                    put("format", "m4a")
                                })
                            })
                        })
                    })
                })
            }.toString().toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("https://ai.liara.ir/api/69467b6ba99a2016cac892e1/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()
            
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            
            if (response.isSuccessful) {
                val json = JSONObject(responseBody)
                val text = json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "")
                
                return if (!text.isNullOrBlank()) {
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
