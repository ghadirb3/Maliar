package com.persianai.assistant.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.persianai.assistant.services.ReminderReceiver
import java.util.Calendar

/**
 * مدیریت پیشرفته یادآوری‌های هوشمند
 * شامل: یادآوری‌های تکراری، مبتنی بر مکان، زمینه‌محور، و خانوادگی
 */
class SmartReminderManager(private val context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences("smart_reminders", Context.MODE_PRIVATE)
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val gson = Gson()
    
    companion object {
        private const val TAG = "SmartReminder"
        private const val KEY_REMINDERS = "reminders"
    }
    
    /**
     * نوع یادآوری
     */
    enum class ReminderType(val displayName: String) {
        SIMPLE("ساده"),
        RECURRING("تکراری"),
        LOCATION_BASED("مبتنی بر مکان"),
        BIRTHDAY("تولد"),
        ANNIVERSARY("سالگرد"),
        BILL_PAYMENT("پرداخت قبض"),
        MEDICINE("دارو"),
        FAMILY("خانوادگی"),
        SHOPPING("خرید"),
        TASK("کار روزانه")
    }
    
    /**
     * اولویت یادآوری
     */
    enum class Priority(val displayName: String, val color: String) {
        LOW("کم", "#4CAF50"),
        MEDIUM("متوسط", "#FF9800"),
        HIGH("زیاد", "#F44336"),
        URGENT("فوری", "#9C27B0")
    }
    
    /**
     * نوع هشدار یادآوری
     */
    enum class AlertType {
        NOTIFICATION,
        FULL_SCREEN,
        SMART
    }
    
    /**
     * الگوی تکرار
     */
    enum class RepeatPattern(val displayName: String) {
        ONCE("یکبار"),
        DAILY("روزانه"),
        WEEKLY("هفتگی"),
        MONTHLY("ماهانه"),
        YEARLY("سالانه"),
        WEEKDAYS("روزهای کاری"),
        WEEKENDS("آخر هفته"),
        CUSTOM("سفارشی")
    }
    
    /**
     * یادآوری هوشمند
     */
    data class SmartReminder(
        val id: String,
        val title: String,
        val description: String = "",
        val type: ReminderType,
        val priority: Priority = Priority.MEDIUM,
        val alertType: AlertType = AlertType.NOTIFICATION,
        val triggerTime: Long,
        val repeatPattern: RepeatPattern = RepeatPattern.ONCE,
        val customRepeatDays: List<Int> = emptyList(), // 1=یکشنبه, 2=دوشنبه, ...
        val locationLat: Double? = null,
        val locationLng: Double? = null,
        val locationRadius: Int = 100, // متر
        val locationName: String = "",
        val isCompleted: Boolean = false,
        val completedAt: Long? = null,
        val createdAt: Long = System.currentTimeMillis(),
        val tags: List<String> = emptyList(),
        val relatedPerson: String = "", // برای تولدها و یادآوری‌های خانوادگی
        val attachments: List<String> = emptyList(),
        val snoozeCount: Int = 0,
        val lastSnoozed: Long? = null,
        val notes: String = ""
    )
    
    /**
     * افزودن یادآوری
     */
    fun addReminder(reminder: SmartReminder): SmartReminder {
        val reminders = getAllReminders().toMutableList()
        reminders.add(reminder)
        saveReminders(reminders)
        
        // تنظیم آلارم
        scheduleReminder(reminder)
        
        Log.i(TAG, "✅ یادآوری جدید: ${reminder.title} (${reminder.type.displayName})")
        
        return reminder
    }

    fun addReminderWithoutAlarm(reminder: SmartReminder): SmartReminder {
        val reminders = getAllReminders().toMutableList()
        reminders.add(reminder)
        saveReminders(reminders)
        
        Log.i(TAG, "✅ یادآوری جدید (بدون آلارم): ${reminder.title} (${reminder.type.displayName})")
        
        return reminder
    }
    
    /**
     * ایجاد یادآوری ساده
     */
    fun createSimpleReminder(
        title: String,
        description: String = "",
        triggerTime: Long,
        priority: Priority = Priority.MEDIUM,
        alertType: AlertType = AlertType.NOTIFICATION
    ): SmartReminder {
        val reminder = SmartReminder(
            id = System.currentTimeMillis().toString(),
            title = title,
            description = description,
            type = ReminderType.SIMPLE,
            priority = priority,
            alertType = alertType,
            triggerTime = triggerTime
        )
        return addReminder(reminder)
    }
    
    /**
     * ایجاد یادآوری تکراری
     */
    fun createRecurringReminder(
        title: String,
        description: String = "",
        firstTriggerTime: Long,
        repeatPattern: RepeatPattern,
        customDays: List<Int> = emptyList(),
        priority: Priority = Priority.MEDIUM
    ): SmartReminder {
        val reminder = SmartReminder(
            id = System.currentTimeMillis().toString(),
            title = title,
            description = description,
            type = ReminderType.RECURRING,
            priority = priority,
            triggerTime = firstTriggerTime,
            repeatPattern = repeatPattern,
            customRepeatDays = customDays
        )
        return addReminder(reminder)
    }
    
    /**
     * ایجاد یادآوری تولد
     */
    fun createBirthdayReminder(
        personName: String,
        birthdayDate: Long,
        notes: String = ""
    ): SmartReminder {
        val reminder = SmartReminder(
            id = "birthday_${System.currentTimeMillis()}",
            title = "🎂 تولد $personName",
            description = "امروز تولد $personName است!",
            type = ReminderType.BIRTHDAY,
            priority = Priority.HIGH,
            triggerTime = birthdayDate,
            repeatPattern = RepeatPattern.YEARLY,
            relatedPerson = personName,
            notes = notes
        )
        return addReminder(reminder)
    }
    
    /**
     * ایجاد یادآوری پرداخت قبض
     */
    fun createBillReminder(
        billName: String,
        dueDate: Long,
        amount: Long = 0,
        isRecurring: Boolean = false
    ): SmartReminder {
        val reminder = SmartReminder(
            id = "bill_${System.currentTimeMillis()}",
            title = "💰 پرداخت $billName",
            description = if (amount > 0) "مبلغ: ${String.format("%,d", amount)} تومان" else "",
            type = ReminderType.BILL_PAYMENT,
            priority = Priority.HIGH,
            triggerTime = dueDate,
            repeatPattern = if (isRecurring) RepeatPattern.MONTHLY else RepeatPattern.ONCE
        )
        return addReminder(reminder)
    }
    
    /**
     * ایجاد یادآوری دارو
     */
    fun createMedicineReminder(
        medicineName: String,
        times: List<Pair<Int, Int>>, // (hour, minute)
        notes: String = ""
    ): List<SmartReminder> {
        val reminders = mutableListOf<SmartReminder>()
        
        times.forEach { (hour, minute) ->
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.HOUR_OF_DAY, hour)
            calendar.set(Calendar.MINUTE, minute)
            calendar.set(Calendar.SECOND, 0)
            
            // اگر زمان گذشته، برای فردا تنظیم کن
            if (calendar.timeInMillis < System.currentTimeMillis()) {
                calendar.add(Calendar.DAY_OF_MONTH, 1)
            }
            
            val reminder = SmartReminder(
                id = "medicine_${System.currentTimeMillis()}_$hour$minute",
                title = "💊 مصرف دارو: $medicineName",
                description = "ساعت $hour:${String.format("%02d", minute)}",
                type = ReminderType.MEDICINE,
                priority = Priority.URGENT,
                triggerTime = calendar.timeInMillis,
                repeatPattern = RepeatPattern.DAILY,
                notes = notes
            )
            reminders.add(addReminder(reminder))
        }
        
        return reminders
    }
    
    /**
     * ایجاد یادآوری مبتنی بر مکان
     */
    fun createLocationReminder(
        title: String,
        description: String,
        lat: Double,
        lng: Double,
        radius: Int = 100,
        locationName: String
    ): SmartReminder {
        val reminder = SmartReminder(
            id = "location_${System.currentTimeMillis()}",
            title = title,
            description = description,
            type = ReminderType.LOCATION_BASED,
            priority = Priority.MEDIUM,
            triggerTime = System.currentTimeMillis(),
            locationLat = lat,
            locationLng = lng,
            locationRadius = radius,
            locationName = locationName
        )
        return addReminderWithoutAlarm(reminder)
    }
    
    /**
     * دریافت تمام یادآوری‌ها
     */
    fun getAllReminders(): List<SmartReminder> {
        val json = prefs.getString(KEY_REMINDERS, "[]") ?: "[]"
        val type = object : TypeToken<List<SmartReminder>>() {}.type
        return gson.fromJson(json, type)
    }
    
    /**
     * دریافت یادآوری‌های فعال
     */
    fun getActiveReminders(): List<SmartReminder> {
        return getAllReminders().filter { !it.isCompleted }
    }
    
    /**
     * دریافت یادآوری‌های امروز
     */
    fun getTodayReminders(): List<SmartReminder> {
        val now = Calendar.getInstance()
        val startOfDay = now.clone() as Calendar
        startOfDay.set(Calendar.HOUR_OF_DAY, 0)
        startOfDay.set(Calendar.MINUTE, 0)
        startOfDay.set(Calendar.SECOND, 0)
        
        val endOfDay = now.clone() as Calendar
        endOfDay.set(Calendar.HOUR_OF_DAY, 23)
        endOfDay.set(Calendar.MINUTE, 59)
        endOfDay.set(Calendar.SECOND, 59)
        
        return getActiveReminders()
            .filter { it.triggerTime in startOfDay.timeInMillis..endOfDay.timeInMillis }
            .sortedBy { it.triggerTime }
    }
    
    /**
     * دریافت یادآوری‌های سررسید گذشته
     */
    fun getOverdueReminders(): List<SmartReminder> {
        val now = System.currentTimeMillis()
        return getActiveReminders()
            .filter { it.triggerTime < now && it.repeatPattern == RepeatPattern.ONCE }
            .sortedBy { it.triggerTime }
    }
    
    /**
     * دریافت یادآوری‌های آینده
     */
    fun getUpcomingReminders(days: Int = 7): List<SmartReminder> {
        val now = System.currentTimeMillis()
        val future = now + (days * 24 * 60 * 60 * 1000)
        
        return getActiveReminders()
            .filter { it.triggerTime in now..future }
            .sortedBy { it.triggerTime }
    }
    
    /**
     * علامت‌زدن یادآوری به عنوان انجام شده
     */
    fun completeReminder(reminderId: String): Boolean {
        val reminders = getAllReminders().toMutableList()
        val index = reminders.indexOfFirst { it.id == reminderId }
        
        if (index != -1) {
            val reminder = reminders[index]
            
            // اگر تکراری نیست، علامت بزن
            if (reminder.repeatPattern == RepeatPattern.ONCE) {
                reminders[index] = reminder.copy(
                    isCompleted = true,
                    completedAt = System.currentTimeMillis()
                )
            } else {
                // برای تکراری، زمان بعدی را محاسبه کن
                val nextTime = calculateNextTriggerTime(reminder)
                reminders[index] = reminder.copy(triggerTime = nextTime)
                scheduleReminder(reminders[index])
            }
            
            saveReminders(reminders)
            Log.i(TAG, "✅ یادآوری انجام شد: ${reminder.title}")
            return true
        }
        
        return false
    }
    
    /**
     * به تعویق انداختن یادآوری (Snooze)
     */
    fun snoozeReminder(reminderId: String, minutes: Int = 10): Boolean {
        val reminders = getAllReminders().toMutableList()
        val index = reminders.indexOfFirst { it.id == reminderId }
        
        if (index != -1) {
            val reminder = reminders[index]
            val newTime = System.currentTimeMillis() + (minutes * 60 * 1000)
            
            reminders[index] = reminder.copy(
                triggerTime = newTime,
                snoozeCount = reminder.snoozeCount + 1,
                lastSnoozed = System.currentTimeMillis()
            )
            
            saveReminders(reminders)
            scheduleReminder(reminders[index])
            
            Log.i(TAG, "⏰ یادآوری به تعویق افتاد: ${reminder.title} ($minutes دقیقه)")
            return true
        }
        
        return false
    }
    
    /**
     * حذف یادآوری
     */
    fun updateReminder(updatedReminder: SmartReminder): Boolean {
        val reminders = getAllReminders().toMutableList()
        val index = reminders.indexOfFirst { it.id == updatedReminder.id }

        if (index != -1) {
            val oldReminder = reminders[index]
            reminders[index] = updatedReminder
            saveReminders(reminders)

            // Reschedule if trigger time is different
            if (oldReminder.triggerTime != updatedReminder.triggerTime) {
                cancelReminder(updatedReminder.id)
                scheduleReminder(updatedReminder)
            }
            Log.i(TAG, "🔄 یادآوری به‌روز شد: ${updatedReminder.title}")
            return true
        }
        return false
    }

    fun deleteReminder(reminderId: String): Boolean {
        val reminders = getAllReminders().toMutableList()
        val removed = reminders.removeIf { it.id == reminderId }
        
        if (removed) {
            saveReminders(reminders)
            cancelReminder(reminderId)
            Log.i(TAG, "🗑️ یادآوری حذف شد")
        }
        
        return removed
    }
    
    /**
     * محاسبه زمان trigger بعدی برای یادآوری‌های تکراری
     */
    private fun calculateNextTriggerTime(reminder: SmartReminder): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = reminder.triggerTime
        
        when (reminder.repeatPattern) {
            RepeatPattern.DAILY -> calendar.add(Calendar.DAY_OF_MONTH, 1)
            RepeatPattern.WEEKLY -> calendar.add(Calendar.WEEK_OF_YEAR, 1)
            RepeatPattern.MONTHLY -> calendar.add(Calendar.MONTH, 1)
            RepeatPattern.YEARLY -> calendar.add(Calendar.YEAR, 1)
            RepeatPattern.WEEKDAYS -> {
                do {
                    calendar.add(Calendar.DAY_OF_MONTH, 1)
                } while (calendar.get(Calendar.DAY_OF_WEEK) in listOf(Calendar.SATURDAY, Calendar.FRIDAY))
            }
            RepeatPattern.WEEKENDS -> {
                do {
                    calendar.add(Calendar.DAY_OF_MONTH, 1)
                } while (calendar.get(Calendar.DAY_OF_WEEK) !in listOf(Calendar.SATURDAY, Calendar.FRIDAY))
            }
            RepeatPattern.CUSTOM -> {
                if (reminder.customRepeatDays.isNotEmpty()) {
                    do {
                        calendar.add(Calendar.DAY_OF_MONTH, 1)
                        // تبدیل Calendar.DAY_OF_WEEK (1-7) به 0-6
                        val dayOfWeek = (calendar.get(Calendar.DAY_OF_WEEK) - 1) % 7
                    } while (dayOfWeek !in reminder.customRepeatDays)
                }
            }
            else -> return reminder.triggerTime
        }
        
        return calendar.timeInMillis
    }
    
    /**
     * تنظیم آلارم برای یادآوری
     */
    fun scheduleReminder(reminder: SmartReminder) {
        // برای اندروید 12 به بالا، نیاز به اجازه آلارم دقیق داریم
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) {
                try {
                    val settingsIntent = Intent(
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:${context.packageName}")
                    ).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(settingsIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error requesting exact alarm permission", e)
                }

                Toast.makeText(
                    context,
                    "لطفاً در تنظیمات، اجازهٔ آلارم دقیق را برای برنامه فعال کنید.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        val useAlarm = reminder.alertType == AlertType.FULL_SCREEN ||
            reminder.tags.any { it.startsWith("use_alarm:true") }
        
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = "com.persianai.assistant.REMINDER_ALARM"
            // ID عددی برای استفاده در NotificationManager و requestCode
            putExtra("reminder_id", reminder.id.hashCode())
            // ID اصلی برای کار با SmartReminderManager
            putExtra("smart_reminder_id", reminder.id)

            putExtra("reminder_title", reminder.title)
            putExtra("reminder_description", reminder.description)
            putExtra("reminder_priority", reminder.priority.name)
            putExtra("message", reminder.title)
            putExtra("use_alarm", useAlarm)
            putExtra("alert_type", reminder.alertType.name)
            putStringArrayListExtra("reminder_tags", ArrayList(reminder.tags))
        }
        
        Log.d(TAG, "🔔 Intent prepared: title=${reminder.title}, alertType=${reminder.alertType}, useAlarm=$useAlarm, tags=${reminder.tags}")
        Log.d(TAG, "🔔 FULL_SCREEN check: alertType=${reminder.alertType}, is FULL_SCREEN=${reminder.alertType == AlertType.FULL_SCREEN}")
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminder.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        try {
            // اگر زمان گذشته است، برای اولین بار بعد از الآن تنظیم کن
            var triggerTime = reminder.triggerTime
            val now = System.currentTimeMillis()
            
            if (triggerTime < now && reminder.repeatPattern != RepeatPattern.ONCE) {
                // برای یادآوری‌های تکراری، اگر زمان گذشته بود، برای دفعهٔ بعد محاسبه کن
                triggerTime = calculateNextTriggerTime(reminder, now)
            } else if (triggerTime < now && reminder.repeatPattern == RepeatPattern.ONCE) {
                // برای یادآوری‌های یکبار، اگر زمان گذشته بود، فوراً اجرا کن
                triggerTime = now + 1000 // یک ثانیه بعد
            }
            
            Log.d(TAG, "Alarm will trigger at: $triggerTime (now: $now)")

            if (useAlarm) {
                val showIntent = Intent(context, com.persianai.assistant.activities.FullScreenAlarmActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("smart_reminder_id", reminder.id)
                }
                val showPendingIntent = PendingIntent.getActivity(
                    context,
                    reminder.id.hashCode() + 5000,
                    showIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerTime, showPendingIntent)
                alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
                Log.d(TAG, "✅ AlarmClock set for fullscreen/smart: ${reminder.title}")
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                Log.d(TAG, "✅ Alarm set for: ${reminder.title}")
            }

            ensureReminderServiceRunning()
        } catch (e: SecurityException) {
            Log.e(TAG, "خطا در تنظیم آلارم: ${e.message}")
            // اگر setExactAndAllowWhileIdle ناموفق بود، از setAndAllowWhileIdle استفاده کن
            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    reminder.triggerTime,
                    pendingIntent
                )
                Log.d(TAG, "⏰ آلارم (غیر دقیق) تنظیم شد: ${reminder.title}")
            } catch (e2: Exception) {
                Log.e(TAG, "خطا در تنظیم آلارم (غیر دقیق): ${e2.message}")
            }
        }
    }

    private fun ensureReminderServiceRunning() {
        try {
            val serviceIntent = Intent(context, com.persianai.assistant.services.ReminderService::class.java)
            context.startService(serviceIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start ReminderService backup", e)
        }
    }
    
    /**
     * محاسبهٔ زمان بعدی برای یادآوری تکراری
     */
    fun calculateNextTriggerTime(reminder: SmartReminder, now: Long): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = reminder.triggerTime
        
        return when (reminder.repeatPattern) {
            RepeatPattern.DAILY -> {
                calendar.add(Calendar.DAY_OF_MONTH, 1)
                calendar.timeInMillis
            }
            RepeatPattern.WEEKLY -> {
                calendar.add(Calendar.WEEK_OF_YEAR, 1)
                calendar.timeInMillis
            }
            RepeatPattern.MONTHLY -> {
                calendar.add(Calendar.MONTH, 1)
                calendar.timeInMillis
            }
            RepeatPattern.YEARLY -> {
                calendar.add(Calendar.YEAR, 1)
                calendar.timeInMillis
            }
            RepeatPattern.WEEKDAYS -> {
                // روزهای کاری: دوشنبه تا جمعه
                do {
                    calendar.add(Calendar.DAY_OF_MONTH, 1)
                } while (calendar.get(Calendar.DAY_OF_WEEK) in listOf(Calendar.SATURDAY, Calendar.SUNDAY))
                calendar.timeInMillis
            }
            RepeatPattern.WEEKENDS -> {
                // آخر هفته: شنبه و یکشنبه
                do {
                    calendar.add(Calendar.DAY_OF_MONTH, 1)
                } while (calendar.get(Calendar.DAY_OF_WEEK) !in listOf(Calendar.SATURDAY, Calendar.SUNDAY))
                calendar.timeInMillis
            }
            RepeatPattern.CUSTOM -> {
                // سفارشی: روزهای انتخاب شده
                if (reminder.customRepeatDays.isNotEmpty()) {
                    do {
                        calendar.add(Calendar.DAY_OF_MONTH, 1)
                        // تبدیل Calendar.DAY_OF_WEEK (1-7) به 0-6
                        val dayOfWeek = (calendar.get(Calendar.DAY_OF_WEEK) - 1) % 7
                    } while (dayOfWeek !in reminder.customRepeatDays)
                } else {
                    // اگر روزی انتخاب نشده، مثل هفتگی
                    calendar.add(Calendar.WEEK_OF_YEAR, 1)
                }
                calendar.timeInMillis
            }
            RepeatPattern.ONCE -> now + 1000
        }
    }
    
    /**
     * لغو آلارم یادآوری
     */
    private fun cancelReminder(reminderId: String) {
        val intent = Intent(context, ReminderReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminderId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        alarmManager.cancel(pendingIntent)
        Log.d(TAG, "❌ آلارم لغو شد")
    }
    
    /**
     * ذخیره یادآوری‌ها
     */
    private fun saveReminders(reminders: List<SmartReminder>) {
        val json = gson.toJson(reminders)
        prefs.edit().putString(KEY_REMINDERS, json).apply()
    }
    
    /**
     * دریافت آمار یادآوری‌ها
     */
    fun getReminderStats(): ReminderStats {
        val all = getAllReminders()
        val active = getActiveReminders()
        val completed = all.filter { it.isCompleted }
        val today = getTodayReminders()
        val overdue = getOverdueReminders()
        
        return ReminderStats(
            totalReminders = all.size,
            activeReminders = active.size,
            completedReminders = completed.size,
            todayReminders = today.size,
            overdueReminders = overdue.size,
            completionRate = if (all.isNotEmpty()) (completed.size.toFloat() / all.size * 100).toInt() else 0
        )
    }
    
    data class ReminderStats(
        val totalReminders: Int,
        val activeReminders: Int,
        val completedReminders: Int,
        val todayReminders: Int,
        val overdueReminders: Int,
        val completionRate: Int
    )
}
