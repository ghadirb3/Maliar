package com.persianai.assistant.call

import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log
import com.persianai.assistant.models.Contact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * جستجوی مخاطبین دستگاه برای سیستم تماس مالیار
 * کاملاً آفلاین و سریع
 */
class ContactSearcher(private val context: Context) {
    
    private val TAG = "ContactSearcher"
    
    /**
     * جستجوی مخاطبین بر اساس عبارت
     * @param query عبارت جستجو (نام یا شماره تلفن)
     * @param maxResults حداکثر تعداد نتایج
     * @return لیست مخاطبین مرتب‌شده بر اساس امتیاز
     */
    suspend fun searchContacts(query: String, maxResults: Int = 3): List<Contact> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Contact>()
        
        try {
            if (query.isBlank()) {
                Log.w(TAG, "⚠️ عبارت جستجو خالی است")
                return@withContext emptyList()
            }
            
            Log.d(TAG, "🔍 جستجوی مخاطبین برای: '$query'")
            
            // Retry mechanism for first-time permission grants
            var contacts = emptyList<Contact>()
            var retryCount = 0
            val maxRetries = 3
            
            while (retryCount < maxRetries && contacts.isEmpty()) {
                try {
                    contacts = readAllContacts()
                    
                    if (contacts.isEmpty() && retryCount < maxRetries - 1) {
                        Log.d(TAG, "🔄 مخاطبی یافت نشد، تلاش مجدد ${retryCount + 1}/$maxRetries")
                        delay(500 * (retryCount + 1)) // 500ms, 1000ms, 1500ms delays
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "❌ دسترسی به مخاطبین مجاز نیست", e)
                    break
                }
                retryCount++
            }
            
            if (contacts.isEmpty()) {
                Log.w(TAG, "⚠️ پس از $maxRetries تلاش، مخاطبی یافت نشد")
                return@withContext emptyList()
            }
            
            Log.d(TAG, "✅ ${contacts.size} مخاطب از دفترچه تلفن خوانده شد")
            
            // فیلتر و امتیازدهی
            val matchingContacts = contacts
                .map { contact ->
                    val score = contact.calculateMatchScore(query)
                    contact.copy(score = score)
                }
                .filter { it.score > 0f }
                .sortedByDescending { it.score }
                .take(maxResults)
            
            Log.d(TAG, "✅ ${matchingContacts.size} مخاطب مطابق یافت شد")
            matchingContacts.forEach { contact ->
                Log.d(TAG, "  - ${contact.name} (${contact.phoneNumber}) [امتیاز: ${contact.score}]")
            }
            
            return@withContext matchingContacts
            
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ دسترسی به مخاطبین مجاز نیست", e)
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطا در جستجوی مخاطبین", e)
            emptyList()
        }
    }
    
    /**
     * خواندن تمام مخاطبین از دفترچه تلفن
     */
    private suspend fun readAllContacts(): List<Contact> = withContext(Dispatchers.IO) {
        val contacts = mutableListOf<Contact>()
        val contentResolver = context.contentResolver
        
        // ستون‌های مورد نیاز برای خواندن
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone._ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID
        )
        
        val cursor: Cursor? = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )
        
        cursor?.use { 
            val idColumn = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone._ID)
            val nameColumn = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberColumn = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val contactIdColumn = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            
            val seenContacts = mutableSetOf<String>() // برای جلوگیری از تکراری
            
            while (it.moveToNext()) {
                try {
                    val id = it.getString(idColumn)
                    val name = it.getString(nameColumn) ?: "نامشخص"
                    val number = it.getString(numberColumn) ?: continue
                    val contactId = it.getString(contactIdColumn)
                    
                    // ایجاد کلید یکتا برای جلوگیری از تکراری (بر اساس contactId + number)
                    val uniqueKey = "$contactId-$number"
                    
                    if (!seenContacts.contains(uniqueKey) && name.isNotBlank()) {
                        val contact = Contact(
                            id = id,
                            name = name.trim(),
                            phoneNumber = number.trim()
                        )
                        contacts.add(contact)
                        seenContacts.add(uniqueKey)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ خطا در خواندن یک مخاطب", e)
                }
            }
        }
        
        Log.d(TAG, "📚 ${contacts.size} مخاطب از دفترچه تلفن خوانده شد")
        contacts
    }
    
    /**
     * جستجوی سریع برای یک نتیجه برتر
     * @param query عبارت جستجو
     * @return بهترین مخاطب تطابق داده شده یا null
     */
    suspend fun findBestMatch(query: String): Contact? {
        val results = searchContacts(query, maxResults = 1)
        return results.firstOrNull()
    }
    
    /**
     * بررسی اینکه آیا دسترسی به مخاطبین وجود دارد
     */
    fun hasContactPermission(): Boolean {
        return try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone._ID),
                null,
                null,
                null
            )?.use { true } ?: false
        } catch (e: SecurityException) {
            false
        }
    }
}
