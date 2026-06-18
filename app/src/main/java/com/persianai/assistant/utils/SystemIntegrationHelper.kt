package com.persianai.assistant.utils

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.util.Calendar

/**
 * کمک به یکپارچه‌سازی با سیستم و برنامه‌های دیگر
 */
object SystemIntegrationHelper {

    /**
     * تنظیم یادآوری با AlarmManager
     */
    fun setReminder(
        context: Context, 
        message: String, 
        hour: Int, 
        minute: Int,
        useAlarm: Boolean = false,
        repeatInterval: Long = 0 // 0 = یکبار، AlarmManager.INTERVAL_DAY = روزانه
    ): Boolean {
        return try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            
            // بررسی permission برای Android 12+
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                if (!alarmManager.canScheduleExactAlarms()) {
                    // هدایت به تنظیمات
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        android.net.Uri.parse("package:${context.packageName}")
                    )
                    intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                    
                    android.widget.Toast.makeText(
                        context,
                        "لطفاً اجازه تنظیم یادآوری دقیق را فعال کنید",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    return false
                }
            }
            
            // محاسبه زمان یادآوری
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                
                // اگر زمان گذشته، برای فردا تنظیم کن
                if (timeInMillis < System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            
            // ساخت Intent برای ReminderReceiver
            val reminderId = "legacy_${System.currentTimeMillis()}"
            val intent = Intent(context, com.persianai.assistant.services.ReminderReceiver::class.java).apply {
                action = "com.persianai.assistant.REMINDER_ALARM"
                putExtra("message", message)
                putExtra("reminder_title", message)
                putExtra("reminder_description", "")
                putExtra("smart_reminder_id", reminderId)
                putExtra("reminder_id", reminderId.hashCode())
                putExtra("use_alarm", useAlarm)
                putExtra("alert_type", if (useAlarm) "FULL_SCREEN" else "NOTIFICATION")
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                System.currentTimeMillis().toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // تنظیم Alarm
            if (repeatInterval > 0) {
                // یادآوری تکرارشونده
                alarmManager.setRepeating(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    repeatInterval,
                    pendingIntent
                )
                android.util.Log.d("SystemIntegration", "Repeating alarm set for: ${calendar.time}")
            } else {
                // یکبار
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        calendar.timeInMillis,
                        pendingIntent
                    )
                } else {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        calendar.timeInMillis,
                        pendingIntent
                    )
                }
                android.util.Log.d("SystemIntegration", "Exact alarm set for: ${calendar.time}")
            }
            
            android.widget.Toast.makeText(
                context,
                "✅ یادآوری تنظیم شد برای ساعت $hour:${minute.toString().padStart(2, '0')}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            
            true
        } catch (e: Exception) {
            android.util.Log.e("SystemIntegration", "Error setting reminder", e)
            false
        }
    }

    /**
     * باز کردن مسیریابی در Google Maps
     */
    fun openNavigation(context: Context, destination: String, withPersianVoice: Boolean = false): Boolean {
        return try {
            // اگر مسیریابی صوتی فارسی درخواست شده
            if (withPersianVoice) {
                val navigationHelper = PersianNavigationHelper(context)
                navigationHelper.speak("در حال باز کردن مسیریابی به $destination")
            }
            
            val gmmIntentUri = Uri.parse("google.navigation:q=$destination")
            val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply {
                setPackage("com.google.android.apps.maps")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(mapIntent)
            true
        } catch (e: Exception) {
            // اگر Google Maps نصب نبود، از browser استفاده کن
            try {
                val browserIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$destination")
                ).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(browserIntent)
                true
            } catch (e2: Exception) {
                false
            }
        }
    }
    
    /**
     * مسیریابی با صوت فارسی کامل
     */
    suspend fun startPersianVoiceNavigation(context: Context, destination: String): String {
        val navigationHelper = PersianNavigationHelper(context)
        return navigationHelper.startPersianNavigation("موقعیت فعلی", destination)
    }

    /**
     * جستجو در Google
     */
    fun searchWeb(context: Context, query: String): Boolean {
        if (isBlockedText(context, query)) {
            android.widget.Toast.makeText(
                context,
                "این جستجو توسط کنترل والدین مسدود شد.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return false
        }
        return try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(android.app.SearchManager.QUERY, query)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * تماس تلفنی
     */
    fun makePhoneCall(context: Context, phoneNumber: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * ارسال ایمیل
     */
    fun sendEmail(context: Context, to: String, subject: String, body: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "message/rfc822"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(Intent.createChooser(intent, "ارسال ایمیل"))
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * باز کردن یک URL
     */
    fun openUrl(context: Context, url: String): Boolean {
        if (isBlockedText(context, url)) {
            android.widget.Toast.makeText(
                context,
                "این آدرس توسط کنترل والدین مسدود شد.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return false
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(url)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * ارسال پیام در تلگرام
     */
    fun sendTelegram(context: Context, phoneNumber: String, message: String): Boolean {
        return try {
            android.util.Log.d("SystemIntegration", "sendTelegram called: phone=$phoneNumber, message=$message")
            
            // کپی کردن متن به Clipboard
            copyToClipboard(context, message)
            
            // باز کردن تلگرام
            val packageName = "org.telegram.messenger"
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            
            if (intent != null) {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                
                // نمایش Toast
                android.widget.Toast.makeText(
                    context,
                    "📋 پیام کپی شد! در تلگرام Paste کنید",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                
                android.util.Log.d("SystemIntegration", "Telegram opened, message copied to clipboard")
                return true
            } else {
                android.util.Log.e("SystemIntegration", "Telegram not installed")
                return false
            }
        } catch (e: Exception) {
            android.util.Log.e("SystemIntegration", "Telegram send error", e)
            false
        }
    }
    
    /**
     * کپی کردن متن به Clipboard
     */
    private fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("پیام", text)
        clipboard.setPrimaryClip(clip)
    }
    
    /**
     * باز کردن برنامه + کپی پیام به Clipboard
     */
    fun openAppWithMessage(context: Context, appName: String, message: String): Boolean {
        // کپی کردن پیام
        copyToClipboard(context, message)
        
        // باز کردن برنامه
        val success = openApp(context, appName)
        
        if (success && message.isNotEmpty()) {
            android.widget.Toast.makeText(
                context,
                "📋 پیام کپی شد! در $appName Paste کنید",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        
        return success
    }
    
    /**
     * ارسال پیام در واتساپ
     */
    fun sendWhatsApp(context: Context, phoneNumber: String, message: String): Boolean {
        return try {
            // اگر شماره نداریم، فقط Share کن
            if (phoneNumber == "UNKNOWN" || phoneNumber.isEmpty()) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    setPackage("com.whatsapp")
                    putExtra(Intent.EXTRA_TEXT, message)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return true
            }
            
            val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://wa.me/$cleanNumber?text=${Uri.encode(message)}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            android.util.Log.e("SystemIntegration", "WhatsApp send error", e)
            false
        }
    }
    
    /**
     * ارسال پیام در پیام‌نگار (Android SMS)
     */
    fun sendSMS(context: Context, phoneNumber: String, message: String): Boolean {
        return try {
            if (phoneNumber == "UNKNOWN" || phoneNumber.isEmpty()) {
                // باز کردن SMS app با متن
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("sms:")
                    putExtra("sms_body", message)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return true
            }
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("sms:$phoneNumber")
                putExtra("sms_body", message)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            android.util.Log.e("SystemIntegration", "SMS send error", e)
            false
        }
    }
    
    /**
     * باز کردن برنامه خاص
     */
    fun openApp(context: Context, appName: String): Boolean {
        if (isBlockedText(context, appName)) {
            android.widget.Toast.makeText(
                context,
                "این برنامه توسط کنترل والدین مسدود شد.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return false
        }
        // لیست package name های شناخته شده
        val knownPackages = mapOf(
            "تلگرام" to "org.telegram.messenger",
            "telegram" to "org.telegram.messenger",
            "واتساپ" to "com.whatsapp",
            "whatsapp" to "com.whatsapp",
            "روبیکا" to "ir.resaneh1.iptv",
            "rubika" to "ir.resaneh1.iptv",
            "ایتا" to "ir.eitaa.messenger",
            "eitaa" to "ir.eitaa.messenger",
            "نشان" to "com.neshantadbir.neshan",
            "neshan" to "com.neshantadbir.neshan",
            "اینستاگرام" to "com.instagram.android",
            "instagram" to "com.instagram.android",
            "توییتر" to "com.twitter.android",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "یوتیوب" to "com.google.android.youtube",
            "youtube" to "com.google.android.youtube",
            "گوگل" to "com.google.android.googlequicksearchbox",
            "google" to "com.google.android.googlequicksearchbox",
            "گوگل مپ" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "maps" to "com.google.android.apps.maps",
            "مپ" to "com.google.android.apps.maps",
            "نقشه" to "com.google.android.apps.maps",
            "chrome" to "com.android.chrome",
            "کروم" to "com.android.chrome",
            "گپ" to "ir.gap.messenger",
            "gap" to "ir.gap.messenger"
        )
        
        val packageName = knownPackages[appName.lowercase()]
        
        if (packageName != null) {
            // استفاده از package name شناخته شده
            return try {
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                    android.util.Log.d("SystemIntegration", "Opened app: $appName ($packageName)")
                    true
                } else {
                    android.util.Log.w("SystemIntegration", "App not installed: $appName")
                    false
                }
            } catch (e: Exception) {
                android.util.Log.e("SystemIntegration", "Error opening app: $appName", e)
                false
            }
        } else {
            // جستجو در برنامه‌های نصب شده
            return try {
                val pm = context.packageManager
                val installedApps = pm.getInstalledApplications(0)
                
                // جستجوی برنامه با نام
                val foundApp = installedApps.find { 
                    it.loadLabel(pm).toString().contains(appName, ignoreCase = true)
                }
                
                if (foundApp != null) {
                    val intent = pm.getLaunchIntentForPackage(foundApp.packageName)
                    if (intent != null) {
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(intent)
                        android.util.Log.d("SystemIntegration", "Found and opened: ${foundApp.loadLabel(pm)}")
                        return true
                    }
                }
                
                android.util.Log.w("SystemIntegration", "App not found: $appName")
                false
            } catch (e: Exception) {
                android.util.Log.e("SystemIntegration", "Error searching for app: $appName", e)
                false
            }
        }
    }

    private fun isBlockedText(context: Context, text: String): Boolean {
        val prefs = PreferencesManager(context)
        if (!prefs.isParentalControlEnabled()) return false
        val keywords = prefs.getBlockedKeywords()
        if (keywords.isEmpty()) return false
        val lowerText = text.lowercase()
        return keywords.any { it.isNotEmpty() && lowerText.contains(it.lowercase()) }
    }

    /**
     * اضافه کردن رویداد به تقویم
     */
    fun addCalendarEvent(
        context: Context,
        title: String,
        description: String,
        beginTime: Long,
        endTime: Long
    ): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.Events.DESCRIPTION, description)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginTime)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endTime)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * پخش موسیقی
     */
    fun playMusic(context: Context, query: String): Boolean {
        return try {
            val intent = Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH").apply {
                putExtra(android.app.SearchManager.QUERY, query)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * تشخیص نوع درخواست و اجرای عمل مناسب
     */
    fun handleSmartRequest(context: Context, request: String): String {
        val lowerRequest = request.lowercase()

        return when {
            // یادآوری
            lowerRequest.contains("یادآوری") || lowerRequest.contains("یادآور") -> {
                "برای تنظیم یادآوری، لطفاً از دستور زیر استفاده کنید:\n'یادآوری ساعت [ساعت]:[دقیقه] - [پیام]'\n\nمثال: یادآوری ساعت 8:30 - جلسه کاری"
            }

            // مسیریابی
            lowerRequest.contains("مسیر") || lowerRequest.contains("راهنما") || lowerRequest.contains("navigation") -> {
                extractAndNavigate(context, request)
            }

            // تماس
            lowerRequest.contains("تماس") || lowerRequest.contains("زنگ بزن") -> {
                extractAndCall(context, request)
            }

            // پیامک
            lowerRequest.contains("پیامک") || lowerRequest.contains("sms") -> {
                extractAndSMS(context, request)
            }

            // جستجو
            lowerRequest.contains("جستجو") || lowerRequest.contains("search") -> {
                extractAndSearch(context, request)
            }

            // تقویم
            lowerRequest.contains("تقویم") || lowerRequest.contains("رویداد") -> {
                "برای اضافه کردن رویداد، لطفاً از دستور زیر استفاده کنید:\n'تقویم [عنوان] - [تاریخ] [ساعت]'"
            }

            else -> {
                "می‌توانم به شما در موارد زیر کمک کنم:\n" +
                        "• تنظیم یادآوری\n" +
                        "• مسیریابی و راهنمای مسیر\n" +
                        "• تماس تلفنی\n" +
                        "• ارسال پیامک\n" +
                        "• جستجوی وب\n" +
                        "• اضافه کردن رویداد به تقویم\n\n" +
                        "مثال: 'مسیریابی به تهران' یا 'یادآوری ساعت 8:00 - جلسه کاری'"
            }
        }
    }

    private fun extractAndNavigate(context: Context, request: String): String {
        val lowerRequest = request.lowercase()
        val withPersianVoice = lowerRequest.contains("صوت فارسی") || 
                               lowerRequest.contains("صوتی") ||
                               lowerRequest.contains("راهنمای صوتی")
        
        val destination = request
            .replace(Regex("مسیر(یابی)?\\s*(به)?", RegexOption.IGNORE_CASE), "")
            .replace(Regex("راهنما(ی)?\\s*مسیر", RegexOption.IGNORE_CASE), "")
            .replace(Regex("با\\s*صوت\\s*فارسی", RegexOption.IGNORE_CASE), "")
            .replace(Regex("صوت(ی)?", RegexOption.IGNORE_CASE), "")
            .replace("از", "")
            .trim()

        return if (destination.isNotEmpty()) {
            if (openNavigation(context, destination, withPersianVoice)) {
                if (withPersianVoice) {
                    "در حال باز کردن مسیریابی به $destination با راهنمای صوتی فارسی..."
                } else {
                    "در حال باز کردن مسیریابی به $destination در Google Maps..."
                }
            } else {
                "خطا در باز کردن مسیریابی"
            }
        } else {
            """
            لطفاً مقصد را مشخص کنید.
            
            مثال‌ها:
            • 'مسیریابی به تهران'
            • 'مسیریابی به تهران با صوت فارسی'
            • 'راهنمای صوتی مسیر به مشهد'
            """.trimIndent()
        }
    }

    private fun extractAndCall(context: Context, request: String): String {
        val phoneRegex = Regex("\\d{10,11}")
        val phoneMatch = phoneRegex.find(request)

        return if (phoneMatch != null && makePhoneCall(context, phoneMatch.value)) {
            "در حال باز کردن شماره‌گیری..."
        } else {
            "لطفاً شماره تلفن را مشخص کنید. مثال: 'تماس با 09121234567'"
        }
    }

    private fun extractAndSMS(context: Context, request: String): String {
        val phoneRegex = Regex("\\d{10,11}")
        val phoneMatch = phoneRegex.find(request)

        return if (phoneMatch != null && sendSMS(context, phoneMatch.value, "")) {
            "در حال باز کردن پیامک..."
        } else {
            "لطفاً شماره تلفن را مشخص کنید. مثال: 'پیامک به 09121234567'"
        }
    }

    private fun extractAndSearch(context: Context, request: String): String {
        val query = request
            .replace(Regex("جستجو(ی)?", RegexOption.IGNORE_CASE), "")
            .replace("search", "", ignoreCase = true)
            .trim()

        return if (query.isNotEmpty() && searchWeb(context, query)) {
            "در حال جستجو: $query"
        } else {
            "لطفاً موضوع جستجو را مشخص کنید. مثال: 'جستجوی هوش مصنوعی'"
        }
    }
}
