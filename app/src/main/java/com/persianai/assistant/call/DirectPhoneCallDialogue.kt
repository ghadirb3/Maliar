package com.persianai.assistant.call

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import android.util.Log
import com.persianai.assistant.R
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
    private val voiceEngine = UnifiedVoiceEngine(context)
    
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val NOTIFICATION_ID = 2001
    private val CHANNEL_ID = "call_dialog_channel"
    
    init {
        createNotificationChannel()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Call Dialogue",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Status updates for voice call confirmation"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun updateNotification(title: String, content: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun cancelNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }
    
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
            cancelNotification()
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
            
            // شروع ضبط صدا
            updateNotification("🎧 در حال ضبط صدا", "در حال شنیدن پاسخ شما...")
            val startResult = engine.startRecording()
            if (startResult.isFailure) {
                Log.e(TAG, "❌ خطا در شروع ضبط: ${startResult.exceptionOrNull()?.message}")
                withContext(Dispatchers.Main) {
                    ttsHelper.speakOnlineFirst("خطا در ضبط صدا، لطفاً دوباره تلاش کنید")
                }
                cancelNotification()
                return@withContext ""
            }
            
            Log.d(TAG, "✅ ضبط صدا شروع شد")
            
            // منتظر مکث یا timeout - بهینه شده برای سرعت
            val timeoutMs = 6000L // 6 ثانیه - کاهش از 12 ثانیه
            val silenceStopMs = 1500L // 1.5 ثانیه سکوت - کاهش از 2 ثانیه
            var lastSpeechTime = System.currentTimeMillis()
            var hasSpeech = false
            
            while (System.currentTimeMillis() - lastSpeechTime < timeoutMs) {
                val now = System.currentTimeMillis()
                val amplitude = engine.getCurrentAmplitude()
                
                if (amplitude > 100) {
                    hasSpeech = true
                    lastSpeechTime = now
                    if (hasSpeech) {
                        updateNotification("🎤 در حال ضبط صدا", "صدای شما شنیده شد، ادامه دهید...")
                    }
                }
                
                if (hasSpeech && (now - lastSpeechTime) > silenceStopMs) {
                    Log.d(TAG, "🔇 سکوت تشخیص داده شد - توقف ضبط")
                    break
                }
                
                delay(100)
            }
            
            updateNotification("📝 پردازش صدا", "در حال تبدیل گفتار به متن...")
            
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
                cancelNotification()
                withContext(Dispatchers.Main) {
                    ttsHelper.speakOnlineFirst("به دلیل سکوت، تماس لغو شد")
                }
                return@withContext "SILENCE_TIMEOUT"
            }
            
            // بررسی کلمات لغو (فقط کلمات کامل برای جلوگیری از false positive)
            val normalizedText = transcribedText.lowercase().trim()
            val negativePatterns = listOf(
                Regex("\\bلغو\\b"),
                Regex("\\bکنسل\\b"),
                Regex("\\bنه\\b"),
                Regex("\\bتموم\\b"),
                Regex("\\bبس\\b"),
                Regex("\\bتمام\\b")
            )
            val isNegative = negativePatterns.any { it.containsMatchIn(normalizedText) }
            if (isNegative) {
                Log.d(TAG, "❌ کاربر لغو کرد: $transcribedText")
                cancelNotification()
                withContext(Dispatchers.Main) {
                    ttsHelper.speakOnlineFirst("تماس لغو شد")
                }
                return@withContext "CANCEL"
            }
            
            Log.d(TAG, "✅ پاسخ کاربر: $transcribedText")
            cancelNotification()
            transcribedText
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطا در شناسایی صدا", e)
            cancelNotification()
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
                val normalizedResponse = response.lowercase().trim()
                
                when {
                    // بررسی کلمات تأیید - بهینه شده برای تشخیص سریع
                    normalizedResponse.contains("بله") || 
                    normalizedResponse.contains("آره") || 
                    normalizedResponse.contains("تماس") ||
                    normalizedResponse.contains("بگیر") ||
                    normalizedResponse.contains("بزن") ||
                    normalizedResponse.contains("کن") ||
                    normalizedResponse.contains("باشه") ||
                    normalizedResponse == "بله" ||
                    normalizedResponse == "آره" ||
                    normalizedResponse == "تماس" ||
                    normalizedResponse == "بگیر" ||
                    normalizedResponse == "بزن" -> {
                        
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
            Log.d(TAG, "📞 شروع تماس مستقیم با شماره: $phoneNumber")
            
            withContext(Dispatchers.Main) {
                // تماس مستقیم با اپلیکیشن تلفن اندروید
                val callIntent = android.content.Intent(android.content.Intent.ACTION_CALL).apply {
                    data = android.net.Uri.parse("tel:$phoneNumber")
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                
                try {
                    context.startActivity(callIntent)
                    Log.d(TAG, "✅ تماس با موفقیت شروع شد")
                    cancelNotification()
                } catch (e: SecurityException) {
                    Log.e(TAG, "❌ عدم دسترسی به تماس: ${e.message}")
                    ttsHelper.speakOnlineFirst("لطفاً دسترسی تماس را در تنظیمات فعال کنید")
                    
                    // تلاش با ACTION_DIAL به عنوان جایگزین
                    try {
                        val dialIntent = android.content.Intent(android.content.Intent.ACTION_DIAL).apply {
                            data = android.net.Uri.parse("tel:$phoneNumber")
                            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(dialIntent)
                        Log.d(TAG, "📱 صفحه شماره‌گیر باز شد")
                        ttsHelper.speakOnlineFirst("صفحه شماره‌گیر باز شد، لطفاً تماس را بگیرید")
                    } catch (e2: Exception) {
                        Log.e(TAG, "❌ خطا در باز کردن صفحه شماره‌گیر: ${e2.message}")
                        ttsHelper.speakOnlineFirst("خطا در شروع تماس، لطفاً دستی تماس بگیرید")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ خطا در شروع تماس: ${e.message}")
                    ttsHelper.speakOnlineFirst("خطا در شروع تماس")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطا کلی در makePhoneCall", e)
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
