package com.persianai.assistant.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import android.util.Base64
import android.os.Environment
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * مدیریت توکن های Ivira API
 * کلیدها از لینک رمزشده دریافت می‌شوند و استفاده می‌شوند
 */
class IviraTokenManager(private val context: Context) {
    
    companion object {
        private const val TAG = "IviraTokenManager"
        
        // Ivira API Endpoints
        const val IVIRA_API_URL = "https://api.ivira.ai/v1/chat/completions"
        const val IVIRA_TTS_URL = "https://partai.gw.isahab.ir/avasho/avasho/request"
        const val IVIRA_STT_URL = "https://partai.gw.isahab.ir/avanegar/avanegar/request"
        
        // Models Priority
        const val MODEL_VIRA = "compound-vira"  // مدل زبانی ترکیبی ویرا (نام واقعی روی سرور)
        const val MODEL_GPT5_MINI = "gpt-5-mini"  // GPT-5 Mini
        const val MODEL_GPT5_NANO = "gpt-5-nano"  // GPT-5 Nano
        const val MODEL_GEMMA3_27B = "gemma3-27b"  // Gemma 3 27B
        const val MODEL_AVANGARDI = "avangardi"  // آوانگار (TTS) - جدید
        const val MODEL_AWASHO = "awasho"  // آواشو (STT) - جدید
        
        // Token encryption key
        private const val ENCRYPTION_KEY_LENGTH = 32  // 256-bit
        private const val IV_LENGTH = 12  // 96-bit for GCM
        private const val TAG_LENGTH = 16  // 128-bit for GCM
        private const val ITERATIONS = 20000
    }
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    
    private val prefs = context.getSharedPreferences("ivira_tokens", Context.MODE_PRIVATE)
    
    /**
     * دریافت توکن‌های رمزشده از لینک
     */
    suspend fun fetchEncryptedTokensFromUrl(
        url: String = "https://abrehamrahi.ir/o/public/UfAv7lIC/",
        password: String = "12345"
    ): Result<Map<String, String>> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔄 Fetching encrypted tokens from $url")
            
            val request = Request.Builder()
                .url(url)
                .build()
            
            val response = httpClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.e(TAG, "❌ Failed to fetch tokens: ${response.code}")
                return@withContext Result.failure(Exception("HTTP ${response.code}"))
            }
            
            val encryptedContent = response.body?.string() 
                ?: return@withContext Result.failure(Exception("Empty response"))
            
            // فک کردن توکن‌ها
            val tokens = decryptTokens(encryptedContent, password)
            
            // ذخیره توکن‌ها
            saveTokens(tokens)
            
            Log.d(TAG, "✅ Successfully fetched and saved ${tokens.size} tokens")
            Result.success(tokens)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error fetching tokens", e)
            Result.failure(e)
        }
    }
    
    /**
     * فک کردن توکن‌های رمزشده (Base64 + AES-GCM)
     */
    private fun decryptTokens(encryptedB64: String, password: String): Map<String, String> {
        try {
            // Decode Base64
            val encryptedBytes = Base64.decode(encryptedB64.trim(), Base64.DEFAULT)
            
            // Extract salt and IV
            val salt = encryptedBytes.sliceArray(0 until 16)
            val iv = encryptedBytes.sliceArray(16 until 28)
            val ciphertext = encryptedBytes.sliceArray(28 until encryptedBytes.size)
            
            // Derive key using PBKDF2
            val key = deriveKey(password, salt)
            
            // Decrypt using AES-GCM
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(TAG_LENGTH * 8, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)
            
            val decryptedBytes = cipher.doFinal(ciphertext)
            val decryptedText = String(decryptedBytes, Charsets.UTF_8)
            
            // Parse tokens (each line is a token)
            val tokens = mutableMapOf<String, String>()
            decryptedText.split("\n").forEachIndexed { index, rawToken ->
                val token = rawToken.trim()
                if (token.isNotEmpty()) {
                    val modelName = getModelNameForToken(index)
                    tokens[modelName] = token
                    Log.d(TAG, "✅ Extracted token for $modelName")
                }
            }
            
            return tokens
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error decrypting tokens", e)
            throw e
        }
    }
    
    /**
     * اشتقاق کلید از رمز عبور
     */
    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, ENCRYPTION_KEY_LENGTH * 8)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val key = factory.generateSecret(spec)
        return SecretKeySpec(key.encoded, 0, key.encoded.size, "AES")
    }
    
    /**
     * نام مدل را برای هر توکن تعیین کنید
     */
    private fun getModelNameForToken(index: Int): String {
        return when (index) {
            0 -> MODEL_VIRA  // Vira (ترکیبی)
            1 -> MODEL_GPT5_MINI
            2 -> MODEL_GPT5_NANO
            3 -> MODEL_GEMMA3_27B
            4 -> MODEL_AVANGARDI  // TTS
            5 -> MODEL_AWASHO  // STT
            else -> "unknown_model_$index"
        }
    }
    
    /**
     * ذخیره توکن‌ها در SharedPreferences
     */
    private fun saveTokens(tokens: Map<String, String>) {
        prefs.edit().apply {
            clear()
            tokens.forEach { (model, token) ->
                putString("token_$model", token)
                Log.d(TAG, "💾 Saved token for $model")
            }
            apply()
        }
    }
    
    /**
     * بازیافت توکن برای یک مدل
     */
    fun getToken(model: String): String? {
        return prefs.getString("token_$model", null)
    }
    
    /**
     * بازیافت تمام توکن‌ها
     */
    fun getAllTokens(): Map<String, String> {
        val tokens = mutableMapOf<String, String>()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("token_") && value is String) {
                val model = key.removePrefix("token_")
                tokens[model] = value
            }
        }

        // If tokens were found in SharedPreferences, log masked view for diagnostics
        if (tokens.isNotEmpty()) {
            logMaskedTokens(tokens)
            return tokens
        }

        // اگر توکن‌ها در Preferences پیدا نشدند، تلاش برای بارگذاری از assets یا مسیر دانلود محلی
        if (tokens.isEmpty()) {
            // Try assets first
            try {
                val assetStream = try {
                    context.assets.open("ivira_tokens.txt")
                } catch (e: Exception) {
                    null
                }

                if (assetStream != null) {
                    val content = assetStream.bufferedReader().use { it.readText() }
                    val parsed = parseTokensFromPlainText(content)
                    if (parsed.isNotEmpty()) {
                        saveTokens(parsed)
                        logMaskedTokens(parsed)
                        return parsed
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "No ivira tokens in assets: ${e.message}")
            }

            // Common external / download locations to check (covers many devices)
            val downloadCandidates = listOf(
                "/sdcard/Download/key_2.txt",
                "/sdcard/Download/ivira_keys.txt",
                "/sdcard/Download/ivira_tokens.txt",
                "/storage/emulated/0/Download/key_2.txt",
                "/storage/emulated/0/Download/ivira_tokens.txt",
                (context.filesDir?.absolutePath ?: "") + "/ivira_tokens.txt",
                (context.getExternalFilesDir(null)?.absolutePath ?: "") + "/ivira_tokens.txt"
            )

            for (path in downloadCandidates) {
                try {
                    if (path.isBlank()) continue
                    val f = File(path)
                    if (f.exists()) {
                        val content = f.readText()
                        val parsed = parseTokensFromPlainText(content)
                        if (parsed.isNotEmpty()) {
                            saveTokens(parsed)
                            logMaskedTokens(parsed)
                            return parsed
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Could not read ivira tokens from $path: ${e.message}")
                }
            }
        }

        return tokens
    }

    private fun logMaskedTokens(tokens: Map<String, String>) {
        try {
            val masked = tokens.map { (k, v) ->
                val t = v
                val m = if (t.length > 12) t.take(6) + "..." + t.takeLast(4) else t.take(4) + "..."
                "$k=$m"
            }
            Log.d(TAG, "💡 Loaded Ivira tokens: ${masked.joinToString(", ")}")
        } catch (e: Exception) {
            Log.d(TAG, "Could not mask tokens for logging: ${e.message}")
        }
    }

    /**
     * Parse plain-text token files. Lines can be either `MODEL:token` or plain token per line.
     */
    private fun parseTokensFromPlainText(text: String): Map<String, String> {
        val extracted = mutableListOf<String>()

        val jwtRegex = Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
        val wordRegex = Regex("[A-Za-z0-9-_]{10,}")

        for (raw in text.lines()) {
            var line = raw.trim()
            if (line.isEmpty()) continue

            // skip comment-only lines
            if (line.startsWith("#") || line.startsWith("//")) continue

            // Try to extract a JWT-like token first
            val jwtMatch = jwtRegex.find(line)
            if (jwtMatch != null) {
                val token = jwtMatch.value.replace(Regex("[^\\x20-\\x7E]"), "").trim().trim('"', '\'')
                if (token.length >= 10) extracted.add(token)
                continue
            }

            // If line contains `model:token` style, try to parse right-hand side
            if (line.contains(":")) {
                val parts = line.split(":", limit = 2)
                val right = parts.getOrNull(1)?.trim() ?: ""
                val m = jwtRegex.find(right) ?: wordRegex.find(right)
                val token = m?.value?.replace(Regex("[^\\x20-\\x7E]"), "")?.trim()?.trim('"', '\'')
                if (!token.isNullOrBlank() && token.length >= 10) {
                    extracted.add(token)
                    continue
                }
            }

            // Fallback: find first long alphanumeric chunk
            val m = wordRegex.find(line)
            val token = m?.value?.replace(Regex("[^\\x20-\\x7E]"), "")?.trim()?.trim('"', '\'')
            if (!token.isNullOrBlank() && token.length >= 10) {
                extracted.add(token)
            }
        }

        val tokens = mutableMapOf<String, String>()
        // If exactly two tokens provided, map to STT/TTS common order
        if (extracted.size == 2) {
            tokens[MODEL_AWASHO] = extracted[0]
            tokens[MODEL_AVANGARDI] = extracted[1]
            return tokens
        }

        var index = 0
        for (t in extracted) {
            val model = getModelNameForToken(index)
            tokens[model] = t
            index++
        }

        return tokens
    }
    
    /**
     * ذخیره یا به‌روزرسانی یک توکن
     */
    fun setToken(model: String, token: String) {
        prefs.edit().putString("token_$model", token).apply()
    }
    
    /**
     * چک کردن وجود توکن‌ها
     */
    fun hasTokens(): Boolean = getAllTokens().isNotEmpty()
    
    /**
     * پاک کردن تمام توکن‌ها
     */
    fun clearTokens() {
        prefs.edit().clear().apply()
        Log.d(TAG, "🗑️ Cleared all tokens")
    }
    
    /**
     * دریافت مدل با اولویت (اگر مدل اول کار نکند، مدل دوم را بکار)
     */
    fun getTextModelInPriority(): List<String> {
        return listOf(
            MODEL_VIRA,        // اولویت 1
            MODEL_GPT5_MINI,   // اولویت 2
            MODEL_GPT5_NANO,   // اولویت 3
            MODEL_GEMMA3_27B   // اولویت 4
        ).filter { model ->
            getToken(model) != null
        }
    }
    
    /**
     * دریافت مدل TTS با اولویت
     */
    fun getTTSModelInPriority(): List<String> {
        return listOf(
            MODEL_AVANGARDI,  // اولویت 1
            MODEL_AWASHO      // اولویت 2
        ).filter { model ->
            getToken(model) != null
        }
    }
    
    /**
     * دریافت مدل STT با اولویت
     */
    fun getSTTModelInPriority(): List<String> {
        return listOf(
            MODEL_AWASHO,     // اولویت 1
            MODEL_AVANGARDI   // اولویت 2
        ).filter { model ->
            getToken(model) != null
        }
    }
}
