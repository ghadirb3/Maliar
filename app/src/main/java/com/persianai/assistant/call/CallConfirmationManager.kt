package com.persianai.assistant.call

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.*
import com.persianai.assistant.utils.TTSHelper
import com.persianai.assistant.models.Contact
import com.persianai.assistant.activities.CallConfirmationActivity
import com.persianai.assistant.integration.IviraIntegrationManager
import com.persianai.assistant.stt.OnlineSTTService
import java.io.File

/**
 * مدیر سیستم تأیید تماس مالیار
 * مکانیزم ایمن و بدون لمس برای تأیید تماس‌های صوتی
 */
class CallConfirmationManager(private val context: Context) {
    
    private val TAG = "CallConfirmationManager"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val ttsHelper = TTSHelper(context)
    private val iviraManager = IviraIntegrationManager(context)
    private val onlineSTT = OnlineSTTService(context)
    
    // کلمات کلیدی برای تأیید و لغو
    private val positiveKeywords = listOf(
        "بله", "آره", "تماس", "بگیر", "اوکی", "باشه", "انجام بده", "دقیقا", "میخوام", "می‌خوام"
    )
    
    private val negativeKeywords = listOf(
        "لغو", "کنسل", "نه", "خیر", "نمیخوام", "نمی‌خوام", "انجام نده", "متوقف شو", "برو"
    )
    
    private var currentCallSession: CallSession? = null
    
    /**
     * شروع فرآیند تأیید تماس
     */
    fun startCallConfirmation(contact: Contact, phoneNumber: String) {
        Log.d(TAG, "🚀 شروع فرآیند تأیید تماس با ${contact.name}")
        
        // لغو جلسه قبلی اگر وجود دارد
        currentCallSession?.cancel()
        
        currentCallSession = CallSession(contact, phoneNumber).apply {
            start()
        }
    }
    
    /**
     * لغو فرآیند تأیید تماس فعلی
     */
    fun cancelCurrentConfirmation() {
        currentCallSession?.cancel()
        currentCallSession = null
    }
    
    /**
     * جلسه تأیید تماس
     */
    inner class CallSession(
        private val contact: Contact,
        private val phoneNumber: String
    ) {
        private var job: Job? = null
        private var isActive = false
        
        fun start() {
            isActive = true
            job = scope.launch {
                try {
                    // مرحله ۱: پرسش تأیید
                    askForConfirmation()
                    
                    // مرحله ۲: فعال کردن شناسایی صدا
                    val response = listenForResponse()
                    
                    // مرحله ۳: تحلیل پاسخ و اجرا
                    processResponse(response)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "❌ خطا در جلسه تأیید تماس", e)
                    scope.launch {
                        ttsHelper.speakOnlineFirst("خطا در تأیید تماس")
                    }
                } finally {
                    isActive = false
                }
            }
        }
        
        fun cancel() {
            isActive = false
            job?.cancel()
        }
        
        private suspend fun askForConfirmation() {
            if (!isActive) return
            
            val message = "با ${contact.name} تماس بگیرم؟"
            Log.d(TAG, "📢 پرسش تأیید: $message")
            
            // استفاده از TTSHelper با اولویت GapGPT آنلاین
            ttsHelper.speakOnlineFirst(message)
            
            // نمایش صفحه تأیید تماس
            showConfirmationOverlay()
        }
        
        private suspend fun listenForResponse(): String {
            if (!isActive) return ""
            
            delay(1000) // کمی صبر برای تمام شدن TTS
            
            Log.d(TAG, "🎤 فعال کردن شناسایی صدا برای پاسخ کاربر...")
            
            return withContext(Dispatchers.IO) {
                try {
                    // استفاده از OnlineSTTService برای شناسایی صدا (Liara → GapGPT)
                    val audioFile = createTempAudioFile()
                    val sttResult = onlineSTT.transcribeAudio(audioFile)
                    
                    val response = if (sttResult.isSuccess) sttResult.text else ""
                    Log.d(TAG, "✅ پاسخ شناسایی شد: $response")
                    
                    response
                } catch (e: Exception) {
                    Log.e(TAG, "❌ خطا در شناسایی صدا", e)
                    ""
                }
            }
        }
        
        private fun createTempAudioFile(): File {
            val tempDir = File(context.cacheDir, "temp_audio")
            if (!tempDir.exists()) tempDir.mkdirs()
            return File(tempDir, "temp_recording_${System.currentTimeMillis()}.wav")
        }
        
        private suspend fun processResponse(response: String) {
            if (!isActive) return
            
            Log.d(TAG, "📝 پاسخ کاربر: '$response'")
            
            val normalizedResponse = response.lowercase().trim()
            
            when {
                positiveKeywords.any { it in normalizedResponse } -> {
                    Log.d(TAG, "✅ پاسخ مثبت دریافت شد - برقراری تماس")
                    scope.launch {
                        ttsHelper.speakOnlineFirst("در حال برقراری تماس...")
                    }
                    makePhoneCall()
                }
                
                negativeKeywords.any { it in normalizedResponse } -> {
                    Log.d(TAG, "❌ پاسخ منفی دریافت شد - لغو تماس")
                    scope.launch {
                        ttsHelper.speakOnlineFirst("تماس لغو شد")
                    }
                }
                
                normalizedResponse.isEmpty() -> {
                    Log.d(TAG, "⏹️ سکوت کاربر - لغو تماس")
                    scope.launch {
                        ttsHelper.speakOnlineFirst("تماس لغو شد")
                    }
                }
                
                else -> {
                    Log.d(TAG, "❓ پاسخ نامفهوم - لغو تماس")
                    scope.launch {
                        ttsHelper.speakOnlineFirst("متوجه نشدم، تماس لغو شد")
                    }
                }
            }
            
            hideConfirmationOverlay()
        }
        
        private fun makePhoneCall() {
            try {
                val intent = Intent(Intent.ACTION_CALL).apply {
                    data = Uri.parse("tel:$phoneNumber")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                Log.d(TAG, "📞 تماس با $phoneNumber برقرار شد")
            } catch (e: Exception) {
                Log.e(TAG, "❌ خطا در برقراری تماس", e)
                scope.launch {
                    ttsHelper.speakOnlineFirst("خطا در برقراری تماس")
                }
            }
        }
        
        private fun showConfirmationOverlay() {
            if (!isActive) return
            
            try {
                Log.d(TAG, "📱 نمایش صفحه تأیید تماس")
                CallConfirmationActivity.start(context, contact)
            } catch (e: Exception) {
                Log.e(TAG, "❌ خطا در نمایش صفحه تأیید", e)
            }
        }
        
        private fun hideConfirmationOverlay() {
            // TODO: مخفی کردن اورلی تأیید تماس
            Log.d(TAG, "📱 مخفی کردن صفحه تأیید تماس")
        }
    }
    
    /**
     * آزادسازی منابع
     */
    fun cleanup() {
        scope.cancel()
        currentCallSession?.cancel()
        currentCallSession = null
    }
}
