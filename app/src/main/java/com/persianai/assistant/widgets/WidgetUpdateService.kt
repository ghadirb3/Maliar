package com.persianai.assistant.widgets

import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.RemoteViews
import com.persianai.assistant.R
import com.persianai.assistant.api.WorldWeatherAPI
import com.persianai.assistant.utils.PersianDateConverter
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

/**
 * سرویس بروزرسانی خودکار ویجت‌ها
 */
class WidgetUpdateService : Service() {
    
    companion object {
        private const val TAG = "WidgetUpdateService"
    }
    
    private val updateHandler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        super.onCreate()
        startUpdating()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateAllWidgets()
        return START_STICKY
    }
    
    private fun startUpdating() {
        updateRunnable = object : Runnable {
            override fun run() {
                updateAllWidgets()
                // بروزرسانی هر 30 ثانیه برای نمایش زمان دقیق
                updateHandler.postDelayed(this, 30000)
            }
        }
        updateHandler.post(updateRunnable!!)
    }
    
    private fun updateAllWidgets() {
        val context = this
        
        // بروزرسانی ویجت متوسط
        updateMediumWidgets(context)
        
        // بروزرسانی ویجت کوچک
        updateSmallWidgets(context)
        
        // بروزرسانی ویجت بزرگ
        updateLargeWidgets(context)
    }
    
    private fun updateMediumWidgets(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val widgetComponent = ComponentName(context, PersianCalendarWidget::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(widgetComponent)
        
        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_persian_calendar)
            
            // بروزرسانی زمان
            val timeFormat = SimpleDateFormat("HH:mm", Locale("fa", "IR"))
            views.setTextViewText(R.id.widgetClock, timeFormat.format(Date()))
            
            // بروزرسانی تاریخ
            val persianDate = PersianDateConverter.getCurrentPersianDate()
            val dayOfWeek = getDayOfWeekPersian()
            val dateText = "$dayOfWeek، ${persianDate.day} ${PersianDateConverter.getMonthName(persianDate.month)} ${persianDate.year}"
            views.setTextViewText(R.id.widgetPersianDate, dateText)
            
            // بروزرسانی آب و هوا
            updateWeatherAsync(context, views, appWidgetManager, appWidgetId)
            
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
    
    private fun updateSmallWidgets(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val widgetComponent = ComponentName(context, PersianCalendarWidgetSmall::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(widgetComponent)
        
        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_persian_calendar_small)
            
            // TextClock خودش آپدیت می‌شود، نیازی به تنظیم دستی نیست
            
            // بروزرسانی تاریخ (فقط روز و ماه)
            val persianDate = PersianDateConverter.getCurrentPersianDate()
            val shortDate = "${persianDate.day} ${PersianDateConverter.getMonthName(persianDate.month).substring(0, 3)}"
            views.setTextViewText(R.id.widgetPersianDateSmall, shortDate)
            
            // بروزرسانی آب و هوا
            updateWeatherSmall(context, views)
            
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
    
    private fun updateWeatherSmall(context: Context, views: RemoteViews) {
        val prefs = context.getSharedPreferences("weather_prefs", Context.MODE_PRIVATE)
        val city = prefs.getString("selected_city", "تهران") ?: "تهران"
        
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val weather = WorldWeatherAPI.getCurrentWeather(city)
                if (weather != null) {
                    val emoji = WorldWeatherAPI.getWeatherEmoji(weather.icon)
                    val text = "$emoji ${weather.temp.roundToInt()}°"
                    withContext(Dispatchers.Main) {
                        views.setTextViewText(R.id.widgetWeatherSmall, text)
                        val appWidgetManager = AppWidgetManager.getInstance(context)
                        val widgetComponent = ComponentName(context, PersianCalendarWidgetSmall::class.java)
                        val appWidgetIds = appWidgetManager.getAppWidgetIds(widgetComponent)
                        appWidgetIds.forEach { id ->
                            appWidgetManager.updateAppWidget(id, views)
                        }
                    }
                } else {
                    // استفاده از داده ذخیره شده
                    val prefs = context.getSharedPreferences("weather_prefs", Context.MODE_PRIVATE)
                    val savedTemp = prefs.getFloat("current_temp_$city", 25f)
                    val savedIcon = prefs.getString("weather_icon_$city", "113") ?: "113"
                    val emoji = WorldWeatherAPI.getWeatherEmoji(savedIcon)
                    val text = "$emoji ${savedTemp.roundToInt()}°"
                    withContext(Dispatchers.Main) {
                        views.setTextViewText(R.id.widgetWeatherSmall, text)
                        val appWidgetManager = AppWidgetManager.getInstance(context)
                        val widgetComponent = ComponentName(context, PersianCalendarWidgetSmall::class.java)
                        val appWidgetIds = appWidgetManager.getAppWidgetIds(widgetComponent)
                        appWidgetIds.forEach { id ->
                            appWidgetManager.updateAppWidget(id, views)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating small widget weather", e)
            }
        }
    }
    
    private fun updateLargeWidgets(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val widgetComponent = ComponentName(context, PersianCalendarWidgetLarge::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(widgetComponent)
        
        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_persian_calendar_large)
            
            // TextClock ها خودشان به صورت خودکار آپدیت می‌شوند
            // فقط نیاز به بروزرسانی تاریخ و آب و هوا داریم
            
            // بروزرسانی تاریخ کامل
            val persianDate = PersianDateConverter.getCurrentPersianDate()
            val dayOfWeek = getDayOfWeekPersian()
            val fullDate = "$dayOfWeek، ${persianDate.day} ${PersianDateConverter.getMonthName(persianDate.month)} ${persianDate.year}"
            views.setTextViewText(R.id.widgetPersianDateLarge, fullDate)
            
            // تاریخ میلادی
            val gregorianFormat = SimpleDateFormat("d MMMM yyyy", Locale.ENGLISH)
            views.setTextViewText(R.id.widgetGregorianDateLarge, gregorianFormat.format(Date()))
            
            // بروزرسانی آب و هوا
            updateWeatherAsync(context, views, appWidgetManager, appWidgetId)
            
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
    
    private fun updateWeatherAsync(
        context: Context, 
        views: RemoteViews, 
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        serviceScope.launch {
            try {
                val prefs = context.getSharedPreferences("weather_prefs", Context.MODE_PRIVATE)
                val city = prefs.getString("selected_city", "تهران") ?: "تهران"
                
                val weather = WorldWeatherAPI.getCurrentWeather(city)
                if (weather != null) {
                    val emoji = WorldWeatherAPI.getWeatherEmoji(weather.icon)
                    val text = "$emoji ${weather.temp.toInt()}° $city"
                    
                    withContext(Dispatchers.Main) {
                        // بروزرسانی بر اساس نوع ویجت
                        when {
                            views.getLayoutId() == R.layout.widget_persian_calendar -> {
                                views.setTextViewText(R.id.widgetWeather, text)
                            }
                            views.getLayoutId() == R.layout.widget_persian_calendar_small -> {
                                views.setTextViewText(R.id.widgetWeatherSmall, "$emoji ${weather.temp.toInt()}°")
                            }
                            views.getLayoutId() == R.layout.widget_persian_calendar_large -> {
                                views.setTextViewText(R.id.widgetWeatherLarge, text)
                                views.setTextViewText(R.id.widgetWeatherDescLarge, weather.description)
                            }
                        }
                        appWidgetManager.updateAppWidget(appWidgetId, views)
                    }
                }
            } catch (e: Exception) {
                Log.e("WidgetUpdateService", "Failed to update weather widget", e)
            }
        }
    }
    
    private fun getDayOfWeekPersian(): String {
        val calendar = Calendar.getInstance()
        return when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SATURDAY -> "شنبه"
            Calendar.SUNDAY -> "یکشنبه"
            Calendar.MONDAY -> "دوشنبه"
            Calendar.TUESDAY -> "سه‌شنبه"
            Calendar.WEDNESDAY -> "چهارشنبه"
            Calendar.THURSDAY -> "پنج‌شنبه"
            Calendar.FRIDAY -> "جمعه"
            else -> ""
        }
    }
    
    private fun getWeatherEmoji(temp: Double): String {
        return when {
            temp < 0 -> "❄️"
            temp < 10 -> "🌨️"
            temp < 20 -> "⛅"
            temp < 30 -> "☀️"
            else -> "🔥"
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        updateRunnable?.let { updateHandler.removeCallbacks(it) }
        serviceScope.cancel()
    }
}
