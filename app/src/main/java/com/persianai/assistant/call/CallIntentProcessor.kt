package com.persianai.assistant.call

import android.content.Context
import android.util.Log
import com.persianai.assistant.models.Contact
import kotlinx.coroutines.*
import java.util.regex.Pattern

/**
 * پردازشگر فرمان‌های صوتی تماس برای سیستم مالیار
 * استخراج قصد تماس و نام مخاطب از متن ورودی
 */
class CallIntentProcessor(private val context: Context) {
    
    private val TAG = "CallIntentProcessor"
    private val contactSearcher = ContactSearcher(context)
    
    // الگوهای فرمان تماس به فارسی
    private val callPatterns = listOf(
        Pattern.compile("تماس با (.+)"),
        Pattern.compile("با (.+) تماس بگیر"),
        Pattern.compile("(.+) را صدا بزن"),
        Pattern.compile("(.+) را تماس بگیر"),
        Pattern.compile("زنگ بزن به (.+)"),
        Pattern.compile("به (.+) زنگ بزن"),
        Pattern.compile("پیدا کردن (.+)"),
        Pattern.compile("(.+) پیدا کن"),
        Pattern.compile("تماس (.+)"),
        Pattern.compile("زنگ (.+)"),
        Pattern.compile("صدا زدن (.+)"),
        Pattern.compile("با (.+) صحبت کن"),
        Pattern.compile("با (.+) حرف بزن")
    )
    
    // الگوهای استخراج شماره تلفن
    private val phonePatterns = listOf(
        Pattern.compile("تماس مستقیم با شماره ([0-9\\s]+)"),
        Pattern.compile("با شماره ([0-9\\s]+) تماس بگیر"),
        Pattern.compile("شماره ([0-9\\s]+) را تماس بگیر"),
        Pattern.compile("به شماره ([0-9\\s]+) زنگ بزن"),
        Pattern.compile("زنگ بزن به شماره ([0-9\\s]+)"),
        Pattern.compile("تماس با شماره ([0-9\\s]+)"),
        Pattern.compile("شماره ([0-9\\s]+)"),
        Pattern.compile("\\b(09[0-9\\s]{8,11})\\b"),
        Pattern.compile("\\b(\\+98[0-9\\s]{8,11})\\b"),
        Pattern.compile("\\b(0[0-9\\s]{9,11})\\b")
    )
    
    // الگوهای لغو تماس
    private val cancelPatterns = listOf(
        Pattern.compile("لغو تماس"),
        Pattern.compile("تماس لغو"),
        Pattern.compile("کنسل تماس"),
        Pattern.compile("تماس کنسل"),
        Pattern.compile("تماس را لغو کن"),
        Pattern.compile("تماس را کنسل کن"),
        Pattern.compile("تماس را متوقف کن"),
        Pattern.compile("تماس را قطع کن")
    )
    
    /**
     * پردازش فرمان صوتی کاربر
     * @param userInput متن ورودی کاربر
     * @return نتیجه پردازش فرمان
     */
    suspend fun processCallIntent(userInput: String): CallIntentResult = withContext(Dispatchers.IO) {
        val normalizedInput = userInput.lowercase().trim()
        
        Log.d(TAG, "🎯 پردازش فرمان تماس: '$userInput'")
        
        try {
            // مرحله ۱: بررسی لغو تماس
            if (isCancelCommand(normalizedInput)) {
                Log.d(TAG, "❌ فرمان لغو تماس شناسایی شد")
                return@withContext CallIntentResult.Cancel
            }
            
            // مرحله ۲: استخراج شماره تلفن یا نام مخاطب
            val phoneNumber = extractPhoneNumber(normalizedInput)
            if (phoneNumber != null) {
                Log.d(TAG, "📞 شماره تلفن مستقیم استخراج شد: '$phoneNumber'")
                val cleanedPhone = cleanPhoneNumber(phoneNumber)
                return@withContext CallIntentResult.DirectCall(cleanedPhone)
            }
            
            val contactName = extractContactName(normalizedInput)
            if (contactName == null) {
                Log.d(TAG, "❌ نام مخاطب استخراج نشد")
                return@withContext CallIntentResult.NotRecognized("متوجه نشدم با کی تماس بگیرم")
            }
            
            Log.d(TAG, "👤 نام مخاطب استخراج شد: '$contactName'")
            
            // مرحله ۳: جستجوی مخاطب
            val query = contactName
            Log.d(TAG, "🔍 جستجوی مخاطب با عبارت: '$query'")
            val contacts = contactSearcher.searchContacts(query, maxResults = 3)
            
            when {
                contacts.isEmpty() -> {
                    Log.d(TAG, "❌ هیچ مخاطبی یافت نشد")
                    CallIntentResult.ContactNotFound("هیچ مخاطبی با نام $contactName پیدا نشد")
                }
                
                contacts.size == 1 -> {
                    val contact = contacts.first()
                    Log.d(TAG, "✅ یک مخاطب یافت شد: ${contact.name}")
                    CallIntentResult.SingleContact(contact)
                }
                
                else -> {
                    Log.d(TAG, "📋 چندین مخاطب یافت شد: ${contacts.size} مورد")
                    CallIntentResult.MultipleContacts(contacts)
                }
            }
            
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ دسترسی به مخاطبین مجاز نیست", e)
            CallIntentResult.PermissionRequired("برای تماس نیاز به دسترسی به مخاطبین دارم")
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطا در پردازش فرمان تماس", e)
            CallIntentResult.Error("خطا در پردازش فرمان: ${e.message}")
        }
    }
    
    /**
     * بررسی اینکه آیا فرمان لغو تماس است
     */
    private fun isCancelCommand(input: String): Boolean {
        return cancelPatterns.any { pattern ->
            pattern.matcher(input).find()
        }
    }
    
    /**
     * استخراج شماره تلفن از فرمان صوتی
     */
    private fun extractPhoneNumber(input: String): String? {
        for (pattern in phonePatterns) {
            val matcher = pattern.matcher(input)
            if (matcher.find()) {
                val phoneNumber = matcher.group(1)?.trim()
                if (phoneNumber != null && phoneNumber.isNotBlank()) {
                    return phoneNumber
                }
            }
        }
        return null
    }
    
    /**
     * پاکسازی شماره تلفن از فاصله‌ها و کاراکترهای اضافی
     */
    private fun cleanPhoneNumber(phoneNumber: String): String {
        return phoneNumber
            .replace("\\s".toRegex(), "") // حذف فاصله‌ها
            .replace("-", "") // حذف خط تیره
            .replace("(", "") // حذف پرانتز باز
            .replace(")", "") // حذف پرانتز بسته
            .replace("٠", "0") // اعداد عربی
            .replace("١", "1")
            .replace("٢", "2")
            .replace("٣", "3")
            .replace("٤", "4")
            .replace("٥", "5")
            .replace("٦", "6")
            .replace("٧", "7")
            .replace("٨", "8")
            .replace("٩", "9")
    }
    
    /**
     * استخراج نام مخاطب از فرمان صوتی
     */
    private fun extractContactName(input: String): String? {
        for (pattern in callPatterns) {
            val matcher = pattern.matcher(input)
            if (matcher.find()) {
                var contactName = matcher.group(1)?.trim()
                
                if (contactName != null) {
                    // پاکسازی نام از کلمات اضافی
                    contactName = cleanupContactName(contactName)
                    
                    if (contactName.isNotBlank() && contactName.length >= 2) {
                        return contactName
                    }
                }
            }
        }
        
        // اگر با الگوها چیزی پیدا نشد، جستجوی مستقیم
        return extractDirectContactName(input)
    }
    
    /**
     * پاکسازی نام مخاطب از کلمات اضافی
     * فقط کلمات پرت را حذف می‌کند، روابط را حفظ می‌کند
     */
    private fun cleanupContactName(name: String): String {
        return name
            .replace("آقای", "").trim()
            .replace("خانم", "").trim()
            .replace("جناب", "").trim()
            .replace("سرکار", "").trim()
            .replace("دکتر", "").trim()
            .replace("مهندس", "").trim()
            .replace("استاد", "").trim()
            // حفظ کلمات روابط خانوادگی برای تطابق دقیق
            // .replace("همسر", "").trim()
            // .replace("مادر", "").trim()
            // .replace("پدر", "").trim()
            // .replace("برادر", "").trim()
            // .replace("خواهر", "").trim()
            // .replace("دوست", "").trim()
            // .replace("همکار", "").trim()
            // .replace("مدیر", "").trim()
            // .replace("رئیس", "").trim()
            .trim()
    }
    
    /**
     * استخراج مستقیم نام مخاطب (fallback)
     */
    private fun extractDirectContactName(input: String): String? {
        val words = input.split(" ").filter { it.isNotBlank() }
        
        // جستجوی ترکیب‌های ممکن
        for (i in words.indices) {
            for (j in i + 1..minOf(i + 3, words.size)) {
                val candidate = words.subList(i, j).joinToString(" ")
                if (candidate.length >= 2 && !isStopWord(candidate)) {
                    return candidate
                }
            }
        }
        
        return null
    }
    
    /**
     * بررسی اینکه آیا کلمه یک کلمه توقف است
     */
    private fun isStopWord(word: String): Boolean {
        val stopWords = setOf(
            "تماس", "زنگ", "صدا", "با", "به", "را", "کن", "بگیر", "بزن", "پیدا", "کنم", "کنی", "کند",
            "صحبت", "حرف", "بزن", "زن", "کنم", "کنی", "کند", "میخوام", "می‌خوام", "میخواهم", "می‌خواهم"
        )
        return stopWords.contains(word.lowercase())
    }
    
    /**
     * نتایج پردازش فرمان تماس
     */
    sealed class CallIntentResult {
        object Cancel : CallIntentResult()
        data class NotRecognized(val message: String) : CallIntentResult()
        data class ContactNotFound(val message: String) : CallIntentResult()
        data class PermissionRequired(val message: String) : CallIntentResult()
        data class Error(val message: String) : CallIntentResult()
        data class SingleContact(val contact: Contact) : CallIntentResult()
        data class MultipleContacts(val contacts: List<Contact>) : CallIntentResult()
        data class DirectCall(val phoneNumber: String) : CallIntentResult()
    }
}
