package com.persianai.assistant.ai

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.persianai.assistant.utils.ModelSelector
import com.persianai.assistant.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.ConnectionPool
import java.util.concurrent.TimeUnit

/**
 * کلاینت اصلی برای ارتباط با APIهای هوش مصنوعی
 * با قابلیت خودکار تعویض کلید در صورت خطا و پشتیبانی از مدل‌های داینامیک
 */
class AIClient(private val context: Context, private val apiKeys: List<APIKey>) {

    private fun formatExceptionForLog(e: Exception): String {
        val name = e::class.java.simpleName
        val msg = e.message ?: ""
        val causeName = e.cause?.javaClass?.simpleName
        val causeMsg = e.cause?.message
        return if (causeName != null) {
            "$name: $msg (cause=$causeName: $causeMsg)"
        } else {
            "$name: $msg"
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)  // 60 سے 120
        .readTimeout(120, TimeUnit.SECONDS)     // 60 سے 120
        .writeTimeout(120, TimeUnit.SECONDS)    // 60 سے 120
        .retryOnConnectionFailure(true)
        .protocols(listOf(Protocol.HTTP_1_1, Protocol.HTTP_2))
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .build()

    private val gson = Gson()
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    
    // ردیابی کلیدهای ناموفق
    private val failedKeys = mutableSetOf<String>()

    /**
     * ارسال پیام با انتخاب خودکار بهترین مدل از روی تنظیمات راه دور
     */
    suspend fun sendMessage(
        messages: List<ChatMessage>,
        systemPrompt: String? = null
    ): ChatMessage = withContext(Dispatchers.IO) {
        val bestModel = ModelSelector.selectBestModel(context, apiKeys)
        sendMessage(bestModel, messages, systemPrompt)
    }

    /**
     * ارسال پیام به مدل هوش مصنوعی مشخص با قابلیت تعویض خودکار کلید
     */
    suspend fun sendMessage(
        model: AIModel,
        messages: List<ChatMessage>,
        systemPrompt: String? = null
    ): ChatMessage = withContext(Dispatchers.IO) {

        // Ivira: اینجا مدیریت نمی‌شود (توکن‌محور در QueryRouter/IviraAPIClient)
        if (model.provider == AIProvider.IVIRA) {
            throw IllegalStateException("IVIRA توسط QueryRouter/IviraAPIClient مدیریت می‌شود، AIClient نباید مستقیماً فراخوانی شود")
        }

        val priority = listOf(
            AIProvider.OPENAI,
            AIProvider.LIARA,
            AIProvider.GAPGPT,
            AIProvider.AVALAI,
            AIProvider.OPENROUTER,
            AIProvider.AIML,
            AIProvider.GLADIA,
            AIProvider.ANTHROPIC,
            AIProvider.LOCAL
        )
        val availableKeys = apiKeys.filter {
            // Check if this specific key-model combination has failed
            val keyModelPair = "${it.key}_${model.modelId}"
            it.provider == model.provider && it.isActive && it.key.isNotBlank() && !failedKeys.contains(keyModelPair)
        }.sortedBy { k ->
            priority.indexOf(k.provider).let { if (it == -1) Int.MAX_VALUE else it }
        }.filter {
            if (model.provider == AIProvider.OPENROUTER && it.key.startsWith("hf_")) {
                false
            } else true
        }
        
        if (availableKeys.isEmpty()) {
            android.util.Log.e("AIClient", "❌ No active keys for ${model.provider.name}")
            throw IllegalStateException("هیچ کلید فعالی برای ${model.provider.name} یافت نشد - برای استفاده از ویژگی‌های آنلاین کلید اضافه کنید")
        }

        var lastError: Exception? = null
        for (apiKey in availableKeys) {
            try {
                android.util.Log.d("AIClient", "🔄 تلاش برای ارسال پیام با ${model.provider.name} key: ${apiKey.key.take(8)}...")
                val result = when (model.provider) {
                    AIProvider.AIML, AIProvider.GLADIA -> sendToOpenAI(model, messages, systemPrompt, apiKey)
                    AIProvider.OPENAI, AIProvider.OPENROUTER, AIProvider.LIARA, AIProvider.AVALAI, AIProvider.GAPGPT ->
                        sendToOpenAI(model, messages, systemPrompt, apiKey)
                    AIProvider.ANTHROPIC -> sendToClaude(model, messages, systemPrompt, apiKey)
                    AIProvider.LOCAL -> throw IllegalStateException("مدل آفلاین نیاز به AIClient ندارد")
                    AIProvider.CUSTOM -> {
                        // برای مدل‌های سفارشی، baseUrl از مدل استفاده می‌شود
                        sendToOpenAI(model, messages, systemPrompt, apiKey)
                    }
                    else -> throw IllegalStateException("Provider پشتیبانی نشده: ${model.provider}")
                }
                // Remove from failed keys on success for this specific key-model combination
                val keyModelPair = "${apiKey.key}_${model.modelId}"
                failedKeys.remove(keyModelPair)
                return@withContext result
            } catch (e: Exception) {
                lastError = e
                val errorMsg = e.message ?: ""
                android.util.Log.w("AIClient", "❌ Key failed for ${model.provider.name}: ${formatExceptionForLog(e)}")

                // Only mark a key as failed for this specific model when the error
                // indicates an authentication/authorization problem (401/403)
                val lowerErr = errorMsg.lowercase()
                val keyModelPair = "${apiKey.key}_${model.modelId}"
                if (lowerErr.contains("401") || lowerErr.contains("403") || lowerErr.contains("unauthorized") || lowerErr.contains("forbidden")) {
                    failedKeys.add(keyModelPair)
                    android.util.Log.d("AIClient", "Marked key as failed for model due to auth error: ${apiKey.key.take(8)}... -> $keyModelPair")
                } else {
                    android.util.Log.d("AIClient", "Not marking key as failed (non-auth error): $errorMsg")
                }
            }
        }

        android.util.Log.e("AIClient", "❌ All keys failed: ${lastError?.message}")
        // Try selecting a fallback model (different provider/model) before giving up
        try {
            val fallback = ModelSelector.selectFallbackModel(context, model, apiKeys)
            if (fallback != null && fallback != model) {
                android.util.Log.d("AIClient", "Trying fallback model: ${fallback.modelId} (${fallback.provider})")
                return@withContext sendMessage(fallback, messages, systemPrompt)
            }
        } catch (e: Exception) {
            android.util.Log.w("AIClient", "Fallback selection failed: ${e.message}")
        }

        throw lastError ?: Exception("خطای نامشخص در ارسال پیام")
    }

    /**
     * ارسال به OpenAI یا OpenRouter
     */
    private suspend fun sendToOpenAI(
        model: AIModel,
        messages: List<ChatMessage>,
        systemPrompt: String?,
        apiKey: APIKey
    ): ChatMessage = withContext(Dispatchers.IO) {
        
        val baseUrl = apiKey.baseUrl?.trim()?.trimEnd('/')
        val apiUrl = when (apiKey.provider) {
            AIProvider.OPENAI -> baseUrl?.let { "$it/chat/completions" }
                ?: "https://api.openai.com/v1/chat/completions"
            AIProvider.LIARA -> baseUrl?.let { "$it/chat/completions" }
                ?: "https://ai.liara.ir/api/69467b6ba99a2016cac892e1/v1/chat/completions"
            AIProvider.AVALAI -> baseUrl?.let { "$it/chat/completions" }
                ?: "https://avalai.ir/api/v1/chat/completions"
            AIProvider.OPENROUTER -> baseUrl?.let { "$it/chat/completions" }
                ?: "https://openrouter.ai/api/v1/chat/completions"
            AIProvider.AIML -> baseUrl?.let { "$it/chat/completions" }
                ?: "https://api.aimlapi.com/v1/chat/completions"
            AIProvider.GLADIA -> baseUrl?.let { "$it/chat/completions" }
                ?: "https://api.gladia.io/v1/chat/completions"
            AIProvider.GAPGPT -> baseUrl?.let { "$it/chat/completions" }
                ?: "https://api.gapgpt.app/v1/chat/completions"
            AIProvider.CUSTOM -> baseUrl?.let { "$it/chat/completions" }
                ?: "https://api.example.com/v1/chat/completions"
            else -> baseUrl?.let { "$it/chat/completions" }
                ?: "https://api.openai.com/v1/chat/completions"
        }

        val messageList = mutableListOf<Map<String, String>>()
        
        if (systemPrompt != null) {
            messageList.add(mapOf("role" to "system", "content" to systemPrompt))
        }
        
        messages.forEach { msg ->
            messageList.add(mapOf(
                "role" to when(msg.role) {
                    MessageRole.USER -> "user"
                    MessageRole.ASSISTANT -> "assistant"
                    MessageRole.SYSTEM -> "system"
                },
                "content" to msg.content
            ))
        }

        val requestBody: Any = when (model.provider) {
            AIProvider.GAPGPT -> {
                // طبق مستندات GapGPT، فقط model و messages لازم است
                mapOf(
                    "model" to model.modelId,
                    "messages" to messageList
                )
            }
            AIProvider.LIARA -> {
                // طبق مستندات Liara، فقط model و messages لازم است
                mapOf(
                    "model" to model.modelId,
                    "messages" to messageList
                )
            }
            else -> {
                ChatRequest(
                    model = model.modelId,
                    messages = messageList,
                    temperature = 0.0,
                    maxTokens = 500
                )
            }
        }

        val jsonBody = gson.toJson(requestBody)
        val body = jsonBody.toRequestBody(mediaType)

        // Log request details for debugging
        val requestId = "req_${System.currentTimeMillis()}"
        android.util.Log.d("AIClient", "[$requestId] Sending to ${model.provider.name}: url=$apiUrl, model=${model.modelId}")
        
        // Log full request for GAPGPT and LIARA debugging
        if (model.provider == AIProvider.GAPGPT || model.provider == AIProvider.LIARA) {
            android.util.Log.d("AIClient", "[$requestId] ${model.provider.name} request body: $jsonBody")
        }

        // Remove prefix from API key (e.g., "gapgpt:" or "liara:")
        val cleanKey = when {
            apiKey.key.startsWith("gapgpt:") -> apiKey.key.removePrefix("gapgpt:")
            apiKey.key.startsWith("liara:") -> apiKey.key.removePrefix("liara:")
            else -> apiKey.key
        }
        
        val requestBuilder = Request.Builder()
            .url(apiUrl)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $cleanKey")
            .post(body)

        // Add provider-specific headers
        when (model.provider) {
            AIProvider.OPENROUTER -> {
                requestBuilder.addHeader("HTTP-Referer", "https://github.com/ghadirb3/Maliar")
                requestBuilder.addHeader("X-Title", "Maliar AI Assistant")
            }
            AIProvider.AIML -> {
                requestBuilder.addHeader("Authorization", "Bearer $cleanKey")
            }
            AIProvider.GAPGPT -> {
                requestBuilder.addHeader("Authorization", "Bearer $cleanKey")
            }
            else -> {}
        }

        val request = requestBuilder.build()

        client.newCall(request).execute().use { response ->
            var responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                android.util.Log.e("AIClient", "API Error ${response.code}: $responseBody")

                // If GAPGPT returns 400 for chat/completions, try /v1/responses as fallback (newer models)
                if (model.provider == AIProvider.GAPGPT && response.code == 400) {
                    try {
                        val altUrl = (apiKey.baseUrl?.trim()?.trimEnd('/') ?: "https://api.gapgpt.app/v1") + "/responses"
                        android.util.Log.d("AIClient", "Trying GAPGPT /responses fallback: url=$altUrl")

                        // Some GAPGPT deployments expect a single `input` string rather than
                        // an array of message objects. Join message contents into a plain string.
                        val joinedInput = messageList.joinToString("\n\n") { it["content"] ?: "" }

                        val altBodyMap = mapOf(
                            "model" to model.modelId,
                            "input" to joinedInput
                        )
                        val altJson = gson.toJson(altBodyMap)
                        val altRequest = Request.Builder()
                            .url(altUrl)
                            .addHeader("Content-Type", "application/json")
                            .addHeader("Authorization", "Bearer $cleanKey")
                            .post(altJson.toRequestBody(mediaType))
                            .build()

                        client.newCall(altRequest).execute().use { altResp ->
                            responseBody = altResp.body?.string() ?: ""
                            if (!altResp.isSuccessful) {
                                android.util.Log.e("AIClient", "GAPGPT /responses Error ${altResp.code}: $responseBody")
                                throw Exception("API Error ${altResp.code}: ${altResp.message}")
                            }
                            // parse below using the same logic
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("AIClient", "GAPGPT /responses fallback failed: ${e.message}")
                        // If /responses also fails, try a minimal chat payload (single user message)
                        try {
                            android.util.Log.d("AIClient", "GAPGPT: trying minimal single-user-message payload as diagnostic/retry")
                            val lastUserContent = messages.reversed().firstOrNull { it.role == com.persianai.assistant.models.MessageRole.USER }?.content
                                ?: messages.lastOrNull()?.content ?: ""
                            val minimalMessages = listOf(mapOf("role" to "user", "content" to lastUserContent))
                            val minimalBody = mapOf(
                                "model" to model.modelId,
                                "messages" to minimalMessages,
                                "temperature" to 0.0
                            )
                            val minimalJson = gson.toJson(minimalBody)
                            val minimalRequest = Request.Builder()
                                .url(apiUrl)
                                .addHeader("Content-Type", "application/json")
                                .addHeader("Authorization", "Bearer $cleanKey")
                                .post(minimalJson.toRequestBody(mediaType))
                                .build()

                            client.newCall(minimalRequest).execute().use { minResp ->
                                responseBody = minResp.body?.string() ?: ""
                                if (!minResp.isSuccessful) {
                                    android.util.Log.e("AIClient", "GAPGPT minimal payload Error ${minResp.code}: $responseBody")
                                    throw Exception("API Error ${minResp.code}: ${minResp.message}")
                                }
                                android.util.Log.d("AIClient", "GAPGPT minimal payload succeeded")
                                // continue to parse responseBody below
                            }
                        } catch (e2: Exception) {
                            android.util.Log.e("AIClient", "GAPGPT minimal fallback failed: ${e2.message}")
                            throw Exception("API Error ${response.code}: ${response.message}")
                        }
                    }
                }

                throw Exception("API Error ${response.code}: ${response.message}")
            }

            if (responseBody.isBlank()) {
                throw Exception("پاسخ خالی از API")
            }

            try {
                val json = gson.fromJson(responseBody, JsonObject::class.java)

                // Try multiple common response shapes
                // 1) choices[0].message.content (chat/completions)
                try {
                    val choices = json.getAsJsonArray("choices")
                    if (choices != null && choices.size() > 0) {
                        val choice = choices[0].asJsonObject
                        val message = choice.getAsJsonObject("message")
                        val content = message?.get("content")?.asString ?: choice.get("text")?.asString
                        if (!content.isNullOrBlank()) {
                            return@withContext ChatMessage(
                                role = MessageRole.ASSISTANT,
                                content = content,
                                timestamp = System.currentTimeMillis()
                            )
                        }
                    }
                } catch (_: Exception) {}

                // 2) output / candidates / data structures (responses API)
                try {
                    // responses API may have 'output' array or 'candidates'
                    val outputArr = json.getAsJsonArray("output") ?: json.getAsJsonArray("candidates")
                    if (outputArr != null && outputArr.size() > 0) {
                        val first = outputArr[0].asJsonObject
                        // try nested content structures
                        val parts = first.getAsJsonArray("content")
                        if (parts != null && parts.size() > 0) {
                            val part0 = parts[0].asJsonObject
                            val text = part0.get("text")?.asString ?: part0.get("content")?.asString
                            if (!text.isNullOrBlank()) return@withContext ChatMessage(
                                role = MessageRole.ASSISTANT,
                                content = text,
                                timestamp = System.currentTimeMillis()
                            )
                        }
                        val candidateText = first.get("text")?.asString
                        if (!candidateText.isNullOrBlank()) return@withContext ChatMessage(
                            role = MessageRole.ASSISTANT,
                            content = candidateText,
                            timestamp = System.currentTimeMillis()
                        )
                    }
                } catch (_: Exception) {}

                // 3) generic fields
                val alt = json.get("response")?.asString ?: json.get("text")?.asString ?: json.get("content")?.asString
                if (!alt.isNullOrBlank()) return@withContext ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = alt,
                    timestamp = System.currentTimeMillis()
                )

                android.util.Log.e("AIClient", "Full API response: $responseBody")
                throw Exception("پاسخ خالی از API")
            } catch (e: Exception) {
                android.util.Log.e("AIClient", "Parse error: ${e.message}, response: $responseBody")
                throw Exception("خطا در پردازش پاسخ API: ${e.message}")
            }
        }
    }

    /**
     * ارسال به Claude (Anthropic)
     */
    private suspend fun sendToClaude(
        model: AIModel,
        messages: List<ChatMessage>,
        systemPrompt: String?,
        apiKey: APIKey
    ): ChatMessage = withContext(Dispatchers.IO) {
        
        val baseUrl = apiKey.baseUrl?.trim()?.trimEnd('/')
        val apiUrl = baseUrl?.let { "$it/messages" }
            ?: "https://api.anthropic.com/v1/messages"

        val messageList = mutableListOf<Map<String, String>>()
        
        messages.forEach { msg ->
            messageList.add(mapOf(
                "role" to when(msg.role) {
                    MessageRole.USER -> "user"
                    MessageRole.ASSISTANT -> "assistant"
                    MessageRole.SYSTEM -> "user" // Claude doesn't support system role in messages
                },
                "content" to msg.content
            ))
        }

        val requestBody = mapOf(
            "model" to model.modelId,
            "max_tokens" to 500,
            "messages" to messageList
        ).let { body ->
            if (systemPrompt != null) {
                body + ("system" to systemPrompt)
            } else {
                body
            }
        }

        val jsonBody = gson.toJson(requestBody)
        val body = jsonBody.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(apiUrl)
            .addHeader("Content-Type", "application/json")
            .addHeader("x-api-key", apiKey.key)
            .addHeader("anthropic-version", "2023-06-01")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                android.util.Log.e("AIClient", "Claude API Error ${response.code}: $responseBody")
                throw Exception("Claude API Error ${response.code}: ${response.message}")
            }

            if (responseBody.isBlank()) {
                throw Exception("پاسخ خالی از Claude API")
            }

            try {
                val json = gson.fromJson(responseBody, JsonObject::class.java)
                val content = json.getAsJsonArray("content")
                if (content != null && content.size() > 0) {
                    val textBlock = content[0].asJsonObject
                    val text = textBlock.get("text")?.asString
                    if (!text.isNullOrBlank()) {
                        return@withContext ChatMessage(
                            role = MessageRole.ASSISTANT,
                            content = text,
                            timestamp = System.currentTimeMillis()
                        )
                    }
                }
                throw Exception("پاسخ خالی از Claude API")
            } catch (e: Exception) {
                android.util.Log.e("AIClient", "Claude Parse error: ${e.message}")
                throw Exception("خطا در پردازش پاسخ Claude API: ${e.message}")
            }
        }
    }

    /**
     * تبدیل صوت به متن با Whisper API
     * ✅ تلاش با کلیدهای فعال به ترتیب GAPGPT → LIARA → OPENAI
     */
    suspend fun transcribeAudio(audioFilePath: String): String = withContext(Dispatchers.IO) {
        // فایل
        val file = java.io.File(audioFilePath)
        if (!file.exists()) return@withContext ""

        val keysOrdered = apiKeys
            .filter { it.isActive && it.key.isNotBlank() }
            .sortedBy { k ->
                when (k.provider) {
                    AIProvider.GAPGPT -> 0
                    AIProvider.LIARA -> 1
                    AIProvider.OPENAI -> 2
                    else -> 99
                }
            }

        for (apiKey in keysOrdered) {
            try {
                android.util.Log.d("AIClient", "🎤 تلاش برای transcribe با ${apiKey.provider.name} key: ${apiKey.key.take(8)}...")
                val result = when (apiKey.provider) {
                    AIProvider.GAPGPT, AIProvider.LIARA, AIProvider.OPENAI -> callWhisperLike(apiKey, file)
                    else -> continue
                }
                if (result.isNotBlank()) return@withContext result
            } catch (e: Exception) {
                android.util.Log.w("AIClient", "❌ Transcribe با ${apiKey.provider.name} ناموفق: ${e.message}")
            }
        }

        ""
    }

    private fun callWhisperLike(apiKey: APIKey, file: java.io.File): String {
        val url = when (apiKey.provider) {
            AIProvider.GAPGPT -> "https://api.gapgpt.app/v1/audio/transcriptions"
            AIProvider.LIARA -> "https://ai.liara.ir/api/69467b6ba99a2016cac892e1/v1/audio/transcriptions"
            AIProvider.OPENAI -> "https://api.openai.com/v1/audio/transcriptions"
            else -> return ""
        }

        val requestBody = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("audio/*".toMediaType()))
            .addFormDataPart("model", "whisper-1")
            .build()

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${apiKey.key}")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()
            if (!response.isSuccessful) {
                android.util.Log.e("AIClient", "Whisper API Error ${response.code}: $responseBody")
                return ""
            }
            if (responseBody.isNullOrBlank()) return ""
            
            return try {
                val json = gson.fromJson(responseBody, JsonObject::class.java)
                json.get("text")?.asString ?: responseBody
            } catch (_: Exception) {
                responseBody
            }
        }
    }

    /**
     * دریافت baseUrl پیش‌فرض برای provider‌های مختلف
     */
    private fun getDefaultBaseUrl(provider: AIProvider): String {
        return when (provider) {
            AIProvider.OPENAI -> "https://api.openai.com/v1"
            AIProvider.LIARA -> "https://ai.liara.ir/api/69467b6ba99a2016cac892e1/v1"
            AIProvider.GAPGPT -> "https://api.gapgpt.app/v1"
            AIProvider.AVALAI -> "https://avalai.ir/api/v1"
            AIProvider.OPENROUTER -> "https://openrouter.ai/api/v1"
            AIProvider.ANTHROPIC -> "https://api.anthropic.com/v1"
            AIProvider.AIML -> "https://api.aiml.io/v1"
            AIProvider.GLADIA -> "https://api.gladia.io/v2"
            AIProvider.IVIRA -> "https://api.ivira.ai/v1"
            AIProvider.CUSTOM -> "https://api.example.com/v1" // باید از remote config بیاید
            AIProvider.LOCAL -> "http://localhost:8080" // مدل‌های محلی
            else -> "https://api.openai.com/v1"
        }
    }

}
