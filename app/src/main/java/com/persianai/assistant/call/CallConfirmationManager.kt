package com.persianai.assistant.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.media.AudioManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import com.persianai.assistant.tts.TTSHelper
import com.persianai.assistant.preferences.PreferencesManager
import com.persianai.assistant.data.Contact
import com.persianai.assistant.ui.CallConfirmationActivity
import com.persianai.assistant.ivira.IviraIntegrationManager
import com.persianai.assistant.stt.OnlineSTTService
import com.persianai.assistant.voice.UnifiedVoiceEngine
import java.io.File

class CallConfirmationManager(private val context: Context) {
    
    private val TAG = "CallConfirmationManager"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private val ttsHelper = TTSHelper.getInstance(context)
    private val prefsManager = PreferencesManager.getInstance(context)
    private val iviraManager = IviraIntegrationManager.getInstance(context)
    private val onlineSTT = OnlineSTTService.getInstance(context)
    private val voiceEngine = UnifiedVoiceEngine.getInstance(context)
    
    private var retryCount = 0
    private val maxRetries = 2
    
    // کلمات کلیدی برای تشخیص پاسخ
    private val positiveKeywords = listOf(
        "بله", "آره", "باشه", "تماس", "بگیر", "زنگ بزن", "برقرار کن", "اوکی", "ok", "yes"
    )
    
    private val negativeKeywords = listOf(
        "نه", "خیر", "لغو", "نمیخوام", "نمی خوام", "بیخیال", "cancel", "no"
    )
    
    private val speakerOnKeywords = listOf(
        "بلندگو", "اسپیکر", "بلند", "speaker", "speakerphone"
    )
    
    private val speakerOffKeywords = listOf(
        "گوشی", "عادی", "normal", "earpiece"
    )
    
    // حالت‌های تماس هوشمند
    private val smartCallModes = listOf(
        "بلندگو", "اسپیکر", "گوشی", "عادی", "speaker", "speakerphone", "normal", "earpiece"
    )
    
    private var currentCallSession: CallSession? = null
    
    inner class CallSession(
        val contact: Contact,
        val phoneNumber: String
    ) {
        var isActive = false
        
        fun start() {
            isActive = true
            scope.launch {
                try {
                    Log.d(TAG, "🎯 شروع جلسه تأیید تماس با ${contact.name}")
                    askForConfirmation()
                } catch (e: Exception) {
                    Log.e(TAG, "❌ خطا در شروع جلسه تأیید", e)
                    isActive = false
                }
            }
        }
        
        fun cancel() {
            isActive = false
            Log.d(TAG, "❌ لغو جلسه تأیید تماس")
        }
    }
    
    fun startCallConfirmation(contact: Contact, phoneNumber: String) {
        Log.d(TAG, "📞 درخواست تأیید تماس با ${contact.name}")
        
        // لغو جلسه قبلی در صورت وجود
        currentCallSession?.cancel()
        
        // ایجاد جلسه جدید
        currentCallSession = CallSession(contact, phoneNumber).apply {
            start()
        }
    }
    
    fun cancelCurrentConfirmation() {
        currentCallSession?.cancel()
        currentCallSession = null
        retryCount = 0
    }
    
    private suspend fun askForConfirmation() {
        val session = currentCallSession ?: return
        
        if (!session.isActive) {
            Log.d(TAG, "⚠️ جلسه غیرفعال است")
            return
        }
        
        retryCount = 0
        
        // نمایش overlay تأیید
        showConfirmationOverlay()
        
        val message = "آیا می‌خواهید با ${session.contact.name} تماس بگیرید؟"
        Log.d(TAG, "🎤 پرسش تأیید: $message")
        
        // پخش پیام و انتظار واقعی برای پایان TTS
        ttsHelper.speakOnlineFirstAndWait(message)
        
        // شروع شنود پاسخ بدون delay ثابت
        listenForResponse()
    }
    
    private suspend fun listenForResponse() {
        val session = currentCallSession ?: return
        
        if (!session.isActive) {
            Log.d(TAG, "⚠️ جلسه غیرفعال است")
            return
        }
        
        Log.d(TAG, "👂 شروع شنود پاسخ کاربر...")
        
        try {
            // شروع ضبط صدا بدون delay
            voiceEngine.startRecording()
            
            // انتظار برای دریافت فایل صوتی
            delay(3000) // زمان ضبط
            
            val audioFile = voiceEngine.stopRecording()
            
            if (audioFile == null || !audioFile.exists()) {
                Log.e(TAG, "❌ فایل صوتی دریافت نشد")
                handleRetry("فایل صوتی دریافت نشد")
                return
            }
            
            Log.d(TAG, "🎵 فایل صوتی دریافت شد: ${audioFile.absolutePath}")
            
            // ارسال به STT
            val transcription = onlineSTT.transcribeAudio(audioFile)
            
            if (transcription.isNullOrBlank()) {
                Log.w(TAG, "⚠️ متن خالی دریافت شد")
                handleRetry("SILENCE_TIMEOUT")
            } else {
                Log.d(TAG, "📝 متن دریافتی: $transcription")
                processResponse(transcription)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطا در شنود پاسخ", e)
            handleRetry("خطا در شنود")
        }
    }
    
    private suspend fun handleRetry(reason: String) {
        val session = currentCallSession ?: return
        
        if (!session.isActive) return
        
        retryCount++
        
        if (retryCount >= maxRetries) {
            Log.w(TAG, "⚠️ تعداد تلاش‌ها به حداکثر رسید")
            ttsHelper.speakOnlineFirstAndWait("متأسفم، پاسخی دریافت نشد. تماس لغو شد")
            cancelCurrentConfirmation()
            hideConfirmationOverlay()
        } else {
            Log.d(TAG, "🔄 تلاش مجدد ($retryCount از $maxRetries)")
            ttsHelper.speakOnlineFirstAndWait("متوجه نشدم. لطفاً دوباره بگویید: بله یا خیر؟")
            listenForResponse()
        }
    }
    
    private suspend fun processResponse(response: String) {
        val session = currentCallSession ?: return
        
        if (!session.isActive) {
            Log.d(TAG, "⚠️ جلسه غیرفعال است")
            return
        }
        
        Log.d(TAG, "🔍 پردازش پاسخ: $response")
        
        val normalizedResponse = response.lowercase().trim()
        
        // ریست تعداد تلاش برای پاسخ‌های معتبر
        if (normalizedResponse.isNotBlank() && normalizedResponse != "silence_timeout") {
            retryCount = 0
        }
        
        when {
            normalizedResponse == "silence_timeout" -> {
                handleRetry("SILENCE_TIMEOUT")
            }
            
            // بررسی حالت‌های تماس هوشمند
            smartCallModes.any { normalizedResponse.contains(it) } -> {
                Log.d(TAG, "🔊 تشخیص حالت تماس هوشمند")
                
                when {
                    speakerOnKeywords.any { normalizedResponse.contains(it) } -> {
                        enableSpeakerphone()
                        ttsHelper.speakOnlineFirstAndWait("بلندگو فعال شد. آیا تماس برقرار شود?")
                    }
                    speakerOffKeywords.any { normalizedResponse.contains(it) } -> {
                        disableSpeakerphone()
                        ttsHelper.speakOnlineFirstAndWait("حالت عادی فعال شد. آیا تماس برقرار شود?")
                    }
                }
                
                // انتظار برای پاسخ نهایی
                delay(500)
                listenForResponse()
                val finalResponse = onlineSTT.transcribeAudio(voiceEngine.stopRecording() ?: return)
                processFinalConfirmation(finalResponse ?: "")
            }
            
            // بررسی کلمات کلیدی بلندگو
            speakerOnKeywords.any { normalizedResponse.contains(it) } -> {
                Log.d(TAG, "🔊 درخواست فعال‌سازی بلندگو")
                enableSpeakerphone()
                ttsHelper.speakOnlineFirstAndWait("بلندگو فعال شد. آیا تماس برقرار شود?")
                listenForResponse()
            }
            
            speakerOffKeywords.any { normalizedResponse.contains(it) } -> {
                Log.d(TAG, "🔇 درخواست غیرفعال‌سازی بلندگو")
                disableSpeakerphone()
                ttsHelper.speakOnlineFirstAndWait("حالت عادی فعال شد. آیا تماس برقرار شود?")
                listenForResponse()
            }
            
            // بررسی پاسخ مثبت
            positiveKeywords.any { normalizedResponse.contains(it) } -> {
                Log.d(TAG, "✅ پاسخ مثبت دریافت شد")
                ttsHelper.speakOnlineFirstAndWait("در حال برقراری تماس...")
                makePhoneCall()
            }
            
            // بررسی پاسخ منفی
            negativeKeywords.any { normalizedResponse.contains(it) } -> {
                Log.d(TAG, "❌ پاسخ منفی دریافت شد")
                ttsHelper.speakOnlineFirstAndWait("تماس لغو شد")
                cancelCurrentConfirmation()
                hideConfirmationOverlay()
            }
            
            // پاسخ خالی
            normalizedResponse.isBlank() -> {
                handleRetry("پاسخ خالی")
            }
            
            // پاسخ نامشخص
            else -> {
                Log.d(TAG, "❓ پاسخ نامشخص - تلاش مجدد")
                handleRetry("پاسخ نامشخص")
            }
        }
        
        hideConfirmationOverlay()
    }
    
    private suspend fun processFinalConfirmation(response: String) {
        val normalizedResponse = response.lowercase().trim()
        
        when {
            positiveKeywords.any { normalizedResponse.contains(it) } -> {
                Log.d(TAG, "✅ تأیید نهایی دریافت شد")
                ttsHelper.speakOnlineFirstAndWait("در حال برقراری تماس...")
                makePhoneCall()
            }
            
            negativeKeywords.any { normalizedResponse.contains(it) } -> {
                Log.d(TAG, "❌ لغو نهایی دریافت شد")
                ttsHelper.speakOnlineFirstAndWait("تماس لغو شد")
                cancelCurrentConfirmation()
            }
            
            else -> {
                Log.d(TAG, "❓ پاسخ نهایی نامشخص")
                ttsHelper.speakOnlineFirstAndWait("پاسخ نامشخص بود، تماس لغو شد")
                cancelCurrentConfirmation()
            }
        }
        
        hideConfirmationOverlay()
    }
    
    private suspend fun makePhoneCall() {
        val session = currentCallSession ?: return
        
        disableSpeakerphone()
        
        val callMode = prefsManager.getCallMode()
        
        Log.d(TAG, "📞 برقراری تماس با ${session.contact.name} - حالت: $callMode")
        
        try {
            val intent = when (callMode) {
                PreferencesManager.CallMode.DIALER -> {
                    Intent(Intent.ACTION_DIAL).apply {
                        data = Uri.parse("tel:${session.phoneNumber}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                }
                
                PreferencesManager.CallMode.DIRECT -> {
                    // بررسی مجوز CALL_PHONE
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) 
                        != PackageManager.PERMISSION_GRANTED) {
                        Log.e(TAG, "❌ مجوز CALL_PHONE وجود ندارد")
                        ttsHelper.speakOnlineFirstAndWait("مجوز تماس مستقیم وجود ندارد")
                        return
                    }
                    
                    Intent(Intent.ACTION_CALL).apply {
                        data = Uri.parse("tel:${session.phoneNumber}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                }
            }
            
            // بررسی وجود برنامه مدیریت‌کننده Intent
            if (intent.resolveActivity(context.packageManager) == null) {
                Log.e(TAG, "❌ برنامه مدیریت تماس یافت نشد")
                ttsHelper.speakOnlineFirstAndWait("خطا در برقراری تماس")
                return
            }
            
            context.startActivity(intent)
            
            when (callMode) {
                PreferencesManager.CallMode.DIALER -> {
                    ttsHelper.speakOnlineFirstAndWait("شماره در شماره‌گیر باز شد. برای تماس دکمه تماس را بزنید")
                }
                PreferencesManager.CallMode.DIRECT -> {
                    ttsHelper.speakOnlineFirstAndWait("در حال برقراری تماس مستقیم")
                }
            }
            
            Log.d(TAG, "✅ تماس با موفقیت آغاز شد")
            
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ خطای مجوز در برقراری تماس", e)
            ttsHelper.speakOnlineFirstAndWait("مجوز تماس وجود ندارد")
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطا در برقراری تماس", e)
            ttsHelper.speakOnlineFirstAndWait("خطا در برقراری تماس")
        } finally {
            cancelCurrentConfirmation()
        }
    }
    
    private fun enableSpeakerphone() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = true
            Log.d(TAG, "🔊 بلندگو فعال شد")
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطا در فعال‌سازی بلندگو", e)
        }
    }
    
    private fun disableSpeakerphone() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
            Log.d(TAG, "🔇 بلندگو غیرفعال شد")
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطا در غیرفعال‌سازی بلندگو", e)
        }
    }
    
    private fun showConfirmationOverlay() {
        val session = currentCallSession ?: return
        
        if (!session.isActive) return
        
        Log.d(TAG, "📱 نمایش صفحه تأیید تماس")
        CallConfirmationActivity.start(context, session.contact)
    }
    
    private fun hideConfirmationOverlay() {
        // TODO: پیاده‌سازی مخفی کردن overlay
        Log.d(TAG, "📱 مخفی کردن صفحه تأیید تماس")
    }
}
