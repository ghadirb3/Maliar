package com.persianai.assistant.call

import android.content.Context
import android.util.Log
import com.persianai.assistant.utils.TTSHelper
import com.persianai.assistant.stt.OnlineSTTService
import com.persianai.assistant.services.UnifiedVoiceEngine
import com.persianai.assistant.services.RecordingResult
import com.persianai.assistant.ai.AdvancedPersianAssistant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * مدیر مکالمه صوتی برای تماس مستقیم با شماره تلفن
 * دو مرحله تأیید: ۱) خواندن شماره ۲) تأیید نهایی
 */
class DirectPhoneCallDialogue(
    private val context: Context,
    private val phoneNumber: String
) {
    private val TAG = "DirectPhoneCallDialogue"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val ttsHelper = TTSHelper(context)
    private val onlineSTT = OnlineSTTService(context)
    private val aiAssistant = AdvancedPersianAssistant(context)
    
    /**
     * شروع مکالمه تأیید تماس مستقیم
     */
    suspend fun startDirectCallConfirmation() {
        try {
            // مرحله ۱: خواندن شماره برای تأیید
            speakPhoneNumberForConfirmation()
            
            // مرحله ۲: شنود برای پاسخ کاربر
            val response = listenForUserResponse()
            
            // مرحله ۳: تحلیل پاسخ با AI آنلاین
            processConfirmationResponse(response)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطا در مکالمه تأیید تماس مستقیم", e)
            scope.launch {
                ttsHelper.speakOnlineFirst("خطا در تأیید تماس")
            }
        }
    }
    
    /**
     * خواندن شماره تلفن با TTS
     */
    private suspend fun speakPhoneNumberForConfirmation() {
        val formattedPhone = TTSHelper.formatPhoneNumberForTTS(phoneNumber)
        val message = "با شماره $formattedPhone تماس بگیرم؟"
        Log.d(TAG, "📢 درخواست تأیید تماس مستقیم: $message")

        ttsHelper.speakOnlineFirstAndWait(message)
        delay(300)
    }
    
    /**
     * شنود برای پاسخ کاربر
     */
    private suspend fun listenForUserResponse(): String = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🎤 شروع ضبط صدا برای پاسخ تأیید")
            
            val engine = UnifiedVoiceEngine(context)
            val tempFile = createTempAudioFile()
            
            // شروع ضبط با VAD
            val startResult = engine.startRecording()
            if (!startResult.isSuccess) {
                Log.e(TAG, "❌ خطا در شروع ضبط: ${startResult.exceptionOrNull()?.message}")
                return@withContext ""
            }
            
            Log.d(TAG, "✅ ضبط صدا شروع شد")
            
            // منتظر مکث یا timeout
            val timeoutMs = 12000L // 12 ثانیه
            val silenceStopMs = 2000L // 2 ثانیه سکوت
            val startTime = System.currentTimeMillis()
            var lastSpeechTime = startTime
            var hasSpeech = false
            
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                val now = System.currentTimeMillis()
                val amplitude = engine.getCurrentAmplitude()
                
                if (amplitude > 100) {
                    hasSpeech = true
                    lastSpeechTime = now
                }
                
                if (hasSpeech && (now - lastSpeechTime) > silenceStopMs) {
                    Log.d(TAG, "🔇 سکوت تشخیص داده شد - توقف ضبط")
                    break
                }
                
                delay(100)
            }
            
            // توقف ضبط و دریافت فایل
            val stopResult = engine.stopRecording()
            val recordingResult = if (stopResult.isSuccess) {
                stopResult.getOrNull()
            } else null
            
            if (recordingResult == null) {
                Log.e(TAG, "❌ فایل صوتی ضبط نشد")
                withContext(Dispatchers.Main) {
                    ttsHelper.speakOnlineFirst("خطا در ضبط صدا، لطفاً دوباره تلاش کنید")
                }
                return@withContext ""
            }
            
            val recordedFile = recordingResult.file
            
            if (!recordedFile.exists()) {
                Log.e(TAG, "❌ فایل صوتی وجود ندارد: ${recordedFile.absolutePath}")
                withContext(Dispatchers.Main) {
                    ttsHelper.speakOnlineFirst("خطا در ضبط صدا، لطفاً دوباره تلاش کنید")
                }
                return@withContext ""
            }
            
            Log.d(TAG, "✅ فایل صوتی ضبط شد: ${recordedFile.absolutePath}")
            
            // ارسال به STT
            val sttResult = onlineSTT.transcribeAudio(recordedFile)
            val transcribedText = if (sttResult.isSuccess) sttResult.text else ""
            
            // بررسی سکوت یا timeout
            if (transcribedText.isBlank()) {
                Log.d(TAG, "⏰ کاربر سکوت کرد")
                withContext(Dispatchers.Main) {
                    ttsHelper.speakOnlineFirst("به دلیل سکوت، تماس لغو شد")
                }
                return@withContext "SILENCE_TIMEOUT"
            }
            
            // بررسی کلمات لغو
            val normalizedText = transcribedText.lowercase().trim()
            if (normalizedText.contains("لغو") || normalizedText.contains("کنسل") || normalizedText.contains("نه") || 
                normalizedText.contains("تموم") || normalizedText.contains("بس") || normalizedText.contains("تمام")) {
                Log.d(TAG, "❌ کاربر لغو کرد: $transcribedText")
                withContext(Dispatchers.Main) {
                    ttsHelper.speakOnlineFirst("تماس لغو شد")
                }
                return@withContext "CANCEL"
            }
            
            Log.d(TAG, "✅ پاسخ کاربر: $transcribedText")
            transcribedText
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطا در شناسایی صدا", e)
            withContext(Dispatchers.Main) {
                ttsHelper.speakOnlineFirst("خطا در شناسایی صدا، لطفاً دوباره تلاش کنید")
            }
            ""
        }
    }
    
    /**
     * تحلیل پاسخ تأیید با AI آنلاین
     */
    private suspend fun processConfirmationResponse(response: String) {
        Log.d(TAG, "🤖 تحلیل پاسخ تأیید تماس مستقیم: $response")
        
        when {
            response == "SILENCE_TIMEOUT" -> {
                Log.d(TAG, "⏰ کاربر سکوت کرد - لغو خودکار تماس")
                // پیام قبلاً در listenForUserResponse گفته شد
            }
            
            response == "CANCEL" -> {
                Log.d(TAG, "❌ کاربر لغو کرد")
                // پیام قبلاً در listenForUserResponse گفته شد
            }
            
            else -> {
                // استخراج شماره تلفن از پاسخ (در صورت وجود)
                val extractedPhone = extractPhoneNumberFromText(response)
                
                when {
                    // بررسی کلمات تأیید
                    response.lowercase().contains("بله") || 
                    response.lowercase().contains("آره") || 
                    response.lowercase().contains("تماس") ||
                    response.lowercase().contains("بزن") ||
                    response.lowercase().contains("کن") -> {
                        
                        val finalPhone = if (extractedPhone.isNotBlank()) {
                            Log.d(TAG, "📞 شماره اصلاح شده از پاسخ: $extractedPhone")
                            extractedPhone
                        } else {
                            phoneNumber
                        }
                        
                        Log.d(TAG, "✅ تماس تأیید شد با شماره: $finalPhone")
                        
                        withContext(Dispatchers.Main) {
                            ttsHelper.speakOnlineFirst("در حال برقراری تماس با $finalPhone")
                        }
                        
                        delay(1000)
                        
                        // برقراری تماس
                        makePhoneCall(finalPhone)
                    }
                    
                    // اگر شماره جدیدی در پاسخ وجود دارد، از آن استفاده کن
                    extractedPhone.isNotBlank() && extractedPhone != phoneNumber -> {
                        Log.d(TAG, "📞 شماره جدید از پاسخ استخراج شد: $extractedPhone")
                        
                        withContext(Dispatchers.Main) {
                            val formattedPhone = TTSHelper.formatPhoneNumberForTTS(extractedPhone)
                            ttsHelper.speakOnlineFirst("با شماره $formattedPhone تماس بگیرم؟")
                        }
                        
                        delay(2000)
                        
                        // شنود مجدد برای تأیید شماره جدید
                        val secondResponse = listenForUserResponse()
                        processConfirmationResponse(secondResponse)
                    }
                    
                    else -> {
                        Log.d(TAG, "❓ پاسخ نامشخص: $response")
                        withContext(Dispatchers.Main) {
                            ttsHelper.speakOnlineFirst("متوجه نشدم. لطفاً بگویید بله برای تماس یا لغو برای انصراف")
                        }
                    }
                }
            }
        }
    }
    
    /**
     * استخراج شماره تلفن از متن با پشتیبانی از ارقام پراکنده
     */
    private fun extractPhoneNumberFromText(text: String): String {
        val normalizedText = text
            .replace('۰', '0').replace('٠', '0')
            .replace('۱', '1').replace('١', '1')
            .replace('۲', '2').replace('٢', '2')
            .replace('۳', '3').replace('٣', '3')
            .replace('۴', '4').replace('٤', '4')
            .replace('۵', '5').replace('٥', '5')
            .replace('۶', '6').replace('٦', '6')
            .replace('۷', '7').replace('٧', '7')
            .replace('۸', '8').replace('٨', '8')
            .replace('۹', '9').replace('٩', '9')

        // الگوهای استاندارد
        val phonePatterns = listOf(
            Regex("0?9([0-9]{9})"), // 09123456789 یا 9123456789
            Regex("\\+989([0-9]{9})"), // +989123456789
            Regex("9([0-9]{9})") // 9123456789
        )
        
        // اول الگوهای استاندارد را امتحان کن
        for (pattern in phonePatterns) {
            val match = pattern.find(normalizedText)
            if (match != null) {
                val number = match.value
                // نرمال‌سازی شماره به فرمت استاندارد
                return when {
                    number.startsWith("+98") -> number.replace("+98", "0")
                    number.startsWith("98") && number.length == 12 -> "0" + number.substring(2)
                    number.startsWith("9") && number.length == 10 -> "0" + number
                    else -> number.replace("\\s".toRegex(), "")
                }
            }
        }
        
        // اگر الگوی استانداری پیدا نشد، ارقام پراکنده را ترکیب کن
        val allDigits = normalizedText.filter { it.isDigit() }
        
        // جستجوی شماره تلفن ایرانی در ارقام استخراج شده
        if (allDigits.length >= 10) {
            // الگوهای مختلف برای شماره ایرانی
            val iranianPatterns = listOf(
                // شماره 10 رقمی که با 9 شروع می‌شود
                Regex("(9\\d{9})"),
                // شماره 11 رقمی که با 09 شروع می‌شود  
                Regex("(09\\d{9})"),
                // شماره 12 رقمی که با 989 شروع می‌شود
                Regex("(989\\d{9})"),
                // شماره 13 رقمی که با +989 شروع می‌شود
                Regex("(\\+989\\d{9})")
            )
            
            for (pattern in iranianPatterns) {
                val match = pattern.find(allDigits)
                if (match != null) {
                    var number = match.value
                    // نرمال‌سازی
                    when {
                        number.startsWith("+98") -> number = number.replace("+98", "0")
                        number.startsWith("98") && number.length == 12 -> number = "0" + number.substring(2)
                        number.startsWith("9") && number.length == 10 -> number = "0" + number
                    }
                    // حذف فاصله‌ها
                    number = number.replace("\\s".toRegex(), "")
                    
                    // اعتبارسنجی نهایی
                    if (number.matches(Regex("09\\d{9}"))) {
                        return number
                    }
                }
            }
        }
        
        return ""
    }
    
    /**
     * برقراری تماس تلفنی
     */
    private suspend fun makePhoneCall(phoneNumber: String) {
        try {
            // استفاده از CallConfirmationManager برای شروع تماس
            val confirmationManager = CallConfirmationManager(context)
            
            // ایجاد یک مخاطب مجازی برای تماس مستقیم
            val dummyContact = com.persianai.assistant.models.Contact(
                id = "-1",
                name = "شماره مستقیم",
                phoneNumber = phoneNumber
            )
            
            confirmationManager.startCallConfirmation(dummyContact, phoneNumber)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطا در شروع تماس واقعی", e)
            withContext(Dispatchers.Main) {
                ttsHelper.speakOnlineFirst("خطا در شروع تماس")
            }
        }
    }
    
    /**
     * ایجاد فایل صوتی موقت
     */
    private fun createTempAudioFile(): File {
        val timestamp = System.currentTimeMillis()
        val fileName = "temp_direct_call_$timestamp.wav"
        return File(context.cacheDir, fileName)
    }
}
