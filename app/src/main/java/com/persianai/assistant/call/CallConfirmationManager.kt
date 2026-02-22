package com.persianai.assistant.call

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.media.AudioManager
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
        "بله", "آره", "تماس", "بگیر", "اوکی", "باشه", "انجام بده", "دقیقا", "میخوام", "می‌خوام", "بزن", "تماس بگیر"
    )
    
    private val negativeKeywords = listOf(
        "لغو", "کنسل", "نه", "انصراف", "نمیخوام", "نمی‌خوام", "متوقف", "برو", "بسه", "بیخیر", "نهه"
    )
    
    // کلمات کلیدی برای کنترل بلندگو
    private val speakerOnKeywords = listOf(
        "بلندگو روشن", "اسپیکر روشن", "روشن شدن بلندگو", "روشن کردن بلندگو", 
        "بلندگو", "اسپیکر", "speaker", "خانوادگی", "جمعی", "گروهی"
    )
    
    private val speakerOffKeywords = listOf(
        "بلندگو خاموش", "اسپیکر خاموش", "خاموش شدن بلندگو", "خاموش کردن بلندگو",
        "گوشی", "موبایل", "دستگاه", "عادی", "معمولی", "خصوصی", "speaker off", "مخفی"
    )
    
    // حالت‌های هوشمند تماس
    private val smartCallModes = mapOf(
        "خصوصی" to false,      // بلندگو خاموش
        "مخفی" to false,       // بلندگو خاموش  
        "عادی" to false,       // بلندگو خاموش
        "معمولی" to false,     // بلندگو خاموش
        "خانوادگی" to true,    // بلندگو روشن
        "جمعی" to true,        // بلندگو روشن
        "گروهی" to true,       // بلندگو روشن
        "گفتگو" to true        // بلندگو روشن
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
            this@CallSession.isActive = true
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
                    this@CallSession.isActive = false
                }
            }
        }
        
        fun cancel() {
            this@CallSession.isActive = false
            job?.cancel()
        }
        
        private suspend fun askForConfirmation() {
            if (!this@CallSession.isActive) return
            
            // بلندگو به طور پیش‌فرض فعال نمی‌شود - کاربر انتخاب می‌کند
            val message = "با ${contact.name} تماس بگیرم؟ برای تأیید بگویید بله، برای لغو بگویید لغو. اگر می‌خواهید با بلندگو صحبت کنید، بگویید بلندگو روشن"
            Log.d(TAG, "📢 پرسش تأیید: $message")
            
            // استفاده از TTSHelper با اولویت GapGPT آنلاین
            ttsHelper.speakOnlineFirst(message)
            
            // نمایش صفحه تأیید تماس
            showConfirmationOverlay()
        }
        
        private fun enableSpeakerphone() {
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.isSpeakerphoneOn = true
                Log.d(TAG, "🔊 بلندگو فعال شد")
            } catch (e: Exception) {
                Log.e(TAG, "❌ خطا در فعال کردن بلندگو", e)
            }
        }
        
        private fun disableSpeakerphone() {
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.isSpeakerphoneOn = false
                Log.d(TAG, "🔇 بلندگو غیرفعال شد")
            } catch (e: Exception) {
                Log.e(TAG, "❌ خطا در غیرفعال کردن بلندگو", e)
            }
        }
        
        private suspend fun listenForResponse(): String {
            if (!this@CallSession.isActive) return ""
            
            delay(1000) // کمی صبر برای تمام شدن TTS
            
            Log.d(TAG, "🎤 فعال کردن شناسایی صدا برای پاسخ کاربر...")
            
            // منتظر ماندن برای پاسخ با timeout
            val timeoutMs = 8000L // 8 ثانیه برای پاسخ
            val startTime = System.currentTimeMillis()
            
            return withContext(Dispatchers.IO) {
                try {
                    // استفاده از OnlineSTTService برای شناسایی صدا (Liara → GapGPT)
                    val audioFile = createTempAudioFile()
                    val sttResult = onlineSTT.transcribeAudio(audioFile)
                    
                    val transcribedText = if (sttResult.isSuccess) sttResult.text else ""
                    
                    // بررسی سکوت یا timeout
                    val elapsedTime = System.currentTimeMillis() - startTime
                    if (transcribedText.isBlank() && elapsedTime > timeoutMs) {
                        Log.d(TAG, "⏰ کاربر سکوت کرد - لغو خودکار تماس")
                        withContext(Dispatchers.Main) {
                            ttsHelper.speakOnlineFirst("به دلیل عدم پاسخ، تماس لغو شد")
                        }
                        return@withContext "SILENCE_TIMEOUT"
                    }
                    
                    Log.d(TAG, "✅ پاسخ شناسایی شد: $transcribedText")
                    
                    transcribedText
                } catch (e: Exception) {
                    Log.e(TAG, "❌ خطا در شناسایی صدا", e)
                    ""
                } finally {
                    // غیرفعال کردن بلندگو پس از اتمام
                    withContext(Dispatchers.Main) {
                        disableSpeakerphone()
                    }
                }
            }
        }
        
        private fun createTempAudioFile(): File {
            val tempDir = File(context.cacheDir, "temp_audio")
            if (!tempDir.exists()) tempDir.mkdirs()
            return File(tempDir, "temp_recording_${System.currentTimeMillis()}.wav")
        }
        
        private suspend fun processResponse(response: String) {
            if (!this@CallSession.isActive) return
            
            Log.d(TAG, "📝 پاسخ کاربر: '$response'")
            
            val normalizedResponse = response.lowercase().trim()
            
            when {
                response == "SILENCE_TIMEOUT" -> {
                    Log.d(TAG, "⏰ لغو به دلیل سکوت کاربر")
                    // پیام قبلاً در listenForResponse گفته شد
                }
                
                // تشخیص هوشمند حالت تماس با مدل آنلاین
                smartCallModes.containsKey(normalizedResponse) -> {
                    val enableSpeaker = smartCallModes[normalizedResponse] ?: false
                    Log.d(TAG, "🎯 حالت هوشمند تشخیص داده شد: $normalizedResponse (بلندگو: $enableSpeaker)")
                    
                    if (enableSpeaker) {
                        enableSpeakerphone()
                        scope.launch {
                            ttsHelper.speakOnlineFirst("حالت $normalizedResponse فعال شد. بلندگو روشن است. برای تأیید تماس بگویید بله")
                        }
                    } else {
                        disableSpeakerphone()
                        scope.launch {
                            ttsHelper.speakOnlineFirst("حالت $normalizedResponse فعال شد. برای تأیید تماس بگویید بله")
                        }
                    }
                    
                    // دوباره منتظر تأیید نهایی بمانیم
                    delay(2000)
                    val finalResponse = listenForResponse()
                    processFinalConfirmation(finalResponse)
                    return
                }
                
                // کنترل مستقیم بلندگو
                speakerOnKeywords.any { it in normalizedResponse } -> {
                    Log.d(TAG, "� کاربر خواست بلندگو روشن شود")
                    enableSpeakerphone()
                    scope.launch {
                        ttsHelper.speakOnlineFirst("بلندگو روشن شد. برای تأیید تماس بگویید بله یا برای لغو بگویید لغو")
                    }
                    delay(2000)
                    val finalResponse = listenForResponse()
                    processFinalConfirmation(finalResponse)
                    return
                }
                
                speakerOffKeywords.any { it in normalizedResponse } -> {
                    Log.d(TAG, "🔇 کاربر خواست بلندگو خاموش شود")
                    disableSpeakerphone()
                    scope.launch {
                        ttsHelper.speakOnlineFirst("بلندگو خاموش شد. برای تأیید تماس بگویید بله یا برای لغو بگویید لغو")
                    }
                    delay(2000)
                    val finalResponse = listenForResponse()
                    processFinalConfirmation(finalResponse)
                    return
                }
                
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
                    Log.d(TAG, "❓ پاسخ خالی")
                    scope.launch {
                        ttsHelper.speakOnlineFirst("لطفاً بگویید بله برای تماس یا لغو برای انصراف")
                    }
                    // دوباره تلاش کن
                    delay(2000)
                    val retryResponse = listenForResponse()
                    processResponse(retryResponse)
                    return
                }
                
                else -> {
                    Log.d(TAG, "❓ پاسخ نامشخص - تلاش مجدد")
                    scope.launch {
                        ttsHelper.speakOnlineFirst("متوجه نشدم. لطفاً بگویید بله، لغو، یا حالت تماس را مشخص کنید")
                    }
                    // دوباره تلاش کن
                    delay(2000)
                    val retryResponse = listenForResponse()
                    processResponse(retryResponse)
                    return
                }
            }
            
            hideConfirmationOverlay()
        }
        
        private suspend fun processFinalConfirmation(response: String) {
            val normalizedResponse = response.lowercase().trim()
            
            when {
                positiveKeywords.any { it in normalizedResponse } -> {
                    Log.d(TAG, "✅ تأیید نهایی - برقراری تماس")
                    scope.launch {
                        ttsHelper.speakOnlineFirst("در حال برقراری تماس...")
                    }
                    makePhoneCall()
                }
                
                negativeKeywords.any { it in normalizedResponse } -> {
                    Log.d(TAG, "❌ لغو نهایی - تماس لغو شد")
                    scope.launch {
                        ttsHelper.speakOnlineFirst("تماس لغو شد")
                    }
                }
                
                else -> {
                    Log.d(TAG, "❓ پاسخ نامشخص - لغو خودکار")
                    scope.launch {
                        ttsHelper.speakOnlineFirst("پاسخ نامشخص بود، تماس لغو شد")
                    }
                }
            }
            
            hideConfirmationOverlay()
        }
        
        private fun makePhoneCall() {
            try {
                // غیرفعال کردن بلندگو قبل از تماس
                disableSpeakerphone()
                
                val intent = Intent(Intent.ACTION_CALL).apply {
                    data = Uri.parse("tel:$phoneNumber")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                Log.d(TAG, "📞 تماس با $phoneNumber از طریق شماره‌گیر گوشی برقرار شد")
                
                // اطلاع به کاربر درباره حالت‌های تماس
                scope.launch {
                    delay(1000)
                    ttsHelper.speakOnlineFirst("تماس برقرار شد. می‌توانید از بلندگو، هندزفری یا بلوتوث استفاده کنید")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ خطا در برقراری تماس", e)
                scope.launch {
                    ttsHelper.speakOnlineFirst("خطا در برقراری تماس")
                }
            }
        }
        
        private fun showConfirmationOverlay() {
            if (!this@CallSession.isActive) return
            
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
