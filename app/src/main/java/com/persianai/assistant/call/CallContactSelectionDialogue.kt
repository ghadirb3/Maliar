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
 * سیستم انتخاب عددی: کاربر میگه "یک"، "دو"، "سه" و...
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
            // مرحله ۱: خواندن لیست مخاطبین با شماره
            speakContactsList()

            // مرحله ۲: شنود برای پاسخ کاربر
            val response = listenForUserResponse()

            // مرحله ۳: تحلیل پاسخ عددی (بدون AI)
            processUserResponseWithNumber(response)

        } catch (e: Exception) {
            Log.e(TAG, "❌ خطا در مکالمه انتخاب مخاطب", e)
            cancelNotification()
            scope.launch {
                ttsHelper.speakOnlineFirst("خطا در انتخاب مخاطب")
            }
        }
    }

    /**
     * خواندن لیست مخاطبین با TTS - با شماره انتخاب
     */
    private suspend fun speakContactsList() {
        val contactsText = contacts.take(5).mapIndexed { index, contact ->
            val number = index + 1
            val persianNumber = convertToPersianNumber(number)
            val formattedPhone = TTSHelper.formatPhoneNumberForTTS(contact.phoneNumber)
            "گزینه $persianNumber: ${contact.name} با شماره $formattedPhone"
        }.joinToString("، ")

        val message = "$contactsText. لطفاً شماره مخاطب را بگویید. مثلاً بگویید: یک، یا دو، یا سه."
        Log.d(TAG, "📢 خواندن لیست مخاطبین: $message")

        ttsHelper.speakOnlineFirstAndWait(message)
        delay(300)
    }
    
    /**
     * تبدیل عدد به فارسی
     */
    private fun convertToPersianNumber(number: Int): String {
        return when (number) {
            1 -> "یک"
            2 -> "دو"
            3 -> "سه"
            4 -> "چهار"
            5 -> "پنج"
            6 -> "شش"
            7 -> "هفت"
            8 -> "هشت"
            9 -> "نه"
            10 -> "ده"
            else -> number.toString()
        }
    }
    
    /**
     * استخراج شماره از متن کاربر
     */
    private fun extractNumberFromText(text: String): Int {
        val cleanText = text.lowercase().trim()
        
        // تبدیل اعداد فارسی به انگلیسی
        val normalizedText = cleanText
            .replace("یک", "1")
            .replace("دو", "2")
            .replace("سه", "3")
            .replace("چهار", "4")
            .replace("پنج", "5")
            .replace("شش", "6")
            .replace("هفت", "7")
            .replace("هشت", "8")
            .replace("نه", "9")
            .replace("ده", "10")
            .replace("اول", "1")
            .replace("دوم", "2")
            .replace("سوم", "3")
            .replace("چهارم", "4")
            .replace("پنجم", "5")
        
        // جستجوی اعداد در متن
        val numberPattern = """\d+""".toRegex()
        val match = numberPattern.find(normalizedText)
        
        return match?.value?.toIntOrNull() ?: -1
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
            updateNotification("🎧 در حال ضبط صدا", "در حال شنیدن شماره...")
            val startResult = engine.startRecording()
            if (!startResult.isSuccess) {
                Log.e(TAG, "❌ خطا در شروع ضبط: ${startResult.exceptionOrNull()?.message}")
                cancelNotification()
                return@withContext ""
            }
            
            Log.d(TAG, "✅ ضبط صدا شروع شد")
            
            // منتظر مکث یا timeout
            val timeoutMs = 12000L // 12 ثانیه
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
            return@withContext ""
        }
    }

    /**
     * تحلیل پاسخ کاربر با سیستم انتخاب شماره (بدون AI)
     */
    private suspend fun processUserResponseWithNumber(response: String) {
        Log.d(TAG, "🔢 تحلیل پاسخ عددی: $response")

        when {
            response == "SILENCE_TIMEOUT" -> {
                Log.d(TAG, "⏰ لغو به دلیل سکوت کاربر")
                return
            }

            response == "CANCEL" -> {
                Log.d(TAG, "❌ کاربر لغو کرد")
                return
            }

            response.isBlank() -> {
                Log.d(TAG, "❌ پاسخ خالی")
                return
            }
        }

        try {
            // استخراج شماره از پاسخ کاربر
            val selectedNumber = extractNumberFromText(response)
            Log.d(TAG, "🔢 شماره استخراج شده: $selectedNumber")

            when {
                selectedNumber == -1 -> {
                    Log.d(TAG, "❌ شماره معتبری پیدا نشد")
                    withContext(Dispatchers.Main) {
                        ttsHelper.speakOnlineFirst("متوجه نشدم. لطفاً شماره را بگویید. مثلاً: یک یا دو")
                    }
                }
                
                selectedNumber < 1 || selectedNumber > contacts.size -> {
                    Log.d(TAG, "❌ شماره خارج از محدوده: $selectedNumber (مخاطبین: ${contacts.size})")
                    withContext(Dispatchers.Main) {
                        ttsHelper.speakOnlineFirst("شماره نامعتبر است. لطفاً بین یک تا ${convertToPersianNumber(contacts.size)} انتخاب کنید")
                    }
                }
                
                else -> {
                    // انتخاب موفقیت‌آمیز!
                    val selectedContact = contacts[selectedNumber - 1]
                    Log.d(TAG, "✅ مخاطب انتخاب شد: ${selectedContact.name} (${selectedContact.phoneNumber})")
                    
                    withContext(Dispatchers.Main) {
                        ttsHelper.speakOnlineFirst("منتظر بمانید، در حال تماس با ${selectedContact.name}")
                        delay(1000)
                        
                        // شروع تماس
                        CallModule.makeCall(context, selectedContact.phoneNumber, selectedContact.name)
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ خطا در تحلیل پاسخ", e)
            withContext(Dispatchers.Main) {
                ttsHelper.speakOnlineFirst("خطا در انتخاب، لطفاً دوباره تلاش کنید")
            }
        }
    }
    
    /**
     * ایجاد فایل صوتی موقت
     */
    private fun createTempAudioFile(): File {
        val timestamp = System.currentTimeMillis()
        return File(context.cacheDir, "temp_audio_$timestamp.wav")
    }
}
