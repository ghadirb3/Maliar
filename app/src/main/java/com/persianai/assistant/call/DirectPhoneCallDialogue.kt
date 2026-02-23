package com.persianai.assistant.call

import android.content.Context
import android.util.Log
import com.persianai.assistant.utils.TTSHelper
import com.persianai.assistant.utils.TTSHelper.formatPhoneNumberForTTS
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
        val formattedPhone = formatPhoneNumberForTTS(phoneNumber)
        val message = "با شماره $formattedPhone تماس بگیرم؟"
        Log.d(TAG, "📢 درخواست تأیید تماس مستقیم: $message")
        
        ttsHelper.speakOnlineFirst(message)
        
        // صبر برای تمام شدن TTS و کمی وقفه قبل از ضبط
        delay(2000)
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
            val timeoutMs = 8000L // 8 ثانیه
            val silenceStopMs = 2000L // 2 ثانیه سکوت
            val startTime = System.currentTimeMillis()
            var lastSpeechTime = startTime
            var hasSpeech = false
            
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                val now = System.currentTimeMillis()
                val amplitude = engine.getCurrentAmplitude()
                
                if (amplitude > 0.1f) {
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
                Log.d(TAG, "⏰ لغو به دلیل سکوت کاربر")
                withContext(Dispatchers.Main) {
                    ttsHelper.speakOnlineFirst("به دلیل سکوت، تماس لغو شد")
                }
                return
            }
            
            response.isBlank() -> {
                Log.d(TAG, "❌ پاسخ خالی")
                return
            }
        }
        
        try {
            // ساخت پرامپت برای AI
            val prompt = """
                کاربر برای تأیید تماس با شماره $phoneNumber گفت: "$response"
                
                لطفاً تحلیل کن آیا کاربر تمایل به تماس دارد:
                - کلمات تأیید: بله، آره، تماس بگیر، اوکی، باشه، انجام بده، بگیر
                - کلمات لغو: لغو، نه، کنسل، انصراف، نمیخوام
                
                فقط در یک کلمه پاسخ بده:
                - "CONFIRM" اگر تأیید کرده
                - "CANCEL" اگر لغو کرده
                - "NONE" اگر مشخص نیست
            """.trimIndent()
            
            // تحلیل با AI
            val aiResponse = aiAssistant.processRequestWithAI(prompt)
            val analysis = aiResponse.text.lowercase().trim()
            
            Log.d(TAG, "🤖 پاسخ AI تأیید تماس مستقیم: $analysis")
            
            when {
                analysis.contains("confirm") -> {
                    Log.d(TAG, "✅ AI تشخیص داد: تأیید تماس")
                    withContext(Dispatchers.Main) {
                        val formattedPhone = formatPhoneNumberForTTS(phoneNumber)
                        ttsHelper.speakOnlineFirst("در حال تماس با شماره $formattedPhone")
                    }
                    
                    // شروع تماس واقعی
                    startActualCall()
                }
                
                analysis.contains("cancel") -> {
                    Log.d(TAG, "❌ AI تشخیص داد: لغو تماس")
                    withContext(Dispatchers.Main) {
                        ttsHelper.speakOnlineFirst("تماس لغو شد")
                    }
                }
                
                else -> {
                    Log.d(TAG, "❓ AI تشخیص داد: نامشخص")
                    withContext(Dispatchers.Main) {
                        ttsHelper.speakOnlineFirst("متوجه نشدم، تماس لغو شد")
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطا در تحلیل تأیید AI", e)
            withContext(Dispatchers.Main) {
                ttsHelper.speakOnlineFirst("خطا در تحلیل پاسخ، تماس لغو شد")
            }
        }
    }
    
    /**
     * شروع تماس واقعی
     */
    private suspend fun startActualCall() {
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
