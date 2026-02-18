package com.persianai.assistant.utils

import android.content.Context
import android.util.Log
import com.persianai.assistant.models.AIProvider
import com.persianai.assistant.models.APIKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * مدیر بارگذاری خودکار کلیدهای API
 * استراتژی: اولویت Liara، سپس سایر providers
 */
object AutoProvisioningManager {
    
    private const val TAG = "AutoProvisioning"
    private const val DEFAULT_PASSWORD = "12345"
    // منبع کلیدها (اولویت با لینک قبلی، فال‌بک به گیت)
    private const val OLD_KEYS_URL = "https://abrehamrahi.ir/o/public/UfAv7lIC/"
    private const val GIST_KEYS_URL = "https://gist.githubusercontent.com/ghadirb/626a804df3009e49045a2948dad89fe5/raw/4598062acc8f167b95ae84462722125c325a3d2f/keys.txt"

    /**
     * بارگذاری و فعال‌سازی کلیدها با مکانیزم فال‌بک
     */
    suspend fun autoProvision(context: Context): Result<List<APIKey>> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔄 شروع بارگذاری خودکار کلیدها (با فال‌بک)...")

            // تلاش اول از لینک قبلی
            val oldResult = tryLoadFromUrl(OLD_KEYS_URL, "لینک قبلی")
            if (oldResult.isSuccess && hasRequiredKeys(oldResult.getOrThrow())) {
                Log.d(TAG, "✅ کلیدهای مورد نیاز از لینک قبلی پیدا شد")
                return@withContext oldResult
            }

            // فال‌بک به لینک جدید
            Log.d(TAG, "⚠️ لینک قبلی مناسب نبود، تلاش از لینک جدید...")
            val newResult = tryLoadFromUrl(GIST_KEYS_URL, "لینک جدید (Gist)")
            if (newResult.isSuccess) {
                Log.d(TAG, "✅ کلیدها از لینک جدید بارگذاری شد")
                return@withContext newResult
            }

            // اگر هیچ‌کدام کار نکرد
            return@withContext Result.failure(Exception("هیچ‌کدام از منابع کلید پاسخ ندادند"))

        } catch (e: Exception) {
            Log.e(TAG, "خطای بارگذاری: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * بارگذاری از یک URL مشخص
     */
    private suspend fun tryLoadFromUrl(url: String, sourceName: String): Result<List<APIKey>> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "📥 دانلود فایل رمزشده از $sourceName: $url")

            // 1) دانلود
            val encryptedData = runCatching {
                DriveHelper.downloadFromUrl(url)
            }.getOrElse { e ->
                Log.e(TAG, "❌ خطا در دانلود از $sourceName: ${e.message}")
                return@withContext Result.failure(e)
            }

            if (encryptedData.isBlank()) {
                Log.e(TAG, "❌ فایل دانلود شده از $sourceName خالی است")
                return@withContext Result.failure(Exception("فایل کلیدها خالی است"))
            }

            // 2) رمزگشایی
            val decryptedData = runCatching {
                EncryptionHelper.decrypt(encryptedData, DEFAULT_PASSWORD)
            }.onFailure {
                Log.e(TAG, "❌ خطا در رمزگشایی از $sourceName: ${it.message}")
                Log.e(TAG, "دانلود شده (پیش‌نمایش): ${encryptedData.take(120)}")
            }.getOrElse { e ->
                return@withContext Result.failure(e)
            }

            if (decryptedData.isBlank()) {
                Log.e(TAG, "❌ فایل رمزگشایی شده از $sourceName خالی است")
                return@withContext Result.failure(Exception("رمزگشایی ناموفق بود (خروجی خالی)"))
            }

            Log.d(TAG, "📝 محتوای رمزگشایی شده از $sourceName:")
            decryptedData.lines().forEach { line ->
                Log.d(TAG, "  > $line")
            }

            // 3) پارس و نرمال‌سازی
            val parsed = parseAPIKeys(decryptedData)
            if (parsed.isEmpty()) {
                Log.w(TAG, "⚠️ هیچ کلید معتبری از $sourceName یافت نشد")
                return@withContext Result.failure(Exception("هیچ کلید معتبری در فایل یافت نشد"))
            }

            val processedKeys = parsed.map { key ->
                val inferredProvider = key.provider
                val defaultBase = when {
                    inferredProvider == AIProvider.LIARA -> "https://ai.liara.ir/api/69467b6ba99a2016cac892e1/v1"
                    inferredProvider == AIProvider.AIML -> "https://api.aimlapi.com/v1"
                    inferredProvider == AIProvider.GLADIA -> "https://api.gladia.io"
                    inferredProvider == AIProvider.GAPGPT -> "https://api.gapgpt.app/v1"
                    inferredProvider == AIProvider.OPENROUTER && key.key.startsWith("hf_") ->
                        "https://router.huggingface.co/models/openai/whisper-large-v3"
                    inferredProvider == AIProvider.OPENROUTER -> "https://openrouter.ai/api/v1"
                    inferredProvider == AIProvider.OPENAI -> "https://api.openai.com/v1"
                    else -> key.baseUrl
                }
                key.copy(
                    isActive = true,
                    baseUrl = key.baseUrl ?: defaultBase
                )
            }

            Log.d(TAG, "✅ تعداد کلیدهای پارس شده از $sourceName: ${processedKeys.size}")
            processedKeys.forEach { key ->
                Log.d(TAG, "  - ${key.provider.name}: ${key.key.take(10)}... base=${key.baseUrl}")
            }

            Result.success(processedKeys)
        } catch (e: Exception) {
            Log.e(TAG, "خطا در بارگذاری از $sourceName: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * بررسی اینکه آیا کلیدهای مورد نیاز (GAPGPT و Liara) وجود دارند
     */
    private fun hasRequiredKeys(keys: List<APIKey>): Boolean {
        val hasGapgpt = keys.any { it.provider == AIProvider.GAPGPT }
        val hasLiara = keys.any { it.provider == AIProvider.LIARA }
        
        Log.d(TAG, "🔍 بررسی کلیدهای مورد نیاز: GAPGPT=$hasGapgpt, Liara=$hasLiara")
        
        return hasGapgpt && hasLiara
    }
    
    /**
     * پارس کلیدها
     */
    private fun parseAPIKeys(data: String): List<APIKey> {
        val keys = mutableListOf<APIKey>()
        
        data.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#")) return@forEach
            
            val (provider, key, baseUrl) = parseKeyLine(trimmed)
            if (provider != null && key.isNotBlank()) {
                keys.add(
                    APIKey(
                        provider = provider,
                        key = key,
                        baseUrl = baseUrl,
                        isActive = false // شروع غیرفعال، بعداً فعال می‌شود
                    )
                )
                Log.d(TAG, "✓ پارس: ${provider.name}")
            } else {
                Log.w(TAG, "خط نامعتبر: $trimmed")
            }
        }
        
        return keys
    }
    
    /**
     * پارس یک خط
     * فرمت: provider:key:baseUrl (baseUrl اختیاری)
     */
    private fun parseKeyLine(line: String): Triple<AIProvider?, String, String?> {
        val parts = line.split(":").map { it.trim() }
        
        // Case 1: explicit provider:key(:baseUrl) ONLY if provider token is recognized
        if (parts.size >= 2) {
            val provider = when (parts[0].lowercase()) {
                "liara" -> AIProvider.LIARA
                "openai", "gpt" -> AIProvider.OPENAI
                "anthropic", "claude" -> AIProvider.ANTHROPIC
                "openrouter" -> AIProvider.OPENROUTER
                "aiml", "aimlapi" -> AIProvider.AIML
                "gladia" -> AIProvider.GLADIA
                "huggingface", "hf" -> AIProvider.OPENROUTER
                "gapgpt" -> AIProvider.GAPGPT
                else -> null
            }
            
            if (provider != null) {
                val key = parts.getOrNull(1) ?: ""
                val baseUrl = parts.getOrNull(2)
                return Triple(provider, key, baseUrl)
            }
        }

        // Case 2: raw key with no provider prefix (or unrecognized first token) -> infer by pattern
        val inferredProvider = inferProviderFromRawKey(line)
        return Triple(inferredProvider, line, null)
    }

    /**
     * Heuristic provider detection for raw keys (no prefix in file)
     * Priority: OpenRouter (sk-or), Liara (JWT-like), OpenAI (sk- or project), otherwise null.
     */
    private fun inferProviderFromRawKey(raw: String): AIProvider? {
        val trimmed = raw.trim()
        val lower = trimmed.lowercase()

        // OpenRouter keys start with sk-or
        if (lower.startsWith("sk-or")) return AIProvider.OPENROUTER

        // AIML API keys sometimes start with aiml_ or sk-aiml
        if (lower.startsWith("aiml") || lower.startsWith("sk-aiml")) return AIProvider.AIML

        // Liara keys in gist are JWT-like tokens starting with eyJ...
        if (trimmed.startsWith("eyJ")) return AIProvider.LIARA

        // Gladia API keys are UUID-like (xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx)
        if (trimmed.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\$")))
            return AIProvider.GLADIA

        // HuggingFace tokens start with hf_
        if (lower.startsWith("hf_")) return AIProvider.OPENROUTER

        // AIML often uses 32-char hex tokens (e.g., 3335a3...)
        if (trimmed.matches(Regex("^[a-fA-F0-9]{32}\$"))) return AIProvider.AIML

        // GAPGPT keys start with sk- but are not OpenAI - check before OpenAI
        // GAPGPT keys are typically longer and have specific patterns
        if (lower.startsWith("sk-") && trimmed.length > 50) {
            // Additional heuristic: GAPGPT keys often contain specific character patterns
            // This is a reasonable heuristic for now
            return AIProvider.GAPGPT
        }

        // OpenAI (and some project keys) start with sk- or sk-proj-
        if (lower.startsWith("sk-")) return AIProvider.OPENAI

        // Google-style API keys (AIza...) -> treat as OpenAI-compatible for now
        if (trimmed.startsWith("AIza")) return AIProvider.OPENAI

        return null
    }
    
    /**
     * تست کلیدها
     */
    private fun getFreeFallbackKeys(): List<APIKey> {
        Log.d(TAG, "📡 بارگذاری free keys fallback...")
        val freeKeys = mutableListOf<APIKey>()
        
        // OpenRouter - دارای مدل‌های رایگان بسیار خوب (Gemini Nano، Llama 3.2، و غیره)
        // ⚠️ اگر key blank است، OpenRouter free endpoints بدون auth کار می‌کند
        freeKeys.add(APIKey(
            provider = AIProvider.OPENROUTER,
            key = "sk-or-free",  // OpenRouter free public key
            baseUrl = "https://openrouter.ai/api/v1",
            isActive = true
        ))
        
        // Free OpenAI endpoints (اگر تریل دسترس داشته باشید)
        // Note: این کلیدها عمومی هستند و ممکن است rate-limited باشند
        freeKeys.add(APIKey(
            provider = AIProvider.OPENAI,
            key = "sk-proj-free",  // OpenAI free trial key (اگر فعال باشد)
            baseUrl = "https://api.openai.com/v1",
            isActive = true
        ))
        
        // AIML API free tier
        freeKeys.add(APIKey(
            provider = AIProvider.AIML,
            key = "free-aiml-fallback",
            baseUrl = null,
            isActive = true
        ))
        
        Log.d(TAG, "✅ ${freeKeys.size} free fallback keys loaded (OpenRouter first priority)")
        freeKeys.forEach { key ->
            Log.d(TAG, "  - ${key.provider.name}: ${key.baseUrl ?: "default"}")
        }
        
        return freeKeys
    }
    
    /**
     * تست کلیدها
     */
    suspend fun validateAndUpdateKeys(context: Context): Int {
        val prefsManager = PreferencesManager(context)
        val keys = prefsManager.getAPIKeys()
        
        var validCount = 0
        val updatedKeys = mutableListOf<APIKey>()
        
        keys.forEach { key ->
            try {
                val isValid = testAPIKey(key)
                if (isValid) {
                    validCount++
                    updatedKeys.add(key.copy(isActive = true))
                    Log.d(TAG, "✅ معتبر: ${key.provider.name}")
                } else {
                    updatedKeys.add(key.copy(isActive = false))
                    Log.w(TAG, "❌ نامعتبر: ${key.provider.name}")
                }
            } catch (e: Exception) {
                updatedKeys.add(key.copy(isActive = false))
                Log.e(TAG, "خطا در تست: ${e.message}")
            }
        }
        
        prefsManager.saveAPIKeys(updatedKeys)
        return validCount
    }
    
    /**
     * تست یک کلید
     */
    private suspend fun testAPIKey(apiKey: APIKey): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            
            val request = when (apiKey.provider) {
                AIProvider.LIARA -> {
                    val baseUrl = apiKey.baseUrl?.trim()?.trimEnd('/') 
                        ?: "https://ai.liara.ir/api/69467b6ba99a2016cac892e1/v1"
                    okhttp3.Request.Builder()
                        .url("$baseUrl/models")
                        .addHeader("Authorization", "Bearer ${apiKey.key}")
                        .build()
                }
                AIProvider.OPENAI -> {
                    okhttp3.Request.Builder()
                        .url("https://api.openai.com/v1/models")
                        .addHeader("Authorization", "Bearer ${apiKey.key}")
                        .build()
                }
                AIProvider.ANTHROPIC -> {
                    okhttp3.Request.Builder()
                        .url("https://api.anthropic.com/v1/models")
                        .addHeader("x-api-key", apiKey.key)
                        .build()
                }
                AIProvider.CUSTOM -> {
                    // برای مدل‌های سفارشی، baseUrl از apiKey استفاده می‌شود
                    val baseUrl = apiKey.baseUrl?.trim()?.trimEnd('/') ?: "https://api.example.com/v1"
                    okhttp3.Request.Builder()
                        .url("$baseUrl/models")
                        .addHeader("Authorization", "Bearer ${apiKey.key}")
                        .build()
                }
                else -> return@withContext true
            }
            
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "تست شکست: ${e.message}")
            false
        }
    }
}