package com.persianai.assistant.call

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import com.persianai.assistant.models.Contact
import com.persianai.assistant.utils.TTSHelper
import com.persianai.assistant.stt.OnlineSTTService
import com.persianai.assistant.services.UnifiedVoiceEngine
import com.persianai.assistant.services.RecordingResult
import com.persianai.assistant.ai.AdvancedPersianAssistant
import kotlinx.coroutines.*

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
            val numbers = if (contact.phoneNumbers.size > 1) {
                contact.phoneNumbers.joinToString(" و ") { it }
            } else {
                contact.phoneNumber
            }
            "${contact.name} با شماره $numbers"
        }
        
        val message = "مخاطبین: $contactsText. لطفاً نام مخاطب را بگویید."
        Log.d(TAG, "📢 خواندن لیست مخاطبین: $message")
        
        ttsHelper.speakOnlineFirst(message)
        
        // کمی صبر برای تمام شدن TTS
        delay(1000)
    }
    
    /**
     * شنود برای پاسخ کاربر
     */
    private suspend fun listenForUserResponse(): String {
        Log.d(TAG, "🎤 فعال کردن شنود برای پاسخ کاربر...")
        
        return withContext(Dispatchers.IO) {
            try {
                // استفاده از OnlineSTTService با ایجاد فایل صوتی واقعی
                val tempDir = java.io.File(context.cacheDir, "temp_audio")
                if (!tempDir.exists()) tempDir.mkdirs()
                
                val audioFile = java.io.File(tempDir, "temp_recording_${System.currentTimeMillis()}.wav")
                
                // ضبط صدا با UnifiedVoiceEngine
                val engine = UnifiedVoiceEngine(context)
                
                if (!engine.hasRequiredPermissions()) {
                    Log.e(TAG, "❌ مجوز ضبط صدا وجود ندارد")
                    withContext(Dispatchers.Main) {
                        ttsHelper.speakOnlineFirst("برای ضبط صدا، مجوز میکروفون را بدهید")
                    }
                    return@withContext "PERMISSION_DENIED"
                }
                
                Log.d(TAG, "🎤 شروع ضبط صدا...")
                
                // شروع ضبط
                val startResult = engine.startRecording()
                if (startResult.isFailure) {
                    Log.e(TAG, "❌ خطا در شروع ضبط: ${startResult.exceptionOrNull()?.message}")
                    return@withContext ""
                }
                
                // ضبط با timeout
                val timeoutMs = 8000L
                val startTime = System.currentTimeMillis()
                var hasSpeech = false
                var lastSpeechTime = 0L
                val silenceStopMs = 2000L
                val threshold = 800
                
                while (engine.isRecordingInProgress()) {
                    val now = System.currentTimeMillis()
                    val elapsed = now - startTime
                    
                    // بررسی timeout
                    if (elapsed > timeoutMs) {
                        Log.d(TAG, "⏰ timeout - توقف ضبط")
                        break
                    }
                    
                    // بررسی صدا
                    val amplitude = engine.getCurrentAmplitude()
                    if (amplitude > threshold) {
                        hasSpeech = true
                        lastSpeechTime = now
                    }
                    
                    // بررسی سکوت پس از صحبت
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
                        ttsHelper.speakOnlineFirst("به دلیل عدم پاسخ، انتخاب لغو شد")
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
            val aiResponse = aiAssistant.processText(prompt)
            val analysis = aiResponse.lowercase().trim()
            
            Log.d(TAG, "🤖 پاسخ AI: $analysis")
            
            when {
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
                        Log.d(TAG, "❌ مخاطب یافت نشد: $analysis")
                        withContext(Dispatchers.Main) {
                            ttsHelper.speakOnlineFirst("مخاطب یافت نشد، لطفاً دوباره تلاش کنید")
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
     * شروع تأیید تماس
     */
    private suspend fun startCallConfirmation(contact: Contact) {
        try {
            val confirmationManager = CallConfirmationManager(context)
            
            // اگر مخاطب چند شماره دارد، شماره اصلی را استفاده کن
            val phoneNumber = if (contact.phoneNumbers.size > 1) {
                contact.phoneNumber // شماره اصلی
            } else {
                contact.phoneNumber
            }
            
            confirmationManager.startCallConfirmation(contact, phoneNumber)
            
            withContext(Dispatchers.Main) {
                ttsHelper.speakOnlineFirst("در حال تماس با ${contact.name}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطا در شروع تماس", e)
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
        val fileName = "temp_audio_$timestamp.wav"
        return File(context.cacheDir, fileName)
    }
}
