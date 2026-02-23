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
            val formattedPhone = TTSHelper.formatPhoneNumberForTTS(contact.phoneNumber)
            "${contact.name} با شماره $formattedPhone"
        }

        val message = "مخاطبین: $contactsText. لطفاً نام مخاطب را بگویید."
        Log.d(TAG, "📢 خواندن لیست مخاطبین: $message")

        ttsHelper.speakOnlineFirst(message)

        // صبر برای تمام شدن TTS و کمی وقفه قبل از ضبط
        delay(2000)
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
                    ttsHelper.speakOnlineFirst("به دلیل سکوت، انتخاب لغو شد")
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
            
            withContext(Dispatchers.Main) {
                ttsHelper.speakOnlineFirst(confirmMessage)
            }
            
            delay(2000) // صبر برای تمام شدن TTS
            
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
