package com.persianai.assistant.core

import android.content.Context
import android.util.Log
import com.persianai.assistant.ai.AIClient
import com.persianai.assistant.ai.AdvancedPersianAssistant
import com.persianai.assistant.ai.SimpleOfflineResponder
import com.persianai.assistant.api.IviraAPIClient
import com.persianai.assistant.models.APIKey
import com.persianai.assistant.models.AIModel
import com.persianai.assistant.models.AIProvider
import com.persianai.assistant.models.ChatMessage
import com.persianai.assistant.models.MessageRole
import com.persianai.assistant.utils.PreferencesManager
import com.persianai.assistant.utils.ModelSelector
import com.persianai.assistant.utils.IviraTokenManager
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Query Router مرکزی
 * 1) اگر اکشن قابل اجرا باشد آن را انجام می‌دهد
 * 2) در حالت HYBRID/ONLINE اول آنلاین را امتحان می‌کند
 * 3) در صورت شکست یا حالت OFFLINE به آفلاین برمی‌گردد
 */
class QueryRouter(private val context: Context) {

    private val TAG = "QueryRouter"
    private val actionExecutor = ActionExecutor(context)
    private val prefs = PreferencesManager(context)
    private val offlineAssistant = AdvancedPersianAssistant(context)
    // private val modelDownloadManager = ModelDownloadManager(context)
    private val iviraClient = IviraAPIClient(context)
    private val iviraTokenManager = IviraTokenManager(context)

    suspend fun routeQuery(query: String): QueryResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🚀 Routing query: $query")
            val workingMode = prefs.getWorkingMode()
            val apiKeys = prefs.getAPIKeys()
            val activeKeys = apiKeys.filter { it.isActive && !it.key.isNullOrBlank() }

            // 1) اقدام مستقیم (Reminder/Alarm/Note)
            val actionResult = actionExecutor.executeFromQuery(query)
            if (actionResult.success && actionResult.action != null) {
                Log.d(TAG, "✅ Action executed: ${actionResult.action}")
                return@withContext QueryResult(
                    success = true,
                    response = actionResult.message,
                    source = "action",
                    actionExecuted = true,
                    model = null
                )
            }

            // 2) Ivira disabled for text chat - skip to online models per user request
            // val iviraResult = tryIviraOnline(query)
            // if (iviraResult != null) return@withContext iviraResult

            // 3) حالت آفلاین یا بدون کلید → فقط پاسخ ساده
            if (workingMode == PreferencesManager.WorkingMode.OFFLINE || activeKeys.isEmpty()) {
                val offline = offlineAssistant.processRequest(query)
                val response = resolveOfflineResponse(query, offline, workingMode)
                return@withContext QueryResult(
                    success = true,
                    response = response,
                    source = "offline",
                    actionExecuted = false,
                    model = "offline-assistant"
                )
            }

            // 4) تلاش آنلاین (online-first برای HYBRID/ONLINE)
            val onlineResult = tryOnlineModel(query, activeKeys)
            if (onlineResult != null) {
                return@withContext QueryResult(
                    success = true,
                    response = onlineResult.response,
                    source = "online",
                    actionExecuted = false,
                    model = onlineResult.model
                )
            }

            // 5) بازگشت به آفلاین در صورت شکست آنلاین: ابتدا GGUF محلی سپس پاسخ ساده
            Log.w(TAG, "❌ Online model failed, falling back to offline assistant")
            val local = tryLocalModel(query)
            if (local != null) {
                return@withContext QueryResult(
                    success = true,
                    response = local,
                    source = "offline",
                    actionExecuted = false,
                    model = "local-gguf"
                )
            }
            val offline = offlineAssistant.processRequest(query)
            val response = resolveOfflineResponse(query, offline, workingMode)
            return@withContext QueryResult(
                success = true,
                response = response,
                source = "offline",
                actionExecuted = false,
                model = "offline-assistant"
            )

        } catch (e: Exception) {
            Log.e(TAG, "❌ Router error: ${e.message}", e)
            QueryResult(
                success = false,
                response = "خطا: ${e.message}",
                source = "error",
                actionExecuted = false,
                model = null,
                exception = e
            )
        }
    }

    /**
     * تلاش با توکن‌های Ivira (اولویت: Vira → GPT5 Mini → GPT5 Nano → Gemma3-27B)
     */
    private suspend fun tryIviraOnline(query: String): QueryResult? {
        return try {
            if (!iviraTokenManager.hasTokens()) {
                Log.d(TAG, "Ivira tokens not available")
                return null
            }
            val deferred = CompletableDeferred<String>()
            iviraClient.sendMessage(
                message = query,
                model = null,
                onResponse = { deferred.complete(it) },
                onError = { deferred.completeExceptionally(Exception(it)) }
            )
            val responseText = deferred.await()
            Log.d(TAG, "✅ Ivira response received")
            QueryResult(
                success = true,
                response = responseText,
                source = "online",
                actionExecuted = false,
                model = "Ivira"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Ivira online failed: ${e.message}")
            null
        }
    }

    /**
     * Try running downloaded GGUF model via local_llama (if native backend is present).
     */
    private suspend fun tryLocalModel(query: String): String? {
        // Local LLM disabled - always return null
        return null
        /*
        return try {
            if (!localLlama.isAvailable()) {
                Log.w(TAG, "Local llama backend not available (native stub or missing build)")
                return null
            }
            val modelFile = findOfflineModelPath() ?: run {
                Log.w(TAG, "No downloaded offline model found")
                return null
            }
            val res = localLlama.infer(query, modelFile.absolutePath, maxTokens = 256).getOrNull()
            if (res.isNullOrBlank()) {
                Log.w(TAG, "Local model returned empty response")
                null
            } else {
                Log.d(TAG, "✅ Local GGUF response obtained")
                res
            }
        } catch (e: Exception) {
            Log.w(TAG, "Local model inference failed: ${e.message}")
            null
        }
        */
    }

    /**
     * Locate downloaded GGUF model based on user selection or any available model.
     */
    private fun findOfflineModelPath(): File? {
        // Offline models disabled - always return null
        return null
        /*
        return try {
            val preferred = prefs.getOfflineModelType()
            val info = modelDownloadManager.findDownloadedModel(preferred)
                ?: modelDownloadManager.findDownloadedModel()
            if (info != null) {
                val f = modelDownloadManager.getModelFile(info)
                if (f.exists()) f else null
            } else null
        } catch (_: Exception) {
            null
        }
        */
    }

    private suspend fun tryOnlineModel(query: String, activeKeys: List<APIKey>): OnlineResult? {
        return try {
            if (activeKeys.isEmpty()) {
                Log.w(TAG, "⚠️ No active API keys")
                return null
            }

            val aiClient = AIClient(context, activeKeys)
            val messages = listOf(ChatMessage(role = MessageRole.USER, content = query))

            val activeProviders = activeKeys.map { it.provider }.toSet()

            // Fixed priority: Liara -> GAPGPT (gpt-5-nano) -> GAPGPT (gpt-4o-mini) -> GAPGPT (deepseek-chat) -> GAPGPT (grok-3-mini) -> GAPGPT (gemini-2.0-flash) -> GAPGPT (gpt-5.2-chat-latest) -> GAPGPT (gapgpt-deepseek-v3)
            val preferredOrder = mutableListOf<AIModel>()
            
            if (activeProviders.contains(AIProvider.LIARA)) {
                preferredOrder += AIModel.LIARA_GPT_5_NANO
            }
            if (activeProviders.contains(AIProvider.GAPGPT)) {
                preferredOrder += AIModel.GAPGPT_GPT_5_NANO
                preferredOrder += AIModel.GAPGPT_GPT_4O_MINI_TEST
                preferredOrder += AIModel.GAPGPT_DEEPSEEK_CHAT
                preferredOrder += AIModel.GAPGPT_GROK_3_MINI
                preferredOrder += AIModel.GAPGPT_GEMINI_2_FLASH
                preferredOrder += AIModel.GAPGPT_GPT_5_2_CHAT_LATEST
                preferredOrder += AIModel.GAPGPT_DEEPSEEK_V3
            }
            
            if (preferredOrder.isEmpty()) {
                Log.w(TAG, "No Liara/GAPGPT provider active, skipping online in QueryRouter")
                return null
            }

            // Allow retries for GAPGPT fallback
            var attempts = 0
            
            for (model in preferredOrder) {
                try {
                    Log.d(TAG, "🌐 Trying online model: ${model.displayName} (${model.modelId})")
                    val response = aiClient.sendMessage(model, messages)
                    Log.d(TAG, "✅ Got online response from ${model.displayName}")
                    return OnlineResult(response.content, model.displayName)
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ ${model.displayName} failed: ${e.message}")
                    // Continue to next model (e.g., GAPGPT fallback)
                }
                attempts++
            }

            Log.e(TAG, "❌ All online providers failed (retries exhausted), falling back to offline")
            null
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Online model failed: ${e.message}")
            null
        }
    }

    private fun resolveOfflineResponse(
        query: String,
        offline: AdvancedPersianAssistant.AssistantResponse,
        workingMode: PreferencesManager.WorkingMode
    ): String {
        // FIX: Better fallback chain - try AdvancedPersianAssistant first, then SimpleOfflineResponder
        
        // 1. Check if AdvancedPersianAssistant returned a good response
        if (!offline.text.isBlank() && offline.actionType != AdvancedPersianAssistant.ActionType.NEEDS_AI) {
            Log.d(TAG, "✅ Advanced offline assistant provided response")
            return offline.text
        }
        
        // 2. Fall back to SimpleOfflineResponder for generic responses
        val simpleResponse = SimpleOfflineResponder.respond(context, query)
        if (!simpleResponse.isNullOrBlank()) {
            Log.d(TAG, "✅ Simple offline responder provided fallback response")
            return simpleResponse
        }
        
        // 3. If offline mode and no response available, inform user
        if (workingMode == PreferencesManager.WorkingMode.OFFLINE) {
            return "⚠️ این درخواست نیاز به مدل آنلاین دارد و در حالت آفلاین قابل پاسخ نیست."
        }
        
        // 4. Default offline error message
        return "⚠️ فعلاً پاسخ آفلاین در دسترس نیست."
    }
}

data class QueryResult(
    val success: Boolean,
    val response: String,
    val source: String, // "action", "online", "offline", "none", "error"
    val actionExecuted: Boolean = false,
    val model: String? = null,
    val exception: Exception? = null
)

data class OnlineResult(
    val response: String,
    val model: String
)
