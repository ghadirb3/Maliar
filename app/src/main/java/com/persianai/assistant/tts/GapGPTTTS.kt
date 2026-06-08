package com.persianai.assistant.tts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import com.persianai.assistant.utils.PreferencesManager
import com.persianai.assistant.utils.IviraProcessingHelper
import com.persianai.assistant.integration.IviraIntegrationManager

/**
 * سرویس TTS آنلاین GapGPT با مدل gpt-4o-mini-tts
 * اولویت اول برای تبدیل متن به گفتار فارسی
 */
class GapGPTTTS(private val context: Context) {
    
    private val TAG = "GapGPTTTS"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val prefsManager = PreferencesManager(context)
    private val iviraManager = IviraIntegrationManager(context)
    
    companion object {
        private const val BASE_URL = "https://api.gapgpt.app/v1"
        private const val TTS_ENDPOINT = "$BASE_URL/audio/speech"
        // Priority: gemini-2.5-pro-preview-tts -> gemini-2.5-flash-preview-tts -> tts-1 -> gpt-4o-mini-tts
        private val MODELS = listOf(
            "gemini-2.5-pro-preview-tts",
            "gemini-2.5-flash-preview-tts",
            "tts-1",
            "gpt-4o-mini-tts"
        )
        private const val VOICE = "alloy" // می‌توان به voice فارسی تغییر داد
    }
    
    /**
     * تبدیل متن به گفتار با استفاده از GapGPT API
     * با مکانیزم فال‌بک روی مدل‌ها
     * @param text متنی که باید به گفتار تبدیل شود
     * @return مسیر فایل صوتی تولید شده یا null در صورت خطا
     */
    suspend fun synthesizeSpeech(text: String): File? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🎤 در حال تبدیل متن به گفتار با GapGPT: '$text'")
            
            // دریافت API key از سیستم Ivira (همان سیستم سایر بخش‌های اپ)
            val apiKey = getGapGPTApiKey()
            if (apiKey.isBlank()) {
                Log.w(TAG, "⚠️ API Key برای GapGPT تنظیم نشده است")
                return@withContext null
            }
            
            // تلاش با هر مدل به ترتیب اولویت
            for (model in MODELS) {
                try {
                    Log.d(TAG, "🔄 تلاش با مدل: $model")
                    
                    // ایجاد درخواست API
                    val requestBody = JSONObject().apply {
                        put("model", model)
                        put("input", text)
                        put("voice", VOICE)
                        put("response_format", "mp3")
                        put("speed", 1.0)
                    }.toString().toRequestBody("application/json".toMediaType())
                    
                    val request = Request.Builder()
                        .url(TTS_ENDPOINT)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("Content-Type", "application/json")
                        .post(requestBody)
                        .build()
                    
                    // ارسال درخواست
                    val response = client.newCall(request).execute()
                    
                    if (response.isSuccessful) {
                        // ذخیره فایل صوتی
                        val audioFile = saveAudioFile(response.body?.bytes())
                        
                        if (audioFile != null) {
                            Log.d(TAG, "✅ فایل صوتی با موفقیت تولید شد با مدل $model: ${audioFile.absolutePath}")
                            return@withContext audioFile
                        } else {
                            Log.e(TAG, "❌ خطا در ذخیره فایل صوتی با مدل $model")
                        }
                    } else {
                        Log.w(TAG, "⚠️ مدل $model ناموفق بود: ${response.code} - ${response.message}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ خطا در مدل $model: ${e.message}")
                }
            }
            
            Log.e(TAG, "❌ هیچ‌کدام از مدل‌ها کار نکرد")
            return@withContext null
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطا در تبدیل متن به گفتار با GapGPT", e)
            return@withContext null
        }
    }
    
    /**
     * دریافت API key از PreferencesManager (همان سیستم STT و چت متنی)
     * با قابلیت جابجایی بین کلیدهای مختلف GAPGPT
     */
    private fun getGapGPTApiKey(): String {
        return try {
            // استفاده از PreferencesManager که کلیدها را از لینک abrehamrahi بارگیری می‌کند
            val apiKeys = prefsManager.getAPIKeys()
            
            // جستجو برای کلیدهای GAPGPT فعال
            val gapgptKeys = apiKeys.filter { 
                it.provider == com.persianai.assistant.models.AIProvider.GAPGPT && it.isActive 
            }
            
            if (gapgptKeys.isNotEmpty()) {
                // انتخاب اولین کلید فعال (در آینده می‌توان رندوم یا round-robin کرد)
                val selectedKey = gapgptKeys.first()
                Log.d(TAG, "✅ Found ${gapgptKeys.size} GapGPT API keys, using: ${selectedKey.key.take(10)}...")
                selectedKey.key
            } else {
                Log.w(TAG, "⚠️ No active GapGPT API key found")
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطا در دریافت API key", e)
            ""
        }
    }
    
    /**
     * بررسی اینکه آیا سرویس آماده است (API key تنظیم شده)
     */
    fun isServiceReady(): Boolean {
        return getGapGPTApiKey().isNotBlank()
    }
    
    /**
     * تست اتصال به سرویس
     */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isServiceReady()) return@withContext false
            
            val testText = "تست اتصال"
            val result = synthesizeSpeech(testText)
            
            // پاک کردن فایل تست
            result?.delete()
            
            return@withContext result != null
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطا در تست اتصال", e)
            return@withContext false
        }
    }
    
    /**
     * ذخیره فایل صوتی در حافظه موقت
     */
    private fun saveAudioFile(audioData: ByteArray?): File? {
        if (audioData == null || audioData.isEmpty()) return null
        
        return try {
            val outputDir = File(context.cacheDir, "tts_audio")
            if (!outputDir.exists()) {
                outputDir.mkdirs()
            }
            
            val outputFile = File(outputDir, "gapgpt_${System.currentTimeMillis()}.mp3")
            
            FileOutputStream(outputFile).use { fos ->
                fos.write(audioData)
                fos.flush()
            }
            
            return outputFile
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطا در ذخیره فایل صوتی", e)
            return null
        }
    }
    
    /**
     * پاک کردن فایل‌های صوتی قدیمی
     */
    fun cleanupOldAudioFiles() {
        try {
            val outputDir = File(context.cacheDir, "tts_audio")
            if (outputDir.exists()) {
                val files = outputDir.listFiles()
                val now = System.currentTimeMillis()
                
                files?.forEach { file ->
                    // حذف فایل‌های قدیمی‌تر از ۱ ساعت
                    if (now - file.lastModified() > 3600000) {
                        file.delete()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطا در پاک کردن فایل‌های قدیمی", e)
        }
    }
}
