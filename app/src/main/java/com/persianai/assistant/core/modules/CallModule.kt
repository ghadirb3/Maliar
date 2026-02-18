package com.persianai.assistant.core.modules

import android.content.Context
import android.util.Log
import com.persianai.assistant.core.AIIntentRequest
import com.persianai.assistant.core.AIIntentResult
import com.persianai.assistant.core.intent.AIIntent
import com.persianai.assistant.core.intent.CallSmartIntent
import com.persianai.assistant.utils.SmartCallManager
import kotlinx.coroutines.runBlocking

class CallModule(context: Context) : BaseModule(context) {
    override val moduleName: String = "Call"

    override suspend fun canHandle(intent: AIIntent): Boolean {
        return intent is CallSmartIntent
    }

    override suspend fun execute(request: AIIntentRequest, intent: AIIntent): AIIntentResult {
        return when (intent) {
            is CallSmartIntent -> handleSmartCall(request, intent)
            else -> createResult("نوع Intent نشناخته‌شده", intent.name, false)
        }
    }

    private suspend fun handleSmartCall(request: AIIntentRequest, intent: CallSmartIntent): AIIntentResult {
        val contactName = intent.contactName ?: extractContactName(intent.rawText)
        
        logAction("SMART_CALL", "contact=$contactName")
        
        if (contactName.isBlank()) {
            return createResult(
                text = "⚠️ لطفاً نام مخاطب را مشخص کنید.\nمثال: 'تماس با علی' یا 'تماس با بهمن'",
                intentName = intent.name
            )
        }
        
        return try {
            // استفاده از SmartCallManager برای پردازش هوشمند تماس
            val result = SmartCallManager.processCallRequest(context, contactName)
            
            when (result) {
                is com.persianai.assistant.utils.CallResult.SingleContact -> {
                    createResult(
                        text = "📞 مخاطب پیدا شد: «${result.contact.name}»\n" +
                                "شماره: ${result.contact.phoneNumber} (${result.contact.phoneType})\n\n" +
                                "🔔 نوتیفیکیشن تماس ارسال شد. برای تماس روی نوتیفیکیشن ضربه بزنید.",
                        intentName = intent.name,
                        success = true
                    )
                }
                is com.persianai.assistant.utils.CallResult.MultipleContacts -> {
                    createResult(
                        text = "📞 ${result.contacts.size} مخاطب مشابه پیدا شد:\n" +
                                result.contacts.take(5).joinToString("\n") { 
                                    "• ${it.name}: ${it.phoneNumber}" 
                                } +
                                (if (result.contacts.size > 5) "\n... و ${result.contacts.size - 5} مخاطب دیگر" else "") +
                                "\n\n🔔 نوتیفیکیشن انتخاب مخاطب ارسال شد.",
                        intentName = intent.name,
                        success = true
                    )
                }
                is com.persianai.assistant.utils.CallResult.NotFound -> {
                    createResult(
                        text = "❌ مخاطب '${result.contactName}' در لیست تماس‌های شما یافت نشد.\n\n" +
                                "💡 پیشنهاد:\n" +
                                "• نام مخاطب را کامل و صحیح بگویید\n" +
                                "• از کلمات اضافی مثل «آقا»، «خانم» استفاده نکنید\n" +
                                "• مطمئن شوید مخاطب در دفترچه تلفن شما ذخیره شده",
                        intentName = intent.name,
                        success = false
                    )
                }
                is com.persianai.assistant.utils.CallResult.NeedPermission -> {
                    createResult(
                        text = "🔒 برای تماس با '${result.contactName}' به مجوز دسترسی به مخاطبین نیاز داریم.\n\n" +
                                "🔔 نوتیفیکیشن درخواست مجوز ارسال شد.\n" +
                                "روی نوتیفیکیشن ضربه بزنید تا مجوز داده شود.",
                        intentName = intent.name,
                        success = false
                    )
                }
                is com.persianai.assistant.utils.CallResult.Error -> {
                    createResult(
                        text = "❌ خطا در جستجوی مخاطب: ${result.message}",
                        intentName = intent.name,
                        success = false
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling smart call", e)
            createResult(
                text = "❌ خطا: ${e.message}",
                intentName = intent.name,
                success = false
            )
        }
    }

    private fun extractContactName(text: String): String {
        val patterns = listOf(
            "تماس با (\\S+)",
            "برای تماس با (\\S+)",
            "تماس شماره (\\S+)",
            "call to (\\S+)",
            "call (\\S+)"
        )
        
        for (pattern in patterns) {
            val regex = Regex(pattern)
            val result = regex.find(text)
            if (result != null) {
                return result.groupValues[1]
            }
        }
        
        return text.trim()
    }
}