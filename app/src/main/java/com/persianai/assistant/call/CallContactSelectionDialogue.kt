package com.persianai.assistant.call

import android.content.Context
import android.provider.ContactsContract
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import android.util.Log
import com.persianai.assistant.R
import com.persianai.assistant.models.Contact
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
 * مدیر مکالمه صوتی برای انتخاب مخاطب از لیست
 * مکالمه چندمرحله‌ای: TTS خواندن گزینه‌ها + STT گرفتن پاسخ
 */
class CallContactSelectionDialogue(
    private val context: Context,
    private val contacts: List<Contact>
) {
    private val TAG = "CallContactSelectionDialogue"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val ttsHelper = TTSHelper(context)
    private val onlineSTT = OnlineSTTService(context)
    private val aiAssistant = AdvancedPersianAssistant(context)
    
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val NOTIFICATION_ID = 2002
    private val CHANNEL_ID = "call_selection_dialog_channel"
    
    init {
        createNotificationChannel()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Call Selection Dialogue",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Status updates for voice contact selection"
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
     * شروع مکالمه انتخاب مخاطب
     */
    suspend fun startSelectionDialogue() {
        try {
            // مرحله ۱: خواندن لیست مخاطبین با TTS
            speakContactsList()

            // مرحله ۲: شنود برای پاسخ کاربر
            val response = listenForUserResponse()

            // مرحله ۳: تحلیل پاسخ با AI آنلاین
            processUserResponseWithAI(response)

        } catch (e: Exception) {
            Log.e(TAG, "❌ خطا در مکالمه انتخاب مخاطب", e)
            cancelNotification()
            scope.launch {
                ttsHelper.speakOnlineFirst("خطا در انتخاب مخاطب")
            }
        }
    }

    /**
     * خواندن لیست مخاطبین با TTS - ساده شده
     */
    private suspend fun speakContactsList() {
        val contactsText = contacts.take(3).joinToString("، ") { contact ->
            val formattedPhone = TTSHelper.formatPhoneNumberForTTS(contact.phoneNumber)
            "${contact.name} با شماره $formattedPhone"
        }

        val message = "مخاطبین: $contactsText. لطفاً نام مخاطب را بگویید."
        Log.d(TAG, "📢 خواندن لیست مخاطبین: $message")

        ttsHelper.speakOnlineFirstAndWait(message)
        delay(300)
    }

    /**
     * شنود برای پاسخ کاربر
     */
    private suspend fun listenForUserResponse(): String = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🎤 شروع ضبط صدا برای پاسخ کاربر")
            
            val engine = UnifiedVoiceEngine(context)
            val tempFile = createTempAudioFile()
            
            // شروع ضبط با VAD
            updateNotification("🎧 در حال ضبط صدا", "در حال شنیدن نام مخاطب...")
            val startResult = engine.startRecording()
            if (!startResult.isSuccess) {
                Log.e(TAG, "❌ خطا در شروع ضبط: ${startResult.exceptionOrNull()?.message}")
                cancelNotification()
                return@withContext ""
            }
            
            Log.d(TAG, "✅ ضبط صدا شروع شد")
            
            // منتظر مکث یا timeout
            val timeoutMs = 12000L // 12 ثانیه - افزایش زمان برای انتخاب مخاطب
            val silenceStopMs = 3000L // 3 ثانیه سکوت
            val startTime = System.currentTimeMillis()
            var lastSpeechTime = startTime
            var hasSpeech = false
            
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                val now = System.currentTimeMillis()
                val amplitude = engine.getCurrentAmplitude()
                
                if (amplitude > 100) {
                    hasSpeech = true
                    lastSpeechTime = now
                    if (!hasSpeech) {
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
                cancelNotification()
                withContext(Dispatchers.Main) {
                    ttsHelper.speakOnlineFirst("خطا در ضبط صدا، لطفاً دوباره تلاش کنید")
                }
                return@withContext ""
            }

            val recordedFile = recordingResult.file

            if (!recordedFile.exists()) {
                Log.e(TAG, "❌ فایل صوتی وجود ندارد: ${recordedFile.absolutePath}")
                cancelNotification()
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
                Log.d(TAG, "⏰ کاربر سکوت کرد - لغو خودکار")
                cancelNotification()
                withContext(Dispatchers.Main) {
                    ttsHelper.speakOnlineFirst("به دلیل عدم پاسخ، انتخاب لغو شد")
                }
                return@withContext "SILENCE_TIMEOUT"
            }
            
            // بررسی کلمات لغو
            val normalizedText = transcribedText.lowercase().trim()
            if (normalizedText.contains("لغو") || normalizedText.contains("کنسل") || normalizedText.contains("نه") || 
                normalizedText.contains("تموم") || normalizedText.contains("بس") || normalizedText.contains("تمام")) {
                Log.d(TAG, "❌ کاربر لغو کرد: $transcribedText")
                cancelNotification()
                withContext(Dispatchers.Main) {
                    ttsHelper.speakOnlineFirst("انتخاب لغو شد")
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
     * تحلیل پاسخ کاربر با AI آنلاین
     */
    private suspend fun processUserResponseWithAI(response: String) {
        Log.d(TAG, "🤖 تحلیل پاسخ با AI: $response")

        when {
            response == "SILENCE_TIMEOUT" -> {
                Log.d(TAG, "⏰ لغو به دلیل سکوت کاربر")
                withContext(Dispatchers.Main) {
                    ttsHelper.speakOnlineFirst("به دلیل سکوت، انتخاب لغو شد")
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
            val contactsList = contacts.joinToString("\n") { "${it.name}: ${it.phoneNumber}" }
            val prompt = """
                کاربر گفت: "$response"

                مخاطبین موجود:
                $contactsList

                لطفاً تحلیل کن:
                1. آیا کاربر می‌خواهد لغو کند؟ (کلماتی مثل: لغو، نه، کنسل، انصراف)
                2. اگر لغو نکرده، کدام مخاطب را انتخاب کرده؟
                3. اگر نام دقیق نگفته، بهترین تطابق را پیدا کن

                فقط در یک کلمه پاسخ بده:
                - "CANCEL" اگر لغو کرده
                - نام دقیق مخاطب اگر انتخاب کرده
                - "NONE" اگر مشخص نیست
            """.trimIndent()

            // تحلیل با AI
            val aiResponse = aiAssistant.processRequestWithAI(prompt)
            val analysis = aiResponse.text.lowercase().trim()

            Log.d(TAG, "🤖 پاسخ AI: $analysis")

            when {
                response == "SILENCE_TIMEOUT" -> {
                    Log.d(TAG, "⏰ کاربر سکوت کرد - لغو خودکار")
                    // پیام قبلاً در listenForUserResponse گفته شد
                }
                
                response == "CANCEL" -> {
                    Log.d(TAG, "❌ کاربر لغو کرد")
                    // پیام قبلاً در listenForUserResponse گفته شد
                }
                
                analysis.contains("cancel") -> {
                    Log.d(TAG, "❌ AI تشخیص داد: لغو")
                    withContext(Dispatchers.Main) {
                        ttsHelper.speakOnlineFirst("انتخاب لغو شد")
                    }
                }

                analysis.contains("none") -> {
                    Log.d(TAG, "❓ AI تشخیص داد: نامشخص")
                    withContext(Dispatchers.Main) {
                        ttsHelper.speakOnlineFirst("متوجه نشدم، لطفاً دوباره تلاش کنید")
                    }
                }

                else -> {
                    // AI نام مخاطب را تشخیص داده
                    val selectedContact = findContactByName(analysis)
                    if (selectedContact != null) {
                        Log.d(TAG, "✅ مخاطب انتخاب شد: ${selectedContact.name}")
                        startCallConfirmation(selectedContact)
                    } else {
                        // تلاش برای استخراج شماره تلفن از پاسخ کاربر
                        val phoneNumber = extractPhoneNumberFromText(response)
                        if (phoneNumber.isNotBlank()) {
                            Log.d(TAG, "📞 شماره تلفن از پاسخ استخراج شد: $phoneNumber")
                            startDirectCallConfirmation(phoneNumber)
                        } else {
                            Log.d(TAG, "❌ مخاطب یا شماره یافت نشد: $analysis")
                            withContext(Dispatchers.Main) {
                                ttsHelper.speakOnlineFirst("مخاطب یافت نشد. اگر شماره تلفن می‌خواهید تماس بگیرید، لطفاً شماره را بگویید")
                            }
                        }
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ خطا در تحلیل AI", e)
            withContext(Dispatchers.Main) {
                ttsHelper.speakOnlineFirst("خطا در تحلیل پاسخ")
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
     * شروع تأیید تماس مستقیم با شماره تلفن
     */
    private suspend fun startDirectCallConfirmation(phoneNumber: String) {
        try {
            val formattedPhone = TTSHelper.formatPhoneNumberForTTS(phoneNumber)
            val confirmMessage = "با شماره $formattedPhone تماس بگیرم؟"
            Log.d(TAG, "📢 درخواست تأیید تماس مستقیم: $confirmMessage")
            
            ttsHelper.speakOnlineFirstAndWait(confirmMessage)
            delay(300)
            // منتظر تأیید کاربر
            val response = listenForUserResponse()
            processDirectCallResponse(response, phoneNumber)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطا در تأیید تماس مستقیم", e)
            withContext(Dispatchers.Main) {
                ttsHelper.speakOnlineFirst("خطا در تأیید تماس")
            }
        }
    }
    
    /**
     * پردازش پاسخ کاربر برای تماس مستقیم
     */
    private suspend fun processDirectCallResponse(response: String, phoneNumber: String) {
        if (response.isBlank()) {
            Log.d(TAG, "⏰ کاربر سکوت کرد - لغو خودکار تماس مستقیم")
            withContext(Dispatchers.Main) {
                ttsHelper.speakOnlineFirst("به دلیل عدم پاسخ، تماس لغو شد")
            }
            return
        }
        
        val normalizedResponse = response.lowercase().trim()
        
        when {
            normalizedResponse.contains("لغو") || normalizedResponse.contains("کنسل") || normalizedResponse.contains("نه") -> {
                Log.d(TAG, "❌ کاربر تماس مستقیم را لغو کرد")
                withContext(Dispatchers.Main) {
                    ttsHelper.speakOnlineFirst("تماس لغو شد")
                }
            }
            
            normalizedResponse.contains("بله") || normalizedResponse.contains("آره") || normalizedResponse.contains("تمام") -> {
                Log.d(TAG, "✅ کاربر تأیید تماس مستقیم کرد")
                withContext(Dispatchers.Main) {
                    ttsHelper.speakOnlineFirst("در حال برقراری تماس...")
                }
                makeDirectPhoneCall(phoneNumber)
            }
            
            else -> {
                // تحلیل با AI برای پاسخ‌های مبهم
                val prompt = """
                    کاربر به پرسش "با شماره $phoneNumber تماس بگیرم؟" این پاسخ را داده: "$response"
                    
                    لطفاً مشخص کن:
                    1. آیا کاربر تأیید کرده؟
                    2. آیا لغو کرده؟
                    3. یا نامشخص است؟
                    
                    فقط در یک کلمه پاسخ بده:
                    - "CONFIRM" اگر تأیید کرده
                    - "CANCEL" اگر لغو کرده  
                    - "UNCLEAR" اگر نامشخص است
                """.trimIndent()
                
                try {
                    val aiResponse = aiAssistant.processRequestWithAI(prompt)
                    val analysis = aiResponse.text.lowercase().trim()
                    
                    when {
                        analysis.contains("confirm") -> {
                            Log.d(TAG, "✅ AI تأیید تماس مستقیم را تشخیص داد")
                            withContext(Dispatchers.Main) {
                                ttsHelper.speakOnlineFirst("در حال برقراری تماس...")
                            }
                            makeDirectPhoneCall(phoneNumber)
                        }
                        
                        analysis.contains("cancel") -> {
                            Log.d(TAG, "❌ AI لغو تماس مستقیم را تشخیص داد")
                            withContext(Dispatchers.Main) {
                                ttsHelper.speakOnlineFirst("تماس لغو شد")
                            }
                        }
                        
                        else -> {
                            Log.d(TAG, "❓ پاسخ نامشخص برای تماس مستقیم")
                            withContext(Dispatchers.Main) {
                                ttsHelper.speakOnlineFirst("متوجه نشدم، لطفاً دوباره تلاش کنید")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ خطا در تحلیل AI برای تماس مستقیم", e)
                    withContext(Dispatchers.Main) {
                        ttsHelper.speakOnlineFirst("خطا در تحلیل پاسخ")
                    }
                }
            }
        }
    }
    
    /**
     * برقراری تماس مستقیم
     */
    private fun makeDirectPhoneCall(phoneNumber: String) {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_CALL).apply {
                data = android.net.Uri.parse("tel:$phoneNumber")
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Log.d(TAG, "✅ تماس مستقیم برقرار شد: $phoneNumber")
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطا در برقراری تماس مستقیم", e)
            scope.launch {
                ttsHelper.speakOnlineFirst("خطا در برقراری تماس")
            }
        }
    }
    
    /**
     * شروع تأیید دو مرحله‌ای تماس
     */
    private suspend fun startCallConfirmation(contact: Contact) {
        try {
            val confirmationManager = CallConfirmationManager(context)
            
            // استفاده از شماره اصلی مخاطب
            val phoneNumber = contact.phoneNumber
            
            // خواندن اطلاعات تماس برای تأیید
            val formattedPhone = TTSHelper.formatPhoneNumberForTTS(phoneNumber)
            val confirmMessage = "با ${contact.name} به شماره $formattedPhone تماس بگیرم؟"
            Log.d(TAG, "📢 درخواست تأیید تماس: $confirmMessage")
            
            ttsHelper.speakOnlineFirstAndWait(confirmMessage)
            delay(300)
            
            // مرحله ۲: منتظر تأیید کاربر
            val confirmationResponse = listenForUserResponse()
            
            // تحلیل پاسخ تأیید با AI
            processConfirmationResponse(contact, phoneNumber, confirmationResponse)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطا در شروع تأیید تماس", e)
            withContext(Dispatchers.Main) {
                ttsHelper.speakOnlineFirst("خطا در تأیید تماس")
            }
        }
    }
    
    /**
     * تحلیل پاسخ تأیید با AI آنلاین
     */
    private suspend fun processConfirmationResponse(contact: Contact, phoneNumber: String, response: String) {
        Log.d(TAG, "🤖 تحلیل پاسخ تأیید: $response")
        
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
                کاربر برای تأیید تماس گفت: "$response"
                
                لطفاً تحلیل کن آیا کاربر تمایل به تماس دارد:
                - کلمات تأیید: بله، آره، تماس بگیر، اوکی، باشه، انجام بده
                - کلمات لغو: لغو، نه، کنسل، انصراف، نمیخوام
                
                فقط در یک کلمه پاسخ بده:
                - "CONFIRM" اگر تأیید کرده
                - "CANCEL" اگر لغو کرده
                - "NONE" اگر مشخص نیست
            """.trimIndent()
            
            // تحلیل با AI
            val aiResponse = aiAssistant.processRequestWithAI(prompt)
            val analysis = aiResponse.text.lowercase().trim()
            
            Log.d(TAG, "🤖 پاسخ AI تأیید: $analysis")
            
            when {
                analysis.contains("confirm") -> {
                    Log.d(TAG, "✅ AI تشخیص داد: تأیید تماس")
                    withContext(Dispatchers.Main) {
                        val formattedPhone = TTSHelper.formatPhoneNumberForTTS(phoneNumber)
                        ttsHelper.speakOnlineFirst("در حال تماس با ${contact.name} با شماره $formattedPhone")
                    }
                    
                    // شروع تماس واقعی
                    val confirmationManager = CallConfirmationManager(context)
                    confirmationManager.startCallConfirmation(contact, phoneNumber)
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
     * جستجوی مخاطب بر اساس نام
     */
    private fun findContactByName(name: String): Contact? {
        val cleanName = name.lowercase().trim()
        
        return contacts.firstOrNull { contact ->
            contact.name.lowercase() == cleanName ||
            contact.name.lowercase().contains(cleanName) ||
            cleanName.contains(contact.name.lowercase())
        }
    }

    /**
     * ایجاد فایل صوتی موقت
     */
    private fun createTempAudioFile(): File {
        val timestamp = System.currentTimeMillis()
        val fileName = "temp_audio_$timestamp.wav"
        return File(context.cacheDir, fileName)
    }
}
