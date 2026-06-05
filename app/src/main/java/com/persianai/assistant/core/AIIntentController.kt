package com.persianai.assistant.core
 
 import android.content.Context
 import android.util.Log
 import com.persianai.assistant.ai.AIClient
import com.persianai.assistant.models.AIModel
import com.persianai.assistant.models.APIKey
import com.persianai.assistant.models.AIProvider
import com.persianai.assistant.models.ChatMessage
import com.persianai.assistant.models.MessageRole
 import com.persianai.assistant.core.intent.*
 import com.persianai.assistant.core.modules.*
 import com.persianai.assistant.utils.PreferencesManager
 import com.persianai.assistant.utils.ModelSelector
 import org.json.JSONObject
 import kotlinx.coroutines.Dispatchers
 import kotlinx.coroutines.withContext

 class AIIntentController(private val context: Context) {

    // ماژول‌ها
    private val assistantModule = AssistantModule(context)
    private val reminderModule = ReminderModule(context)
    private val navigationModule = NavigationModule(context)
    private val financeModule = FinanceModule(context)
    private val educationModule = EducationModule(context)
    private val callModule = CallModule(context)
    private val weatherModule = WeatherModule(context)
    private val musicModule = MusicModule(context)
    
    // تشخیص‌دهنده Intent پیشرفته
    private val detector = EnhancedIntentDetector(context)

    private val prefs by lazy { PreferencesManager(context) }

    suspend fun handle(request: AIIntentRequest): AIIntentResult = withContext(Dispatchers.Default) {
        logIntent(request)
        
        val result = when (val i = request.intent) {
            // Assistant & Chat
            is AssistantChatIntent -> assistantModule.execute(request, i)
            
            // Reminders
            is ReminderCreateIntent -> reminderModule.execute(request, i)
            is ReminderListIntent -> reminderModule.execute(request, i)
            is ReminderDeleteIntent -> reminderModule.execute(request, i)
            is ReminderUpdateIntent -> reminderModule.execute(request, i)
            
            // Navigation
            is NavigationSearchIntent -> navigationModule.execute(request, i)
            is NavigationStartIntent -> navigationModule.execute(request, i)
            
            // Finance
            is FinanceTrackIntent -> financeModule.execute(request, i)
            is FinanceReportIntent -> financeModule.execute(request, i)
            
            // Education
            is EducationAskIntent -> educationModule.execute(request, i)
            is EducationGenerateQuestionIntent -> educationModule.execute(request, i)
            
            // Call
            is CallSmartIntent -> callModule.execute(request, i)
            
            // Weather
            is WeatherCheckIntent -> weatherModule.execute(request, i)
            
            // Music
            is MusicPlayIntent -> musicModule.execute(request, i)
            
            UnknownIntent -> AIIntentResult(
                text = "متوجه منظورت نشدم. لطفاً واضح‌تر بگو.",
                intentName = i.name
            )
        }
        
        result
    }

    fun detectIntentFromText(text: String, context: String? = null): AIIntent {
        val t = text.trim()
        if (t.isBlank()) return UnknownIntent

        return try {
            detector.detectIntent(text)
        } catch (e: Exception) {
            Log.e("AIIntentController", "Error detecting intent", e)
            AssistantChatIntent(rawText = t)
        }
    }

    suspend fun detectIntentFromTextAsync(text: String, context: String? = null): AIIntent = withContext(Dispatchers.Default) {
        val t = text.trim()
        if (t.isBlank()) return@withContext UnknownIntent

        val workingMode = try {
            prefs.getWorkingMode()
        } catch (_: Exception) {
            PreferencesManager.WorkingMode.OFFLINE
        }

        if (workingMode == PreferencesManager.WorkingMode.OFFLINE) {
            return@withContext detectIntentFromText(text, context)
        }

        val keys = try { prefs.getAPIKeys() } catch (_: Exception) { emptyList() }
        if (keys.none { it.isActive }) {
            return@withContext detectIntentFromText(text, context)
        }

        val online = tryDetectOnlineIntent(text = t, context = context, keys = keys)
        online ?: detectIntentFromText(text, context)
    }

    private suspend fun tryDetectOnlineIntent(
        text: String,
        context: String?,
        keys: List<APIKey>
    ): AIIntent? = withContext(Dispatchers.IO) {
        return@withContext try {
            val client = AIClient(this@AIIntentController.context, keys)
            val systemPrompt = buildOnlineIntentSystemPrompt()
            val userPrompt = buildString {
                appendLine("متن کاربر: \"$text\"")
                if (!context.isNullOrBlank()) {
                    appendLine("زمینه/کانتکست: \"$context\"")
                }
            }.trim()

            // انتخاب مدل آنلاین بر اساس اولویت remote ai_config.json و کلیدهای فعال
            val activeKeys = keys.filter { it.isActive && it.key.isNotBlank() }
            val model = ModelSelector.selectBestModel(this@AIIntentController.context, activeKeys)

            Log.d("AIIntentController", "Online intent via model=${model.displayName} provider=${model.provider.name}")

            val resp = client.sendMessage(
                model = model,
                messages = listOf(
                    ChatMessage(role = MessageRole.USER, content = userPrompt)
                ),
                systemPrompt = systemPrompt
            )

            val content = resp.content.trim()
            if (content.isBlank()) return@withContext null

            val jsonStr = extractJsonObject(content) ?: return@withContext null
            val json = JSONObject(jsonStr)
            parseOnlineIntentJson(json, rawText = text)
        } catch (e: Exception) {
            Log.w("AIIntentController", "Online intent detection failed: ${e.message}")
            null
        }
    }

    private fun buildOnlineIntentSystemPrompt(): String {
        return """
            شما موتور تشخیص Intent یک اپ فارسی هستید.
            فقط و فقط یک JSON شیء برگردان و هیچ متن اضافه‌ای ننویس.

            خروجی JSON باید این فیلدها را داشته باشد:
            - intent: یکی از این مقدارها:
              assistant.chat | reminder.create | reminder.list | reminder.delete | reminder.update |
              navigation.search | navigation.start | finance.track | finance.report |
              education.ask | education.generate_question | call.smart | weather.check | music.play | unknown
            - slots: یک شیء JSON از پارامترها (اختیاری)

            قوانین:
            - اگر مطمئن نیستی intent=assistant.chat بگذار.
            - متن کاربر فارسی است؛ intent را کوتاه و دقیق انتخاب کن.
        """.trimIndent()
    }

    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1)
    }

    private fun parseOnlineIntentJson(json: JSONObject, rawText: String): AIIntent? {
        val name = json.optString("intent").trim()
        val slots = json.optJSONObject("slots")

        fun slot(key: String): String? = slots?.optString(key)?.trim()?.takeIf { !it.isNullOrBlank() }

        return when (name) {
            "assistant.chat" -> AssistantChatIntent(rawText = rawText)
            "reminder.create" -> ReminderCreateIntent(
                rawText = rawText,
                hint = slot("hint"),
                type = slot("type")
            )
            "reminder.list" -> ReminderListIntent(rawText = rawText, category = slot("category"))
            "reminder.delete" -> ReminderDeleteIntent(rawText = rawText, reminderId = slot("reminderId")?.toLongOrNull())
            "reminder.update" -> ReminderUpdateIntent(rawText = rawText, reminderId = slot("reminderId")?.toLongOrNull())
            "navigation.search" -> NavigationSearchIntent(rawText = rawText, destination = slot("destination"))
            "navigation.start" -> NavigationStartIntent(rawText = rawText, destination = slot("destination"))
            "finance.track" -> FinanceTrackIntent(rawText = rawText, type = slot("type"))
            "finance.report" -> FinanceReportIntent(rawText = rawText, timeRange = slot("timeRange"))
            "education.ask" -> EducationAskIntent(rawText = rawText, topic = slot("topic"))
            "education.generate_question" -> EducationGenerateQuestionIntent(
                rawText = rawText,
                topic = slot("topic"),
                level = slot("level")
            )
            "call.smart" -> CallSmartIntent(rawText = rawText, contactName = slot("contactName"))
            "weather.check" -> WeatherCheckIntent(rawText = rawText, location = slot("location"))
            "music.play" -> MusicPlayIntent(rawText = rawText, query = slot("query"))
            "unknown" -> UnknownIntent
            else -> null
        }
    }

    private fun logIntent(request: AIIntentRequest) {
        try {
            Log.d(
                "AIIntentController",
                "Intent=${request.intent.name} | " +
                "Source=${request.source.name} | " +
                "Mode=${request.workingModeName ?: "N/A"}"
            )
        } catch (e: Exception) {
            Log.w("AIIntentController", "Failed to log intent", e)
        }
    }
}