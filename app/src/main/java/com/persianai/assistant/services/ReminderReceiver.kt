package com.persianai.assistant.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.os.PowerManager

import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.persianai.assistant.activities.FullScreenAlarmActivity
import com.persianai.assistant.R
import com.persianai.assistant.activities.DashboardActivity
import com.persianai.assistant.utils.SmartReminderManager

/**
 * BroadcastReceiver برای دریافت الارم یادآوری
 * این Receiver زمانی فعال می‌شود که AlarmManager الارم را trigger کند
 */
class ReminderReceiver : BroadcastReceiver() {
    
    private val TAG = "ReminderReceiver"
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "🔔 onReceive - action: ${intent.action}")
        
        // WakeLock بگیری - خیلی مهم!
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "PersianAssistant::ReminderReceiver"
        ).apply {
            acquire(10 * 60 * 1000L) // 10 دقیقه
        }
        
        try {
            Log.d(TAG, "⚡ WakeLock acquired")
            
            when (intent.action) {
                // الارم یادآوری
                "com.persianai.assistant.REMINDER_ALARM" -> {
                    handleReminderAlarm(context, intent)
                }
                // علامت‌گذاری به عنوان انجام شده
                "MARK_AS_DONE" -> {
                    handleMarkAsDone(context, intent)
                }
                // تعویق یادآوری
                "SNOOZE_REMINDER" -> {
                    handleSnoozeReminder(context, intent)
                }
                // بوت تمام
                Intent.ACTION_BOOT_COMPLETED -> {
                    Log.d(TAG, "📱 BOOT_COMPLETED - reschedule reminders")
                    rescheduleAllReminders(context)
                }
                else -> {
                    Log.d(TAG, "Unknown action: ${intent.action}")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in onReceive", e)
            e.printStackTrace()
        } finally {
            try {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                    Log.d(TAG, "⚡ WakeLock released")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing wakelock", e)
            }
        }
    }
    
    /**
     * پردازش Reminder Alarm
     */
    private fun handleReminderAlarm(context: Context, intent: Intent) {
        try {
            val reminderId = intent.getStringExtra("smart_reminder_id")
            val title = intent.getStringExtra("reminder_title") ?: "⏰ یادآوری"
            val description = intent.getStringExtra("reminder_description") ?: ""
            val alertType = intent.getStringExtra("alert_type") ?: "NOTIFICATION"
            val useAlarm = intent.getBooleanExtra("use_alarm", false)
            val tags = intent.getStringArrayListExtra("reminder_tags") ?: arrayListOf()
            val isSmartReminder = alertType == "SMART" ||
                tags.any { it.startsWith("smart:true") }

            Log.d(TAG, "📝 Reminder received: title=$title, alertType=$alertType, id=$reminderId, smart=$isSmartReminder")

            val useFullScreen = alertType == "FULL_SCREEN" ||
                useAlarm ||
                tags.any { it.startsWith("use_alarm:true") }

            if (useFullScreen) {
                showFullScreenAlarm(context, title, description, reminderId, tags, isSmartReminder)
                return
            }

            // اگر حالت هوشمند درخواست شده، آن را به ReminderService بسپاریم تا AI+TTS را اجرا کند
            if (isSmartReminder) {
                try {
                    val svcIntent = Intent(context, com.persianai.assistant.services.ReminderService::class.java).apply {
                        action = "PLAY_SMART_REMINDER"
                        putExtra("smart_reminder_id", reminderId)
                        putExtra("reminder_title", title)
                        putExtra("reminder_description", description)
                        putStringArrayListExtra("reminder_tags", tags)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(svcIntent)
                    } else {
                        context.startService(svcIntent)
                    }
                    Log.d(TAG, "Delegated smart reminder to ReminderService: $title")
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to start ReminderService for smart reminder: ${e.message}", e)
                    // fallback to heads-up
                }
            }

            // حالت پیش‌فرض: heads-up notification
            showHeadsUpNotification(context, title, description, reminderId)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling alarm", e)
        }
    }
    
    /**
     * نمایش Full Screen Alarm - مستقیم
     */
    private fun showFullScreenAlarm(
        context: Context,
        title: String,
        description: String,
        reminderId: String?,
        tags: ArrayList<String> = arrayListOf(),
        isSmartReminder: Boolean = false
    ) {
        try {
            Log.d(TAG, "🎬 Forcing FullScreenAlarmActivity + fullScreen notification")

            // Intent برای Activity
            val alarmIntent = Intent(context, FullScreenAlarmActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                putExtra("title", title)
                putExtra("description", description)
                putExtra("smart_reminder_id", reminderId)
                putExtra("is_smart_reminder", isSmartReminder)
                putStringArrayListExtra("tags", tags)
            }

            val pendingIntentFlags =
                PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_IMMUTABLE
                } else 0

            val fullScreenPendingIntent = PendingIntent.getActivity(
                context,
                reminderId?.hashCode() ?: 1001,
                alarmIntent,
                pendingIntentFlags
            )

            // کانال نوتیفیکیشن با اهمیت بسیار بالا
            val channelId = "full_screen_alarm_channel"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "Full Screen Alarm",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    this.description = "Full screen alarm reminders"
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(description.ifEmpty { "یادآوری تمام‌صفحه" })
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setContentIntent(fullScreenPendingIntent)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(false)
                .setOngoing(true)
                .build()

            NotificationManagerCompat.from(context).notify(reminderId?.hashCode() ?: 2001, notification)
            Log.d(TAG, "✅ fullScreen notification posted (forced)")

            // همچنین Activity را صراحتاً استارت کنیم
            context.startActivity(alarmIntent)
            Log.d(TAG, "✅ FullScreen activity start requested")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting activity", e)
        }
    }

    /**
     * اعلان Heads-up برای حالت نوتیفیکیشن (بدون ماندگاری در نوار)
     */
    private fun showHeadsUpNotification(
        context: Context,
        title: String,
        description: String,
        reminderId: String?
    ) {
        try {
            val channelId = "reminder_alert_channel"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "Reminder Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    this.description = "Heads-up reminders"
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(channel)
            }

            val pendingIntentFlags =
                PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_IMMUTABLE
                } else 0

            val tapIntent = Intent(context, DashboardActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("smart_reminder_id", reminderId)
                putExtra("reminder_title", title)
                putExtra("reminder_description", description)
                putExtra("open_screen", "reminder")
            }.let {
                PendingIntent.getActivity(
                    context,
                    reminderId?.hashCode() ?: 2001,
                    it,
                    pendingIntentFlags
                )
            }

            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(description.ifEmpty { "یادآوری" })
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setOngoing(false)
                .setContentIntent(tapIntent)
                .build()

            NotificationManagerCompat.from(context).notify(reminderId?.hashCode() ?: 2002, notification)
            Log.d(TAG, "✅ Heads-up notification posted")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error posting heads-up notification", e)
        }
    }

    /**
     * علامت‌گذاری به عنوان انجام شده
     */
    private fun handleMarkAsDone(context: Context, intent: Intent) {
        try {
            val smartReminderId = intent.getStringExtra("smart_reminder_id")
            
            if (!smartReminderId.isNullOrEmpty()) {
                val mgr = SmartReminderManager(context)
                mgr.completeReminder(smartReminderId)
                Log.d(TAG, "✅ Reminder completed: $smartReminderId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error marking as done", e)
        }
    }
    
    /**
     * تعویق یادآوری
     */
    private fun handleSnoozeReminder(context: Context, intent: Intent) {
        try {
            val smartReminderId = intent.getStringExtra("smart_reminder_id")
            
            if (!smartReminderId.isNullOrEmpty()) {
                val mgr = SmartReminderManager(context)
                mgr.snoozeReminder(smartReminderId, 5)
                Log.d(TAG, "⏰ Reminder snoozed: $smartReminderId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error snoozing reminder", e)
        }
    }
    
    /**
     * reschedule تمام یادآوری‌ها
     */
    private fun rescheduleAllReminders(context: Context) {
        try {
            Handler(Looper.getMainLooper()).post {
                val mgr = SmartReminderManager(context)
                val reminders = mgr.getActiveReminders()
                
                Log.d(TAG, "📋 Rescheduling ${reminders.size} reminders...")
                
                for (reminder in reminders) {
                    try {
                        mgr.scheduleReminder(reminder)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error scheduling reminder: ${reminder.id}", e)
                    }
                }
                
                Log.d(TAG, "✅ All reminders rescheduled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error rescheduling reminders", e)
        }
    }
}