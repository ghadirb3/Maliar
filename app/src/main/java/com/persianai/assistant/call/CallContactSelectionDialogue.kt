package com.persianai.assistant.call

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import com.persianai.assistant.models.Contact
import com.persianai.assistant.utils.TTSHelper
import com.persianai.assistant.stt.OnlineSTTService
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
    
    // کلمات کلیدی برای تأیید و لغو
    private val positiveKeywords = listOf("بله", "آره", "تماس", "بگیر", "اوکی", "باشه", "انجام بده", "دقیقا", "میخوام", "می‌خوام")
    private val negativeKeywords = listOf("لغو", "کنسل", "نه", "انصراف", "نمیخوام", "نمی‌خوام", "متوقف", "برو", "بسه", "بیخیر")
    
    /**
     * شروع مکالمه انتخاب مخاطب
     */
    suspend fun startSelectionDialogue() {
        try {
            // مرحله ۱: خواندن لیست مخاطبین با TTS
            speakContactsList()
            
            // مرحله ۲: شنود برای پاسخ کاربر
            val response = listenForUserResponse()
            
            // مرحله ۳: تحلیل پاسخ و اجرا
            processUserResponse(response)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطا در مکالمه انتخاب مخاطب", e)
            scope.launch {
                ttsHelper.speakOnlineFirst("خطا در انتخاب مخاطب")
            }
        }
    }
    
    /**
     * خواندن لیست مخاطبین با TTS
     */
    private suspend fun speakContactsList() {
        val contactsText = contacts.take(3).joinToString("، ") { contact ->
            "${contact.name} با شماره ${contact.phoneNumber}"
        }
        
        val message = "چند مخاطب پیدا شد: $contactsText. لطفاً نام دقیق مخاطب مورد نظر را بگویید."
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
                // ایجاد فایل صوتی موقت
                val audioFile = createTempAudioFile()
                
                // استفاده از OnlineSTTService برای شناسایی صدا
                val sttResult = onlineSTT.transcribeAudio(audioFile)
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
                ""
            }
        }
    }
    
    /**
     * تحلیل پاسخ کاربر و اجرای عمل مناسب
     */
    private suspend fun processUserResponse(response: String) {
        val normalizedResponse = response.lowercase().trim()
        
        when {
            response == "SILENCE_TIMEOUT" -> {
                Log.d(TAG, "⏰ لغو به دلیل سکوت کاربر")
                return
            }
            
            // بررسی کلمات لغو
            negativeKeywords.any { normalizedResponse.contains(it) } -> {
                Log.d(TAG, "❌ کاربر لغو کرد")
                ttsHelper.speakOnlineFirst("انتخاب لغو شد")
                return
            }
            
            // بررسی کلمات تأیید (فقط اگر یک مخاطب باشد)
            positiveKeywords.any { normalizedResponse.contains(it) } && contacts.size == 1 -> {
                val contact = contacts.first()
                Log.d(TAG, "✅ تأیید تماس با ${contact.name}")
                startCallConfirmation(contact)
                return
            }
            
            // جستجوی نام مخاطب در لیست
            else -> {
                val selectedContact = findContactByName(normalizedResponse)
                if (selectedContact != null) {
                    Log.d(TAG, "✅ مخاطب انتخاب شد: ${selectedContact.name}")
                    
                    // اگر مخاطب چند شماره دارد، شماره‌ها را بخوان
                    if (hasMultipleNumbers(selectedContact)) {
                        speakPhoneNumbers(selectedContact)
                    } else {
                        startCallConfirmation(selectedContact)
                    }
                } else {
                    Log.d(TAG, "❌ مخاطبی با این نام پیدا نشد")
                    ttsHelper.speakOnlineFirst("مخاطبی با این نام در لیست پیدا نشد. لطفاً دوباره تلاش کنید.")
                    
                    // تلاش مجدد
                    delay(2000)
                    startSelectionDialogue()
                }
            }
        }
    }
    
    /**
     * جستجوی مخاطب بر اساس نام
     */
    private fun findContactByName(name: String): Contact? {
        return contacts.find { contact ->
            contact.name.lowercase().contains(name) || 
            name.contains(contact.name.lowercase())
        }
    }
    
    /**
     * بررسی اینکه آیا مخاطب چند شماره دارد
     */
    private suspend fun hasMultipleNumbers(contact: Contact): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val contentResolver = context.contentResolver
                val projection = arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                
                val cursor = contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?",
                    arrayOf(contact.name),
                    null
                )
                
                val count = cursor?.use { it.count } ?: 0
                cursor?.close()
                
                Log.d(TAG, "📞 ${contact.name} دارای $count شماره تلفن")
                count > 1
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ خطا در بررسی شماره‌های مخاطب", e)
                false
            }
        }
    }
    
    /**
     * خواندن تمام شماره‌های مخاطب
     */
    private suspend fun getContactPhoneNumbers(contact: Contact): List<String> {
        return withContext(Dispatchers.IO) {
            try {
                val contentResolver = context.contentResolver
                val projection = arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                
                val cursor = contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?",
                    arrayOf(contact.name),
                    null
                )
                
                val numbers = mutableListOf<String>()
                cursor?.use {
                    val numberColumn = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (it.moveToNext()) {
                        val number = it.getString(numberColumn)?.trim()
                        if (number != null && number.isNotBlank()) {
                            numbers.add(number)
                        }
                    }
                }
                cursor?.close()
                
                Log.d(TAG, "📞 شماره‌های ${contact.name}: ${numbers.joinToString(", ")}")
                numbers.distinct()
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ خطا در خواندن شماره‌های مخاطب", e)
                listOf(contact.phoneNumber)
            }
        }
    }
    
    /**
     * خواندن شماره‌های مخاطب (اگر چند شماره دارد)
     */
    private suspend fun speakPhoneNumbers(contact: Contact) {
        val phoneNumbers = getContactPhoneNumbers(contact)
        
        if (phoneNumbers.size <= 1) {
            // اگر فقط یک شماره وجود دارد، مستقیماً تماس را تأیید کن
            startCallConfirmation(contact)
            return
        }
        
        // خواندن شماره‌ها با شماره‌گذاری
        val numbersText = phoneNumbers.mapIndexed { index, number ->
            "${index + 1}: $number"
        }.joinToString("، ")
        
        val message = "برای ${contact.name} چند شماره وجود دارد: $numbersText. لطفاً شماره مورد نظر را بگویید."
        Log.d(TAG, "📢 خواندن شماره‌های مخاطب: $message")
        
        ttsHelper.speakOnlineFirst(message)
        
        delay(1000)
        
        // شنود برای شماره
        val numberResponse = listenForUserResponse()
        
        // پردازش شماره انتخاب شده
        processNumberSelection(contact, phoneNumbers, numberResponse)
    }
    
    /**
     * پردازش انتخاب شماره توسط کاربر
     */
    private suspend fun processNumberSelection(contact: Contact, phoneNumbers: List<String>, response: String) {
        val normalizedResponse = response.lowercase().trim()
        
        when {
            response == "SILENCE_TIMEOUT" -> {
                Log.d(TAG, "⏰ لغو به دلیل سکوت کاربر")
                ttsHelper.speakOnlineFirst("به دلیل عدم پاسخ، انتخاب لغو شد")
                return
            }
            
            negativeKeywords.any { normalizedResponse.contains(it) } -> {
                Log.d(TAG, "❌ کاربر لغو کرد")
                ttsHelper.speakOnlineFirst("انتخاب شماره لغو شد")
                return
            }
            
            else -> {
                // تلاش برای استخراج شماره از پاسخ کاربر
                val selectedNumber = extractNumberFromResponse(normalizedResponse, phoneNumbers)
                
                if (selectedNumber != null) {
                    Log.d(TAG, "✅ شماره انتخاب شد: $selectedNumber")
                    // ایجاد یک کپی از مخاطب با شماره جدید
                    val updatedContact = contact.copy(phoneNumber = selectedNumber)
                    startCallConfirmation(updatedContact)
                } else {
                    Log.d(TAG, "❌ شماره‌ای تشخیص داده نشد")
                    ttsHelper.speakOnlineFirst("متوجه نشدم کدام شماره. لطفاً دوباره تلاش کنید.")
                    
                    // تلاش مجدد
                    delay(2000)
                    speakPhoneNumbers(contact)
                }
            }
        }
    }
    
    /**
     * استخراج شماره از پاسخ کاربر
     */
    private fun extractNumberFromResponse(response: String, phoneNumbers: List<String>): String? {
        // بررسی عدد (۱، ۲، ۳)
        val numberWords = mapOf(
            "یک" to 1, "اول" to 1, "۱" to 1,
            "دو" to 2, "دوم" to 2, "۲" to 2,
            "سه" to 3, "سوم" to 3, "۳" to 3,
            "چهار" to 4, "چهارم" to 4, "۴" to 4,
            "پنج" to 5, "پنجم" to 5, "۵" to 5
        )
        
        // جستجوی عدد در پاسخ
        for ((word, index) in numberWords) {
            if (response.contains(word)) {
                if (index <= phoneNumbers.size) {
                    return phoneNumbers[index - 1]
                }
            }
        }
        
        // جستجوی مستقیم شماره تلفن در پاسخ
        for (number in phoneNumbers) {
            val cleanNumber = number.replace("[^0-9]".toRegex(), "")
            val cleanResponse = response.replace("[^0-9]".toRegex(), "")
            
            if (cleanResponse.contains(cleanNumber) || cleanNumber.contains(cleanResponse)) {
                return number
            }
        }
        
        return null
    }
    
    /**
     * شروع فرآیند تأیید تماس
     */
    private suspend fun startCallConfirmation(contact: Contact) {
        val confirmationManager = CallConfirmationManager(context)
        confirmationManager.startCallConfirmation(contact, contact.phoneNumber)
    }
    
    /**
     * ایجاد فایل صوتی موقت
     */
    private fun createTempAudioFile(): java.io.File {
        val tempDir = java.io.File(context.cacheDir, "temp_audio")
        if (!tempDir.exists()) tempDir.mkdirs()
        return java.io.File(tempDir, "temp_recording_${System.currentTimeMillis()}.wav")
    }
}
