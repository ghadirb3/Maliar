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
            it.provider == model.provider && it.isActive && it.key.isNotBlank() && !failedKeys.contains(it.key)
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
                // Remove from failed keys on success
                failedKeys.remove(apiKey.key)
                return@withContext result
            } catch (e: Exception) {
                lastError = e
                val errorMsg = e.message ?: ""
                android.util.Log.w("AIClient", "❌ Key failed for ${model.provider.name}: ${formatExceptionForLog(e)}")
                
                // GAPGPT fallback disabled - only use specified models (gpt-5-nano, gapgpt-deepseek-v3)
                // Per user request: do not fall back to gpt-4o-mini
                
                // Disable problematic key temporarily
                failedKeys.add(apiKey.key)
            }
        }

        android.util.Log.e("AIClient", "❌ All keys failed: ${lastError?.message}")
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

        val requestBody = when (model.provider) {
            AIProvider.GAPGPT -> {
                // GAPGPT request - explicitly disable streaming
                mapOf(
                    "model" to model.modelId,
                    "messages" to messageList,
                    "temperature" to 0.0,
                    "stream" to false
                )
            }
            else -> {
                // Standard request for other providers
                ChatRequest(
                    model = model.modelId,
                    messages = messageList,
                    temperature = 0.0,  // صفر برای خروجی کاملاً قطعی
                    maxTokens = 500     // کوتاه برای JSON
                )
            }
        }

        val jsonBody = gson.toJson(requestBody)
        val body = jsonBody.toRequestBody(mediaType)

        // Log request details for debugging
        val requestId = "req_${System.currentTimeMillis()}"
        android.util.Log.d("AIClient", "[$requestId] Sending to ${model.provider.name}: url=$apiUrl, model=${model.modelId}")
        
        // Log full request for GAPGPT debugging
        if (model.provider == AIProvider.GAPGPT) {
            android.util.Log.d("AIClient", "[$requestId] GAPGPT request body: $jsonBody")
        }

        val requestBuilder = Request.Builder()
            .url(apiUrl)
            .addHeader("Authorization", "Bearer ${apiKey.key}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
        if (apiKey.provider == AIProvider.OPENROUTER) {
            // OpenRouter نیاز به Referer و X-Title دارد
            requestBuilder.addHeader("HTTP-Referer", "https://openrouter.ai/")
            requestBuilder.addHeader("X-Title", "Persian AI Assistant")
        }
        val request = requestBuilder.post(body).build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()
            val responseSnippet = responseBody?.take(200) ?: "(empty)"
            
            android.util.Log.d("AIClient", "[$requestId] Response: code=${response.code}, success=${response.isSuccessful}, body=$responseSnippet")
            
            if (!response.isSuccessful) {
                val errorDetail = "خطای API: ${response.code} - $responseSnippet"
                android.util.Log.e("AIClient", "[$requestId] API Error: $errorDetail")
                throw Exception(errorDetail)
            }

            // Check for empty response body before parsing
            if (responseBody.isNullOrBlank()) {
                android.util.Log.e("AIClient", "[$requestId] Empty response body from ${model.provider.name}")
                throw Exception("پاسخ خالی از API ${model.provider.name}")
            }

            // Log raw response for GAPGPT debugging
            if (model.provider == AIProvider.GAPGPT) {
                android.util.Log.d("AIClient", "[$requestId] Raw GAPGPT response: $responseBody")
            }

            try {
                // Handle streaming response for GAPGPT
                if (model.provider == AIProvider.GAPGPT && responseBody.contains("data:")) {
                    return parseGAPGPTStream(responseBody, requestId)
                }

                val chatResponse = gson.fromJson(responseBody, ChatResponse::class.java)
                    ?: throw Exception("Failed to parse response as ChatResponse")
                
                // Try multiple response formats
                val content = when {
                    // Standard OpenAI format
                    !chatResponse.choices.isNullOrEmpty() -> {
                        val choice = chatResponse.choices.firstOrNull()
                        choice?.message?.content 
                            ?: choice?.text 
                            ?: choice?.content
                    }
                    // Alternative direct response field
                    !chatResponse.response.isNullOrBlank() -> chatResponse.response
                    // Alternative text field
                    !chatResponse.text.isNullOrBlank() -> chatResponse.text
                    // Alternative content field
                    !chatResponse.content.isNullOrBlank() -> chatResponse.content
                    // Last resort: try to parse as plain text or different JSON structure
                    else -> {
                        try {
                            val json = gson.fromJson(responseBody, JsonObject::class.java)
                            json.get("response")?.asString 
                                ?: json.get("text")?.asString
                                ?: json.get("content")?.asString
                                ?: json.get("message")?.asString
                                ?: json.get("answer")?.asString
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
                
                if (!content.isNullOrBlank()) {
                    android.util.Log.d("AIClient", "[$requestId] Success: content length=${content.length}")
                    ChatMessage(
                        role = MessageRole.ASSISTANT,
                        content = content,
                        timestamp = System.currentTimeMillis()
                    )
                } else {
                    throw Exception("پاسخ خالی از API")
                }
            } catch (e: Exception) {
                android.util.Log.e("AIClient", "[$requestId] Parse error: ${e.message}, response was: $responseSnippet")
                // Log full response for debugging GAPGPT format issues
                if (model.provider == AIProvider.GAPGPT) {
                    android.util.Log.e("AIClient", "[$requestId] Full GAPGPT response: $responseBody")
                }
                throw Exception("خطا در پردازش پاسخ API: ${e.message}")
            }
        }
    }

    /**
     * Parse GAPGPT streaming response
     */
    private fun parseGAPGPTStream(responseBody: String, requestId: String): ChatMessage {
        val contentBuilder = StringBuilder()
        
        responseBody.lines().forEach { line ->
            line.trim().takeIf { it.startsWith("data:") }?.let { dataLine ->
                val jsonStr = dataLine.removePrefix("data:").trim()
                if (jsonStr == "[DONE]") return@forEach
                
                try {
                    val json = gson.fromJson(jsonStr, JsonObject::class.java)
                    val choices = json.getAsJsonArray("choices")
                    if (choices != null && choices.size() > 0) {
                        val choice = choices[0].asJsonObject
                        val delta = choice.getAsJsonObject("delta")
                        if (delta != null) {
                            val content = delta.get("content")?.asString
                            if (!content.isNullOrBlank()) {
                                contentBuilder.append(content)
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("AIClient", "[$requestId] Failed to parse stream chunk: $jsonStr", e)
                }
            }
        }
        
        val finalContent = contentBuilder.toString()
        if (finalContent.isBlank()) {
            throw Exception("No content extracted from GAPGPT stream")
        }
        
        android.util.Log.d("AIClient", "[$requestId] GAPGPT stream parsed successfully: ${finalContent.length} chars")
        return ChatMessage(
            role = MessageRole.ASSISTANT,
            content = finalContent,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun callWhisperLike(url: String, key: String, body: okhttp3.MultipartBody): String {
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $key")
            .post(body)
            .build()
        client.newCall(request).execute().use { resp ->
            val respBody = resp.body?.string()
            if (!resp.isSuccessful) {
                android.util.Log.e("AIClient", "Whisper-like error ${resp.code}: $respBody")
                return ""
            }
            if (respBody.isNullOrBlank()) return ""
            return try {
                val json = gson.fromJson(respBody, JsonObject::class.java)
                json.get("text")?.asString ?: json.get("generated_text")?.asString ?: respBody
            } catch (_: Exception) {
                respBody
            }
        }
    }

    private fun callHuggingFaceWhisper(url: String, key: String, body: okhttp3.MultipartBody): String {
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Accept", "application/json")
            .post(body)
            .build()
        client.newCall(request).execute().use { resp ->
            val respBody = resp.body?.string()
            if (!resp.isSuccessful) {
                android.util.Log.e("AIClient", "HF Whisper error ${resp.code}: $respBody")
                return ""
            }
            if (respBody.isNullOrBlank()) return ""
            return try {
                val json = gson.fromJson(respBody, JsonObject::class.java)
                json.get("text")?.asString ?: json.get("generated_text")?.asString ?: respBody
            } catch (_: Exception) {
                respBody
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
        
        val apiUrl = "https://api.anthropic.com/v1/messages"

        val messageList = messages.map { msg ->
            mapOf(
                "role" to when(msg.role) {
                    MessageRole.USER -> "user"
                    MessageRole.ASSISTANT -> "assistant"
                    else -> "user"
                },
                "content" to msg.content
            )
        }

        val jsonObject = JsonObject().apply {
            addProperty("model", model.modelId)
            add("messages", gson.toJsonTree(messageList))
            addProperty("max_tokens", 4096)
            if (systemPrompt != null) {
                addProperty("system", systemPrompt)
            }
        }

        val body = gson.toJson(jsonObject).toRequestBody(mediaType)

        val request = Request.Builder()
            .url(apiUrl)
            .addHeader("x-api-key", apiKey.key)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()
            
            if (!response.isSuccessful) {
                throw Exception("خطای Claude API: ${response.code} - $responseBody")
            }

            val claudeResponse = gson.fromJson(responseBody, ClaudeResponse::class.java)
            val content = claudeResponse.content.firstOrNull()?.text
                ?: throw Exception("پاسخ خالی از Claude")

            ChatMessage(
                role = MessageRole.ASSISTANT,
                content = content,
                timestamp = System.currentTimeMillis()
            )
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
                    AIProvider.CUSTOM -> 3
                    else -> 4
                }
            }
            .filter { it.provider == AIProvider.GAPGPT || it.provider == AIProvider.LIARA || it.provider == AIProvider.OPENAI }

        if (keysOrdered.isEmpty()) {
            android.util.Log.w("AIClient", "No GAPGPT/LIARA/OPENAI key for STT")
            return@withContext ""
        }

        val mediaTypeStr = when (file.extension.lowercase()) {
            "m4a", "mp4" -> "audio/mp4"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "webm" -> "audio/webm"
            "mp3" -> "audio/mpeg"
            else -> "application/octet-stream"
        }
        val mediaTypeAudio = mediaTypeStr.toMediaType()

        fun buildBody(): okhttp3.MultipartBody {
            return okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("file", file.name, okhttp3.RequestBody.create(mediaTypeAudio, file))
                .addFormDataPart("model", "whisper-1")
                .addFormDataPart("language", "fa")
                .build()
        }

        for (k in keysOrdered) {
            try {
                val baseUrl = when (k.provider) {
                    AIProvider.GAPGPT -> k.baseUrl?.trim()?.trimEnd('/') ?: "https://api.gapgpt.app/v1"
                    AIProvider.LIARA -> k.baseUrl?.trim()?.trimEnd('/') ?: "https://ai.liara.ir/api/69467b6ba99a2016cac892e1/v1"
                    AIProvider.OPENAI -> k.baseUrl?.trim()?.trimEnd('/') ?: "https://api.openai.com/v1"
                    AIProvider.CUSTOM -> k.baseUrl?.trim()?.trimEnd('/') ?: "https://api.example.com/v1"
                    else -> k.baseUrl?.trim()?.trimEnd('/') ?: "https://api.openai.com/v1"
                }
                val url = "$baseUrl/audio/transcriptions"
                val body = buildBody()
                val text = withTimeout(20000) {
                    android.util.Log.d("AIClient", "transcribeAudio using ${k.provider.name} at $url")
                    callWhisperLike(url, k.key, body)
                }.trim()
                if (text.isNotBlank()) return@withContext text
            } catch (e: Exception) {
                android.util.Log.w("AIClient", "${k.provider.name} STT failed: ${e.message}")
            }
        }

        return@withContext ""
    }

    /**
     * AIML async STT دو مرحله‌ای: stt/create سپس polling روی stt/{id}
     */
    private suspend fun callAimlSttAsync(
        baseUrl: String,
        key: String,
        mediaTypeStr: String,
        file: java.io.File
    ): String {
        val createUrl = "$baseUrl/stt/create"
        val body = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart(
                "file",
                file.name,
                okhttp3.RequestBody.Companion.create(
                    mediaTypeStr.toMediaType(),
                    file
                )
            )
            .addFormDataPart("model", "#g1_whisper-small")
            .addFormDataPart("language", "fa")
            .build()

        val createReq = Request.Builder()
            .url(createUrl)
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Accept", "application/json")
            .post(body)
            .build()

        val generationId = try {
            client.newCall(createReq).execute().use { resp ->
                val respBody = resp.body?.string()
                if (!resp.isSuccessful) {
                    android.util.Log.e("AIClient", "AIML stt/create error ${resp.code}: $respBody")
                    return ""
                }
                val json = gson.fromJson(respBody, JsonObject::class.java)
                json.get("generation_id")?.asString ?: ""
            }
        } catch (e: Exception) {
            android.util.Log.e("AIClient", "AIML stt/create exception: ${e.message}", e)
            return ""
        }

        if (generationId.isBlank()) return ""

        val pollUrl = "$baseUrl/stt/$generationId"
        repeat(5) { _ ->
            val pollReq = Request.Builder()
                .url(pollUrl)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Accept", "application/json")
                .get()
                .build()

            try {
                client.newCall(pollReq).execute().use { resp ->
                    val respBody = resp.body?.string()
                    if (!resp.isSuccessful) {
                        android.util.Log.e("AIClient", "AIML stt poll error ${resp.code}: $respBody")
                        return ""
                    }
                    if (respBody.isNullOrBlank()) return ""
                    val json = gson.fromJson(respBody, JsonObject::class.java)
                    val status = json.get("status")?.asString ?: ""
                    if (status.equals("waiting", true) || status.equals("active", true)) {
                        // keep polling
                    } else {
                        val result = json.getAsJsonObject("result")
                        val transcript = result
                            ?.getAsJsonObject("results")
                            ?.getAsJsonArray("channels")
                            ?.firstOrNull()
                            ?.asJsonObject
                            ?.getAsJsonArray("alternatives")
                            ?.firstOrNull()
                            ?.asJsonObject
                            ?.get("transcript")
                            ?.asString
                        if (!transcript.isNullOrBlank()) return transcript
                        return respBody
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AIClient", "AIML stt poll exception: ${e.message}", e)
                return ""
            }

            delay(1500)
        }

        return ""
    }

    private fun callHuggingFaceRaw(token: String, mediaTypeStr: String, file: java.io.File): String {
        val url = "https://router.huggingface.co/models/openai/whisper-large-v3?wait_for_model=true"
        val body = file.readBytes().toRequestBody(mediaTypeStr.toMediaType())
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/json")
            .post(body)
            .build()

        client.newCall(request).execute().use { resp ->
            val respBody = resp.body?.string()
            if (!resp.isSuccessful) {
                android.util.Log.e("AIClient", "HF STT (raw) error: ${resp.code} - $respBody")
                return ""
            }
            if (respBody.isNullOrBlank()) return ""
            if (respBody.startsWith("<!doctype", true)) {
                android.util.Log.e("AIClient", "HF STT returned HTML (blocked)")
                return ""
            }
            return try {
                val json = gson.fromJson(respBody, JsonObject::class.java)
                json.get("text")?.asString ?: json.get("generated_text")?.asString ?: respBody
            } catch (_: Exception) {
                respBody
            }
        }
    }

    private fun callGladiaTranscribe(url: String, key: String, body: okhttp3.MultipartBody): String {
        val request = Request.Builder()
            .url(url)
            .addHeader("x-gladia-key", key)
            .addHeader("Accept", "application/json")
            .post(body)
            .build()

        client.newCall(request).execute().use { resp ->
            val respBody = resp.body?.string()
            if (!resp.isSuccessful) {
                android.util.Log.e("AIClient", "Gladia STT error ${resp.code}: $respBody")
                return ""
            }
            if (respBody.isNullOrBlank()) return ""
            return try {
                val json = gson.fromJson(respBody, JsonObject::class.java)
                // Prefer common fields
                json.get("text")?.asString
                    ?: json.get("transcription")?.asString
                    ?: json.get("result")?.asJsonObject?.get("transcription")?.asString
                    ?: respBody
            } catch (_: Exception) {
                respBody
            }
        }
    }
    
    /**
     * ارسال پیام به مدل داینامیک با base_url سفارشی
     */
    suspend fun sendDynamicMessage(
        modelId: String,
        provider: AIProvider,
        baseUrl: String?,
        messages: List<ChatMessage>,
        systemPrompt: String? = null
    ): ChatMessage = withContext(Dispatchers.IO) {
        
        // پیدا کردن کلید API مناسب برای این provider
        val apiKey = apiKeys.find { it.isActive && it.provider == provider }
            ?: throw IllegalStateException("No active API key found for provider: $provider")
        
        // استفاده از baseUrl سفارشی یا baseUrl از کلید API
        val finalBaseUrl = baseUrl ?: apiKey.baseUrl ?: getDefaultBaseUrl(provider)
        
        // ساخت درخواست
        val requestMessages = mutableListOf<Map<String, String>>()
        systemPrompt?.let {
            requestMessages.add(mapOf("role" to "system", "content" to it))
        }
        requestMessages.addAll(messages.map { 
            mapOf("role" to it.role.name.lowercase(), "content" to it.content) 
        })
        
        val requestBody = ChatRequest(
            model = modelId,
            messages = requestMessages,
            temperature = 0.7,
            maxTokens = 4096,
            stream = false
        )
        
        val jsonBody = gson.toJson(requestBody)
        android.util.Log.d("AIClient", "Sending dynamic request to $finalBaseUrl with model: $modelId")
        
        val request = Request.Builder()
            .url("$finalBaseUrl/chat/completions")
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer ${apiKey.key}")
            .post(jsonBody.toRequestBody(mediaType))
            .build()
        
        try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                
                if (!response.isSuccessful || responseBody.isNullOrBlank()) {
                    throw Exception("HTTP ${response.code}: ${response.message}")
                }
                
                val chatResponse = gson.fromJson(responseBody, ChatResponse::class.java)
                val choice = chatResponse.choices?.firstOrNull()
                    ?: throw Exception("No choices in response")
                
                ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = choice.message?.content 
                        ?: choice.text 
                        ?: choice.content
                        ?: throw Exception("Empty content in choice"),
                    timestamp = System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("AIClient", "Dynamic model request failed", e)
            ChatMessage(
                role = MessageRole.ASSISTANT,
                content = "خطا: ${e.message}",
                timestamp = System.currentTimeMillis(),
                isError = true
            )
        }
    }
    
    /**
     * دریافت baseUrl پیش‌فرض برای provider‌های مختلف
     */
    private fun getDefaultBaseUrl(provider: AIProvider): String {
        return when (provider) {
            AIProvider.OPENAI -> "https://api.openai.com/v1"
            AIProvider.LIARA -> "https://ai.liara.ir/v1"
            AIProvider.GAPGPT -> "https://api.gapgpt.app/v1"
            AIProvider.AVALAI -> "https://api.avalai.ir/v1"
            AIProvider.OPENROUTER -> "https://openrouter.ai/api/v1"
            AIProvider.ANTHROPIC -> "https://api.anthropic.com/v1"
            AIProvider.AIML -> "https://api.aiml.io/v1"
            AIProvider.GLADIA -> "https://api.gladia.io/v2"
            AIProvider.CUSTOM -> "https://api.example.com/v1" // باید از remote config بیاید
            AIProvider.LOCAL -> "http://localhost:8080" // مدل‌های محلی
            else -> "https://api.openai.com/v1"
        }
    }

}
