package com.persianai.assistant.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.persianai.assistant.R
import com.persianai.assistant.activities.DashboardActivity

/**
 * کلاس کمکی برای مدیریت نوتیفیکیشن‌ها
 */
object NotificationHelper {
    
    private const val CHANNEL_ID_REMINDERS = "reminders_channel"
    private const val CHANNEL_ID_WEATHER = "weather_channel"
    private const val CHANNEL_ID_GENERAL = "general_channel"
    private const val CHANNEL_ID_CALLS = "calls_channel"
    
    /**
     * ایجاد کانال‌های نوتیفیکیشن
     */
    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // کانال یادآوری‌ها
            val remindersChannel = NotificationChannel(
                CHANNEL_ID_REMINDERS,
                "یادآوری‌ها",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "نوتیفیکیشن‌های یادآوری"
                enableVibration(true)
                enableLights(true)
            }
            
            // کانال آب و هوا
            val weatherChannel = NotificationChannel(
                CHANNEL_ID_WEATHER,
                "آب و هوا",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "هشدارهای آب و هوا"
            }
            
            // کانال عمومی
            val generalChannel = NotificationChannel(
                CHANNEL_ID_GENERAL,
                "عمومی",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "نوتیفیکیشن‌های عمومی"
            }
            
            // کانال تماس‌ها
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
            
            notificationManager.createNotificationChannel(remindersChannel)
            notificationManager.createNotificationChannel(weatherChannel)
            notificationManager.createNotificationChannel(generalChannel)
            notificationManager.createNotificationChannel(callsChannel)
        }
    }
    
    /**
     * نمایش نوتیفیکیشن یادآوری
     */
    fun showReminderNotification(
        context: Context,
        title: String,
        message: String,
        notificationId: Int = System.currentTimeMillis().toInt()
    ) {
        val intent = Intent(context, DashboardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .build()
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notification)
    }
    
    /**
     * نمایش نوتیفیکیشن آب و هوا
     */
    fun showWeatherNotification(
        context: Context,
        city: String,
        temperature: String,
        description: String,
        icon: String
    ) {
        val intent = Intent(context, DashboardActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_WEATHER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$icon آب و هوا $city")
            .setContentText("$temperature - $description")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(2000, notification)
    }
    
    /**
     * نمایش نوتیفیکیشن عمومی
     */
    fun showGeneralNotification(
        context: Context,
        title: String,
        message: String,
        notificationId: Int = 3000
    ) {
        val intent = Intent(context, DashboardActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_GENERAL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .build()
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notification)
    }
    
    /**
     * نمایش نوتیفیکیشن (برای استفاده در manager classes)
     */
    suspend fun showNotification(
        context: Context,
        title: String,
        message: String,
        channelId: String = CHANNEL_ID_GENERAL
    ) {
        showGeneralNotification(context, title, message)
    }
    
    /**
     * زمان‌بندی نوتیفیکیشن (برای استفاده در manager classes)
     */
    suspend fun scheduleNotification(
        context: Context,
        title: String,
        message: String,
        time: Long,
        channelId: String = CHANNEL_ID_REMINDERS
    ) {
        // فعلاً نوتیفیکیشن فوری ارسال می‌شود
        showReminderNotification(context, title, message)
    }
    
    /**
     * لغو نوتیفیکیشن
     */
    fun cancelNotification(context: Context, notificationId: Int) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(notificationId)
    }
    
    /**
     * لغو همه نوتیفیکیشن‌ها
     */
    fun cancelAllNotifications(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancelAll()
    }
}
