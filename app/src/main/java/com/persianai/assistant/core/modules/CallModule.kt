package com.persianai.assistant.core.modules

import android.content.Context
import android.util.Log
import com.persianai.assistant.core.AIIntentRequest
import com.persianai.assistant.core.AIIntentResult
import com.persianai.assistant.core.intent.AIIntent
import com.persianai.assistant.core.intent.CallSmartIntent
import com.persianai.assistant.call.CallIntentProcessor
import com.persianai.assistant.call.CallConfirmationManager
import com.persianai.assistant.call.CallContactSelectionDialogue
import com.persianai.assistant.call.DirectPhoneCallDialogue
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class CallModule(context: Context) : BaseModule(context) {
    override val moduleName: String = "Call"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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
        // اولویت با intent.contactName که توسط AI پردازش شده
        val contactName = intent.contactName ?: extractContactName(intent.rawText)
        val phoneNumber = extractPhoneNumber(intent.rawText)
        
        logAction("SMART_CALL", "contact=$contactName, phone=$phoneNumber")
        
        return try {
            when {
                // حالت ۱: تماس مستقیم با شماره
                phoneNumber.isNotBlank() -> {
                    handleDirectPhoneCall(phoneNumber)
                }
                
                // حالت ۲: تماس با مخاطب
                contactName.isNotBlank() -> {
                    val callProcessor = CallIntentProcessor(context)
                    val result = callProcessor.processCallIntent(contactName)
                    processContactCallResult(result, intent)
                }
                
                else -> {
                    createResult(
                        text = "⚠️ لطفاً نام مخاطب یا شماره تلفن را مشخص کنید.\nمثال: 'تماس با علی' یا 'تماس با شماره ۰۹۱۲...'",
                        intentName = intent.name
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
    
    /**
     * استخراج شماره تلفن از عبارت
     */
    private fun extractPhoneNumber(text: String): String {
        // الگوهای شماره تلفن ایرانی
        val phonePatterns = listOf(
            Regex("0?9([0-9]{9})"), // 09123456789 یا 9123456789
            Regex("\\+989([0-9]{9})"), // +989123456789
            Regex("9([0-9]{9})") // 9123456789
        )
        
        for (pattern in phonePatterns) {
            val match = pattern.find(text)
            if (match != null) {
                val number = match.value
                // نرمال‌سازی شماره به فرمت استاندارد
                return when {
                    number.startsWith("+98") -> number.replace("+98", "0")
                    number.startsWith("98") && number.length == 12 -> "0" + number.substring(2)
                    number.startsWith("9") && number.length == 10 -> "0" + number
                    else -> number
                }
            }
        }
        
        return ""
    }
    
    /**
     * مدیریت تماس مستقیم با شماره تلفن
     */
    private suspend fun handleDirectPhoneCall(phoneNumber: String): AIIntentResult {
        Log.d(TAG, "📞 تماس مستقیم با شماره: $phoneNumber")
        
        // شروع مکالمه تأیید تماس مستقیم
        val dialogueManager = DirectPhoneCallDialogue(context, phoneNumber)
        scope.launch {
            dialogueManager.startDirectCallConfirmation()
        }
        
        return createResult(
            text = "📞 تماس با شماره $phoneNumber\nدر حال تأیید...",
            intentName = "DirectPhoneCall",
            success = true
        )
    }
    
    /**
     * پردازش نتیجه تماس با مخاطب
     */
    private suspend fun processContactCallResult(result: CallIntentProcessor.CallIntentResult, intent: CallSmartIntent): AIIntentResult {
        return when (result) {
            is CallIntentProcessor.CallIntentResult.SingleContact -> {
                // شروع فرآیند تأیید تماس
                val confirmationManager = CallConfirmationManager(context)
                confirmationManager.startCallConfirmation(result.contact, result.contact.phoneNumber)
                
                createResult(
                    text = "📞 مخاطب پیدا شد: «${result.contact.name}»\n" +
                            "صفحه تأیید تماس نمایش داده شد.",
                    intentName = intent.name,
                    success = true
                )
            }
            
            is CallIntentProcessor.CallIntentResult.MultipleContacts -> {
                // شروع مکالمه صوتی برای انتخاب مخاطب
                val dialogueManager = CallContactSelectionDialogue(context, result.contacts)
                scope.launch {
                    dialogueManager.startSelectionDialogue()
                }
                
                // نمایش فقط نام و شماره مخاطبین
                val contactList = result.contacts.take(3).joinToString("\n") { contact ->
                    "• ${contact.name}: ${contact.phoneNumber}"
                }
                
                createResult(
                    text = "📞 ${result.contacts.size} مخاطب پیدا شد:\n$contactList",
                    intentName = intent.name,
                    success = true
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
