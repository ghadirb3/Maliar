package com.persianai.assistant.chat

import android.content.Context
import android.util.Log
import com.persianai.assistant.config.APIKeyConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SimpleGapGPTChatService(private val context: Context) {
    
    companion object {
        private const val TAG = "SimpleGapGPTChat"
        private const val BASE_URL = "https://api.gapgpt.app/v1"
        private const val CHAT_ENDPOINT = "$BASE_URL/chat/completions"
        
        private val MODEL_PRIORITY = listOf(
            "gpt-5.3-chat-latest",
            "gpt-5-nano",
            "gpt-4o-mini"
        )
    }
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    suspend fun sendMessage(userMessage: String): ChatResponse = withContext(Dispatchers.IO) {
        val apiKeys = APIKeyConfig.getAPIKeys(context)
        val gapgptKey = apiKeys.firstOrNull { key -> key.provider.name == "GAPGPT" && key.isActive }
        
        if (gapgptKey == null) {
            Log.e(TAG, "No active GAPGPT API key found")
            return@withContext ChatResponse(success = false, error = "No GAPGPT API key")
        }
        
        for (model in MODEL_PRIORITY) {
            try {
                val response = tryModel(model, userMessage, gapgptKey.key)
                if (response.success) {
                    return@withContext response
                }
                Log.w(TAG, "Model $model failed: ${response.error}")
            } catch (e: Exception) {
                Log.e(TAG, "Error with model $model", e)
            }
        }
        
        ChatResponse(success = false, error = "All models failed")
    }
    
    private fun tryModel(model: String, message: String, apiKey: String): ChatResponse {
        val messagesArray = JSONArray()
        val messageObj = JSONObject()
        messageObj.put("role", "user")
        messageObj.put("content", message)
        messagesArray.put(messageObj)
        
        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", messagesArray)
        }.toString().toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url(CHAT_ENDPOINT)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()
        
        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""
        
        if (!response.isSuccessful) {
            return ChatResponse(success = false, error = "HTTP ${response.code}: $responseBody")
        }
        
        val json = JSONObject(responseBody)
        val choices = json.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val firstChoice = choices.getJSONObject(0)
            val messageObj = firstChoice.optJSONObject("message")
            val content = messageObj?.optString("content", "") ?: ""
            return ChatResponse(success = true, content = content, model = model)
        }
        
        ChatResponse(success = false, error = "No content in response")
    }
}

data class ChatMessage(
    val role: String,
    val content: String
)

data class ChatResponse(
    val success: Boolean,
    val content: String = "",
    val error: String = "",
    val model: String = ""
)
