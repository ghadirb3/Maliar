package com.persianai.assistant.call

import android.content.Context
import kotlinx.coroutines.withContext
import com.persianai.assistant.ai.AIClient
import com.persianai.assistant.core.AIIntentRequest
import com.persianai.assistant.core.AIIntentController
import kotlinx.coroutines.Dispatchers

/**
 * هوش مصنوعی برای تشخیص نیت کاربر در تماس‌ها
 * از مدل آنلاین برای درک بهتر دستورات طبیعی کاربر استفاده می‌کند
 */
class CallIntentAI(private val context: Context) {
    
    private val aiClient = AIClient(context)
    
    /**
     * تحلیل پاسخ کاربر با هوش مصنوعی آنلاین
     */
    suspend fun analyzeUserResponse(userInput: String, contactName: String): CallIntentResult {
        return withContext(Dispatchers.IO) {
            try {
                val prompt = """
                    کاربر در پاسخ به تماس با "${contactName}" گفت: "${userInput}"
                    
                    لطفاً نیت کاربر را تحلیل کن و یکی از موارد زیر را برگردان:
                    
                    POSSITIVE - اگر کاربر می‌خواهد تماس برقرار شود (کلماتی مثل: بله، آره، تماس بگیر، بزن، اوکی)
                    NEGATIVE - اگر کاربر می‌خواهد تماس لغو شود (کلماتی مثل: لغو، نه، کنسل، انصراف)
                    SPEAKER_ON - اگر کاربر می‌خواهد با بلندگو صحبت کند (کلماتی مثل: بلندگو، اسپیکر، خانوادگی، جمعی)
                    SPEAKER_OFF - اگر کاربر می‌خواهد با گوشی صحبت کند (کلماتی مثل: گوشی، معمولی، خصوصی، مخفی)
                    UNCLEAR - اگر نیت کاربر نامشخص است
                    
                    فقط یکی از کلمات بالا را برگردان.
                """.trimIndent()
                
                // استفاده از AIClient برای تحلیل هوشمند
                val response = aiClient.generateText(prompt)
                
                val intent = when {
                    response.contains("POSSITIVE", ignoreCase = true) -> CallIntentResult.POSITIVE
                    response.contains("NEGATIVE", ignoreCase = true) -> CallIntentResult.NEGATIVE
                    response.contains("SPEAKER_ON", ignoreCase = true) -> CallIntentResult.SPEAKER_ON
                    response.contains("SPEAKER_OFF", ignoreCase = true) -> CallIntentResult.SPEAKER_OFF
                    else -> CallIntentResult.UNCLEAR
                }
                
                CallIntentResult(intent, response)
                
            } catch (e: Exception) {
                // fallback به تحلیل دستی در صورت خطا
                CallIntentResult(CallIntentResult.UNCLEAR, "خطا در تحلیل هوشمند: ${e.message}")
            }
        }
    }
    
    enum class Intent {
        POSITIVE, NEGATIVE, SPEAKER_ON, SPEAKER_OFF, UNCLEAR
    }
    
    data class CallIntentResult(
        val intent: Intent,
        val explanation: String
    )
}
