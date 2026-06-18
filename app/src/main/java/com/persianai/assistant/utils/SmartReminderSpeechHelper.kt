package com.persianai.assistant.utils

import android.content.Context
import android.util.Log
import com.persianai.assistant.ai.AIClient
import com.persianai.assistant.models.AIModel
import com.persianai.assistant.models.ChatMessage
import com.persianai.assistant.models.MessageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * تولید متن طبیعی برای یادآوری هوشمند و پخش با TTS آفلاین
 */
object SmartReminderSpeechHelper {

    private const val TAG = "SmartReminderSpeech"

    suspend fun generateSpeechText(context: Context, title: String, description: String): String =
        withContext(Dispatchers.IO) {
            val baseMessage = buildString {
                append(title.trim())
                if (description.isNotBlank()) {
                    append(". ")
                    append(description.trim())
                }
            }.ifBlank { "یادآوری" }

            val aiText = withTimeoutOrNull(8_000L) {
                generateWithAI(context, baseMessage)
            }

            aiText ?: buildLocalNaturalText(title, description)
        }

    private suspend fun generateWithAI(context: Context, message: String): String? {
        return try {
            val prefsManager = PreferencesManager(context)
            val apiKeys = prefsManager.getAPIKeys().filter { it.isActive }
            if (apiKeys.isEmpty()) return null

            val aiClient = AIClient(context, apiKeys)
            val prompt = """
                کاربر یک یادآوری ثبت کرده: "$message"
                
                لطفاً همین یادآوری را به صورت طبیعی‌تر و دوستانه‌تر بازنویسی کن، طوری که انگار یک دوست به کاربر یادآوری می‌کند.
                فقط یک جمله کوتاه و مستقیم بگو و از علامت نقل‌قول استفاده نکن.
            """.trimIndent()

            val response = aiClient.sendMessage(
                model = AIModel.GPT_4O_MINI,
                messages = listOf(ChatMessage(role = MessageRole.USER, content = prompt)),
                systemPrompt = "تو یک دستیار صمیمی فارسی‌زبان هستی که یادآوری‌ها را به صورت طبیعی بیان می‌کنی."
            )

            val content = response.content.trim().trim('"', '«', '»')
            if (content.length in 10..200) content else null
        } catch (e: Exception) {
            Log.w(TAG, "AI speech text generation failed", e)
            null
        }
    }

    private fun buildLocalNaturalText(title: String, description: String): String {
        val cleanTitle = title.trim().removePrefix("⏰").trim()
        return if (description.isNotBlank()) {
            "یادآوری: $cleanTitle. $description"
        } else {
            "یادت باشه: $cleanTitle"
        }
    }
}
