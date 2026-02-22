package com.persianai.assistant.core.modules

import android.content.Context
import android.util.Log
import com.persianai.assistant.core.AIIntentRequest
import com.persianai.assistant.core.AIIntentResult
import com.persianai.assistant.core.intent.AIIntent
import com.persianai.assistant.core.intent.CallSmartIntent
import com.persianai.assistant.call.CallIntentProcessor
import com.persianai.assistant.call.CallConfirmationManager
import com.persianai.assistant.activities.CallConfirmationActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

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
            // استفاده از سیستم جدید تماس هوشمند
            val callProcessor = CallIntentProcessor(context)
            val result = callProcessor.processCallIntent(contactName)
            
            when (result) {
                is CallIntentProcessor.CallIntentResult.SingleContact -> {
                    // شروع فرآیند تأیید تماس
                    val confirmationManager = CallConfirmationManager(context)
                    confirmationManager.startCallConfirmation(result.contact, result.contact.phoneNumber)
                    
                    createResult(
                        text = "📞 مخاطب پیدا شد: «${result.contact.name}»\n" +
                                "صفحه تأیید تماس نمایش داده شد.\n" +
                                "بگویید «بله» برای تماس یا «لغو» برای انصراف.",
                        intentName = intent.name,
                        success = true
                    )
                }
                is CallIntentProcessor.CallIntentResult.MultipleContacts -> {
                    // مکالمه صوتی برای انتخاب مخاطب
                    val contactsText = result.contacts.take(3).joinToString("، ") { it.name }
                    val contactsWithNumbers = result.contacts.take(3).joinToString("\n") { 
                        "• ${it.name} (${it.phoneNumber})" 
                    }
                    
                    createResult(
                        text = "📞 ${result.contacts.size} مخاطب پیدا شد:\n" +
                                "$contactsWithNumbers\n\n" +
                                "🎤 لطفاً نام دقیق مخاطب مورد نظر را بگویید.\n" +
                                "برای تأیید تماس، بگویید «بله».\n" +
                                "برای لغو، بگویید «لغو» یا سکوت کنید.",
                        intentName = intent.name,
                        success = true,
                        requiresVoiceInput = true,
                        timeoutMs = 15000,
                        voicePrompt = "کدام مخاطب را می‌خواهید تماس بگیرید؟"
                    )
                }
                is CallIntentProcessor.CallIntentResult.ContactNotFound -> {
                    createResult(
                        text = "❌ ${result.message}\n\n" +
                                "💡 پیشنهاد:\n" +
                                "• نام مخاطب را کامل و صحیح بگویید\n" +
                                "• از کلمات اضافی مثل «آقا»، «خانم» استفاده نکنید\n" +
                                "• مطمئن شوید مخاطب در دفترچه تلفن شما ذخیره شده",
                        intentName = intent.name,
                        success = false
                    )
                }
                is CallIntentProcessor.CallIntentResult.PermissionRequired -> {
                    createResult(
                        text = "🔒 ${result.message}\n\n" +
                                "لطفاً به تنظیمات بروید و دسترسی مخاطبین را فعال کنید.",
                        intentName = intent.name,
                        success = false
                    )
                }
                is CallIntentProcessor.CallIntentResult.Error -> {
                    createResult(
                        text = "❌ ${result.message}",
                        intentName = intent.name,
                        success = false
                    )
                }
                is CallIntentProcessor.CallIntentResult.Cancel -> {
                    createResult(
                        text = "❌ درخواست تماس لغو شد.",
                        intentName = intent.name,
                        success = false
                    )
                }
                is CallIntentProcessor.CallIntentResult.NotRecognized -> {
                    createResult(
                        text = "⚠️ ${result.message}\n\n" +
                                "مثال: «تماس با علی» یا «با مریم تماس بگیر»",
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