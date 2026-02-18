package com.persianai.assistant.utils

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.persianai.assistant.R
import com.persianai.assistant.activities.DashboardActivity
import com.persianai.assistant.receivers.CallNotificationReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * مدیر تماس هوشمند با نوتیفیکیشن‌های تعاملی
 */
object SmartCallManager {
    
    private const val TAG = "SmartCallManager"
    private const val CHANNEL_ID_CALLS = "calls_channel"
    private const val NOTIFICATION_ID_CALL_BASE = 5000
    
    // کلمات اضافی که باید حذف شوند
    private val TITLE_WORDS_TO_REMOVE = listOf(
        "آقا", "خانم", "همسر", "پدر", "مادر", "برادر", "خواهر", "پسر", "دختر",
        "دایی", "عمو", "عمه", "خاله", "پدربزرگ", "مادربزرگ", "نوه", "برادرزاده", "خواهرزاده",
        "دوست", "رفیق", "همکار", "همکلاسی", "همسایه", "همراه", "صاحب", "صاحبخانه"
    )
    
    /**
     * ایجاد کانال نوتیفیکیشن تماس‌ها
     */
    fun createCallNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            val callsChannel = NotificationChannel(
                CHANNEL_ID_CALLS,
                "تماس‌ها",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "نوتیفیکیشن‌های تماس هوشمند"
                enableVibration(true)
                enableLights(true)
                setShowBadge(true)
            }
            
            notificationManager.createNotificationChannel(callsChannel)
        }
    }
    
    /**
     * پردازش درخواست تماس و نمایش نوتیفیکیشن مناسب
     */
    suspend fun processCallRequest(context: Context, contactName: String): CallResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Processing call request for: $contactName")
            
            // 1) بررسی مجوز مخاطبین
            if (!hasContactsPermission(context)) {
                Log.w(TAG, "Contacts permission not granted")
                return@withContext showPermissionRequestNotification(context, contactName)
            }
            
            // 2) پاک‌سازی نام مخاطب
            val cleanName = cleanContactName(contactName)
            Log.d(TAG, "Cleaned contact name: '$contactName' -> '$cleanName'")
            
            // 3) جستجوی مخاطبین
            val contacts = searchContacts(context, cleanName)
            Log.d(TAG, "Found ${contacts.size} contacts")
            
            when {
                contacts.isEmpty() -> {
                    CallResult.NotFound(cleanName)
                }
                contacts.size == 1 -> {
                    val contact = contacts.first()
                    showSingleContactNotification(context, contact)
                    CallResult.SingleContact(contact)
                }
                else -> {
                    showMultipleContactsNotification(context, contacts)
                    CallResult.MultipleContacts(contacts)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing call request", e)
            CallResult.Error(e.message ?: "خطای نامشخص")
        }
    }
    
    /**
     * بررسی مجوز دسترسی به مخاطبین
     */
    private fun hasContactsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context, 
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * پاک‌سازی نام مخاطب از کلمات اضافی
     */
    private fun cleanContactName(name: String): String {
        var cleanName = name.trim()
        
        // حذف کلمات اضافی
        for (word in TITLE_WORDS_TO_REMOVE) {
            cleanName = cleanName.replace(word, "").trim()
        }
        
        // حذف فاصله‌های اضافی
        cleanName = cleanName.replace(Regex("\\s+"), " ")
        
        return cleanName.trim()
    }
    
    /**
     * جستجوی هوشمند مخاطبین با fuzzy search
     */
    private fun searchContacts(context: Context, name: String): List<ContactInfo> {
        val contacts = mutableListOf<ContactInfo>()
        
        try {
            // 1) جستجوی دقیق و مشابه
            val exactMatches = searchContactsByName(context, name, exact = true)
            contacts.addAll(exactMatches)
            
            // 2) اگر نتیجه‌ای نبود، جستجوی fuzzy
            if (contacts.isEmpty()) {
                val fuzzyMatches = searchContactsByName(context, name, exact = false)
                contacts.addAll(fuzzyMatches)
            }
            
            // 3) حذف موارد تکراری بر اساس شماره تلفن
            val uniqueContacts = contacts.distinctBy { it.phoneNumber }
            
            Log.d(TAG, "Found ${uniqueContacts.size} unique contacts for '$name'")
            return uniqueContacts
            
        } catch (e: Exception) {
            Log.e(TAG, "Error searching contacts", e)
            return emptyList()
        }
    }
    
    /**
     * جستجوی مخاطبین بر اساس نام
     */
    private fun searchContactsByName(context: Context, name: String, exact: Boolean): List<ContactInfo> {
        val contacts = mutableListOf<ContactInfo>()
        
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )
        
        val selection = if (exact) {
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?"
        } else {
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        }
        
        val selectionArgs = if (exact) {
            arrayOf(name)
        } else {
            arrayOf("%$name%")
        }
        
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        )
        
        cursor?.use {
            val idIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val typeIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
            
            while (it.moveToNext()) {
                val id = it.getLong(idIndex)
                val displayName = it.getString(nameIndex) ?: continue
                val phoneNumber = it.getString(numberIndex) ?: continue
                val type = it.getInt(typeIndex)
                
                // پاک‌سازی شماره تلفن
                val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
                
                contacts.add(
                    ContactInfo(
                        id = id,
                        name = displayName,
                        phoneNumber = cleanNumber,
                        phoneType = getPhoneTypeLabel(type)
                    )
                )
            }
        }
        
        return contacts
    }
    
    /**
     * دریافت برچسب نوع شماره تلفن
     */
    private fun getPhoneTypeLabel(type: Int): String {
        return when (type) {
            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "موبایل"
            ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "منزل"
            ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "کار"
            ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "اصلی"
            else -> "سایر"
        }
    }
    
    /**
     * نمایش نوتیفیکیشن درخواست مجوز مخاطبین
     */
    private fun showPermissionRequestNotification(context: Context, contactName: String): CallResult {
        val notificationId = NOTIFICATION_ID_CALL_BASE + 100
        
        // Intent برای درخواست مجوز
        val permissionIntent = Intent(context, CallNotificationReceiver::class.java).apply {
            action = "REQUEST_CONTACTS_PERMISSION"
            putExtra("contact_name", contactName)
        }
        
        val permissionPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            permissionIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("📞 نیاز به مجوز مخاطبین")
            .setContentText("برای تماس با '$contactName' به دسترسی مخاطبین نیاز داریم")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(
                R.drawable.ic_notification,
                "✓ دادن مجوز",
                permissionPendingIntent
            )
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("برای تماس با '$contactName' باید به مخاطبین دسترسی داشته باشیم.\n\nروی دکمه زیر بزنید تا مجوز داده شود.")
            )
            .build()
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notification)
        
        return CallResult.NeedPermission(contactName)
    }
    
    /**
     * نمایش نوتیفیکیشن برای یک مخاطب پیدا شده
     */
    private fun showSingleContactNotification(context: Context, contact: ContactInfo) {
        val notificationId = NOTIFICATION_ID_CALL_BASE + contact.id.toInt()
        
        // Intent برای تماس مستقیم
        val callIntent = Intent(context, CallNotificationReceiver::class.java).apply {
            action = "MAKE_CALL"
            putExtra("phone_number", contact.phoneNumber)
            putExtra("contact_name", contact.name)
        }
        
        val callPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 1,
            callIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // Intent برای باز کردن صفحه شماره‌گیر
        val dialIntent = Intent(context, CallNotificationReceiver::class.java).apply {
            action = "OPEN_DIALER"
            putExtra("phone_number", contact.phoneNumber)
        }
        
        val dialPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 2,
            dialIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // Intent برای تغییر مخاطب
        val changeIntent = Intent(context, CallNotificationReceiver::class.java).apply {
            action = "CHANGE_CONTACT"
            putExtra("contact_name", contact.name)
        }
        
        val changePendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 3,
            changeIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("📞 تماس با «${contact.name}»")
            .setContentText("شماره: ${contact.phoneNumber} (${contact.phoneType})")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(
                R.drawable.ic_notification,
                "📞 تماس",
                callPendingIntent
            )
            .addAction(
                R.drawable.ic_notification,
                "📱 شماره‌گیر",
                dialPendingIntent
            )
            .addAction(
                R.drawable.ic_notification,
                "✏ تغییر مخاطب",
                changePendingIntent
            )
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("📞 تماس با «${contact.name}»\n" +
                            "شماره: ${contact.phoneNumber} (${contact.phoneType})\n\n" +
                            "📞 تماس مستقیم\n" +
                            "📱 باز کردن صفحه شماره‌گیر\n" +
                            "✏ انتخاب مخاطب دیگر")
            )
            .build()
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notification)
    }
    
    /**
     * نمایش نوتیفیکیشن برای چندین مخاطب مشابه
     */
    private fun showMultipleContactsNotification(context: Context, contacts: List<ContactInfo>) {
        val notificationId = NOTIFICATION_ID_CALL_BASE + 200
        
        // Intent برای باز کردن صفحه انتخاب مخاطب
        val selectIntent = Intent(context, CallNotificationReceiver::class.java).apply {
            action = "SELECT_CONTACT"
            putExtra("contacts_json", kotlinx.serialization.json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ContactInfo.serializer()), contacts))
        }
        
        val selectPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            selectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val contactsText = contacts.take(5).joinToString("\n") { 
            "• ${it.name}: ${it.phoneNumber} (${it.phoneType})" 
        }
        
        val moreText = if (contacts.size > 5) "\n... و ${contacts.size - 5} مخاطب دیگر" else ""
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("📞 ${contacts.size} مخاطب مشابه پیدا شد")
            .setContentText("برای انتخاب مخاطب ضربه بزنید")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(selectPendingIntent)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("📞 ${contacts.size} مخاطب مشابه پیدا شد:\n\n$contactsText$moreText\n\nبرای انتخاب مخاطب ضربه بزنید")
            )
            .build()
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notification)
    }
    
    /**
     * لغو نوتیفیکیشن تماس
     */
    fun cancelCallNotification(context: Context, contactId: Long? = null) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (contactId != null) {
            notificationManager.cancel(NOTIFICATION_ID_CALL_BASE + contactId.toInt())
        } else {
            // لغو همه نوتیفیکیشن‌های تماس
            for (id in NOTIFICATION_ID_CALL_BASE..NOTIFICATION_ID_CALL_BASE + 300) {
                notificationManager.cancel(id)
            }
        }
    }
}

/**
 * اطلاعات مخاطب
 */
@kotlinx.serialization.Serializable
data class ContactInfo(
    val id: Long,
    val name: String,
    val phoneNumber: String,
    val phoneType: String
)

/**
 * نتیجه پردازش تماس
 */
sealed class CallResult {
    data class SingleContact(val contact: ContactInfo) : CallResult()
    data class MultipleContacts(val contacts: List<ContactInfo>) : CallResult()
    data class NotFound(val contactName: String) : CallResult()
    data class NeedPermission(val contactName: String) : CallResult()
    data class Error(val message: String) : CallResult()
}
