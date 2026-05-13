package com.example.maliar.features.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.maliar.core.stt.STTHelper
import com.example.maliar.core.tts.TTSHelper
import com.example.maliar.data.local.ContactsRepository
import com.example.maliar.data.model.Contact
import kotlinx.coroutines.delay

class CallContactSelectionDialogue(
    private val context: Context,
    private val sttHelper: STTHelper,
    private val ttsHelper: TTSHelper,
    private val contactsRepository: ContactsRepository
) {
    private val TAG = "CallContactSelection"
    
    /**
     * شروع فرآیند انتخاب مخاطب و برقراری تماس
     */
    suspend fun startContactSelection(contactName: String): Boolean {
        try {
            // جستجوی مخاطبین
            val matchingContacts = contactsRepository.searchContacts(contactName)
            
            if (matchingContacts.isEmpty()) {
                ttsHelper.speakOnlineFirstAndWait("مخاطبی با نام $contactName پیدا نشد")
                return false
            }
            
            // اگر فقط یک مخاطب پیدا شد
            if (matchingContacts.size == 1) {
                return confirmAndCall(matchingContacts[0])
            }
            
            // اگر چند مخاطب پیدا شد
            return handleMultipleContacts(matchingContacts, contactName)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in contact selection", e)
            ttsHelper.speakOnlineFirstAndWait("خطایی در انتخاب مخاطب رخ داد")
            return false
        }
    }
    
    /**
     * مدیریت چند مخاطب با نام مشابه
     */
    private suspend fun handleMultipleContacts(contacts: List<Contact>, searchName: String): Boolean {
        // ساخت پیام با شماره‌گذاری مخاطبین
        val message = buildContactListMessage(contacts, searchName)
        
        // خواندن لیست مخاطبین
        ttsHelper.speakOnlineFirstAndWait(message)
        
        // دریافت شماره انتخابی از کاربر
        val selectedNumber = getUserSelection(contacts.size)
        
        if (selectedNumber == null) {
            ttsHelper.speakOnlineFirstAndWait("عملیات لغو شد")
            return false
        }
        
        // تأیید و برقراری تماس
        return confirmAndCall(contacts[selectedNumber - 1])
    }
    
    /**
     * ساخت پیام لیست مخاطبین با شماره‌گذاری
     */
    private fun buildContactListMessage(contacts: List<Contact>, searchName: String): String {
        val builder = StringBuilder()
        builder.append("${contacts.size} مخاطب با نام $searchName پیدا شد. ")
        
        contacts.forEachIndexed { index, contact ->
            val number = index + 1
            builder.append("شماره $number: ${contact.name}")
            
            // اضافه کردن شماره تلفن اگر موجود باشد
            if (contact.phoneNumbers.isNotEmpty()) {
                val phoneNumber = contact.phoneNumbers[0].number
                builder.append(" با شماره ${formatPhoneNumberForSpeech(phoneNumber)}")
            }
            
            builder.append(". ")
        }
        
        builder.append("لطفاً شماره مخاطب مورد نظر را بگویید یا بگویید لغو")
        
        return builder.toString()
    }
    
    /**
     * فرمت کردن شماره تلفن برای خواندن بهتر
     */
    private fun formatPhoneNumberForSpeech(phoneNumber: String): String {
        // حذف کاراکترهای غیرعددی
        val digits = phoneNumber.filter { it.isDigit() }
        
        // تبدیل به فرمت قابل خواندن (مثلاً: 09123456789 -> صفر نه یک دو سه...)
        return digits.map { it.toString() }.joinToString(" ")
    }
    
    /**
     * دریافت شماره انتخابی از کاربر
     */
    private suspend fun getUserSelection(maxNumber: Int): Int? {
        var attempts = 0
        val maxAttempts = 3
        
        while (attempts < maxAttempts) {
            attempts++
            
            // شروع ضبط صدا
            val userInput = sttHelper.startListening()
            
            if (userInput.isNullOrBlank()) {
                if (attempts < maxAttempts) {
                    ttsHelper.speakOnlineFirstAndWait("متوجه نشدم. لطفاً دوباره بگویید")
                }
                continue
            }
            
            // بررسی لغو
            if (userInput.contains("لغو") || userInput.contains("انصراف")) {
                return null
            }
            
            // استخراج عدد از ورودی
            val selectedNumber = extractNumberFromText(userInput)
            
            if (selectedNumber != null && selectedNumber in 1..maxNumber) {
                return selectedNumber
            }
            
            // ورودی نامعتبر
            if (attempts < maxAttempts) {
                ttsHelper.speakOnlineFirstAndWait("شماره نامعتبر است. لطفاً عددی بین ۱ تا $maxNumber بگویید")
            }
        }
        
        // بعد از 3 تلاش ناموفق
        ttsHelper.speakOnlineFirstAndWait("متأسفانه نتوانستم شماره را تشخیص دهم. عملیات لغو شد")
        return null
    }
    
    /**
     * استخراج عدد از متن فارسی یا انگلیسی
     */
    private fun extractNumberFromText(text: String): Int? {
        // اعداد فارسی
        val persianNumbers = mapOf(
            "یک" to 1, "۱" to 1, "اول" to 1,
            "دو" to 2, "۲" to 2, "دوم" to 2,
            "سه" to 3, "۳" to 3, "سوم" to 3,
            "چهار" to 4, "۴" to 4, "چهارم" to 4,
            "پنج" to 5, "۵" to 5, "پنجم" to 5,
            "شش" to 6, "۶" to 6, "ششم" to 6,
            "هفت" to 7, "۷" to 7, "هفتم" to 7,
            "هشت" to 8, "۸" to 8, "هشتم" to 8,
            "نه" to 9, "۹" to 9, "نهم" to 9,
            "ده" to 10, "۱۰" to 10, "دهم" to 10
        )
        
        // جستجوی عدد فارسی
        for ((key, value) in persianNumbers) {
            if (text.contains(key)) {
                return value
            }
        }
        
        // جستجوی عدد انگلیسی
        val englishNumber = text.filter { it.isDigit() }.toIntOrNull()
        if (englishNumber != null) {
            return englishNumber
        }
        
        return null
    }
    
    /**
     * تأیید و برقراری تماس
     */
    private suspend fun confirmAndCall(contact: Contact): Boolean {
        if (contact.phoneNumbers.isEmpty()) {
            ttsHelper.speakOnlineFirstAndWait("این مخاطب شماره تلفنی ندارد")
            return false
        }
        
        val phoneNumber = contact.phoneNumbers[0].number
        
        // پیام تأیید
        val confirmMessage = "آیا می‌خواهید با ${contact.name} تماس بگیرید؟ بگویید بله یا خیر"
        ttsHelper.speakOnlineFirstAndWait(confirmMessage)
        
        // دریافت تأیید
        val confirmation = sttHelper.startListening()
        
        if (confirmation.isNullOrBlank()) {
            ttsHelper.speakOnlineFirstAndWait("عملیات لغو شد")
            return false
        }
        
        if (confirmation.contains("بله") || confirmation.contains("آره") || confirmation.contains("yes")) {
            return makeCall(contact.name, phoneNumber)
        } else {
            ttsHelper.speakOnlineFirstAndWait("عملیات لغو شد")
            return false
        }
    }
    
    /**
     * برقراری تماس
     */
    private suspend fun makeCall(contactName: String, phoneNumber: String): Boolean {
        // بررسی مجوز
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) 
            != PackageManager.PERMISSION_GRANTED) {
            ttsHelper.speakOnlineFirstAndWait("مجوز تماس داده نشده است")
            return false
        }
        
        try {
            // پیام قبل از تماس
            ttsHelper.speakOnlineFirstAndWait("در حال برقراری تماس با $contactName")
            
            // برقراری تماس
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            
            Log.d(TAG, "Call initiated to $contactName ($phoneNumber)")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error making call", e)
            ttsHelper.speakOnlineFirstAndWait("خطا در برقراری تماس")
            return false
        }
    }
}
