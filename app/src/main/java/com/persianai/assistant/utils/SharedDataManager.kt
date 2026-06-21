package com.persianai.assistant.utils

import android.content.Context
import android.content.SharedPreferences
import com.persianai.assistant.activities.Reminder
import com.persianai.assistant.data.Transaction
import com.persianai.assistant.data.TransactionType
import org.json.JSONArray
import org.json.JSONObject

/**
 * مدیریت یکپارچه داده‌ها بین Dashboard و Assistant
 * برای یکسان‌سازی یادآوری‌ها، آب و هوا، حسابداری و تقویم
 */
object SharedDataManager {
    
    private const val PREFS_NAME = "shared_data"
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    // ==================== Weather Data ====================
    
    fun saveWeatherData(context: Context, city: String, temp: Float, description: String, icon: String) {
        getPrefs(context).edit().apply {
            putString("weather_city", city)
            putFloat("weather_temp", temp)
            putString("weather_desc", description)
            putString("weather_icon", icon)
            putLong("weather_timestamp", System.currentTimeMillis())
            apply()
        }
    }
    
    fun getWeatherCity(context: Context): String {
        return getPrefs(context).getString("weather_city", "تهران") ?: "تهران"
    }
    
    fun getWeatherTemp(context: Context): Float {
        return getPrefs(context).getFloat("weather_temp", 25f)
    }
    
    fun getWeatherDescription(context: Context): String {
        return getPrefs(context).getString("weather_desc", "آفتابی") ?: "آفتابی"
    }
    
    // ==================== Reminders Data ====================
    
    fun saveReminder(context: Context, time: String, message: String) {
        val reminders = getReminders(context).toMutableList()
        reminders.add(Reminder(time, message, false, System.currentTimeMillis()))
        saveReminders(context, reminders)
    }
    
    fun getReminders(context: Context): List<Reminder> {
        val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
        val count = prefs.getInt("count", 0)
        val reminders = mutableListOf<Reminder>()
        
        for (i in 0 until count) {
            val time = prefs.getString("time_$i", "") ?: ""
            val message = prefs.getString("message_$i", "") ?: ""
            val completed = prefs.getBoolean("completed_$i", false)
            val timestamp = prefs.getLong("timestamp_$i", 0)
            
            if (time.isNotEmpty() && message.isNotEmpty()) {
                reminders.add(Reminder(time, message, completed, timestamp))
            }
        }
        
        return reminders.sortedBy { it.timestamp }
    }
    
    private fun saveReminders(context: Context, reminders: List<Reminder>) {
        val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        
        editor.putInt("count", reminders.size)
        
        reminders.forEachIndexed { index, reminder ->
            editor.putString("time_$index", reminder.time)
            editor.putString("message_$index", reminder.message)
            editor.putBoolean("completed_$index", reminder.completed)
            editor.putLong("timestamp_$index", reminder.timestamp)
        }
        
        editor.apply()
    }
    
    fun getUpcomingReminders(context: Context, limit: Int = 3): List<Reminder> {
        return getReminders(context)
            .filter { !it.completed }
            .take(limit)
    }
    
    // ==================== Accounting Data ====================
    
    fun getTotalBalance(context: Context): Double {
        val prefs = getPrefs(context)
        return prefs.getFloat("total_balance", 0f).toDouble()
    }
    
    fun saveTotalBalance(context: Context, balance: Double) {
        getPrefs(context).edit().apply {
            putFloat("total_balance", balance.toFloat())
            apply()
        }
    }
    
    fun getMonthlyExpenses(context: Context): Double {
        val prefs = getPrefs(context)
        return prefs.getFloat("monthly_expenses", 0f).toDouble()
    }
    
    fun saveMonthlyExpenses(context: Context, expenses: Double) {
        getPrefs(context).edit().apply {
            putFloat("monthly_expenses", expenses.toFloat())
            apply()
        }
    }
    
    fun getMonthlyIncome(context: Context): Double {
        val prefs = getPrefs(context)
        return prefs.getFloat("monthly_income", 0f).toDouble()
    }
    
    fun saveMonthlyIncome(context: Context, income: Double) {
        getPrefs(context).edit().apply {
            putFloat("monthly_income", income.toFloat())
            apply()
        }
    }
    
    // ==================== Calendar Data ====================
    
    fun getSelectedDate(context: Context): Triple<Int, Int, Int> {
        val prefs = getPrefs(context)
        val year = prefs.getInt("calendar_year", 1403)
        val month = prefs.getInt("calendar_month", 1)
        val day = prefs.getInt("calendar_day", 1)
        return Triple(year, month, day)
    }
    
    fun saveSelectedDate(context: Context, year: Int, month: Int, day: Int) {
        getPrefs(context).edit().apply {
            putInt("calendar_year", year)
            putInt("calendar_month", month)
            putInt("calendar_day", day)
            apply()
        }
    }
    
    // ==================== Navigation Data ====================
    
    fun getLastLocation(context: Context): Pair<Double, Double>? {
        val prefs = getPrefs(context)
        val lat = prefs.getFloat("last_latitude", 0f)
        val lng = prefs.getFloat("last_longitude", 0f)
        
        return if (lat != 0f && lng != 0f) {
            Pair(lat.toDouble(), lng.toDouble())
        } else {
            null
        }
    }
    
    fun saveLastLocation(context: Context, latitude: Double, longitude: Double) {
        getPrefs(context).edit().apply {
            putFloat("last_latitude", latitude.toFloat())
            putFloat("last_longitude", longitude.toFloat())
            putLong("location_timestamp", System.currentTimeMillis())
            apply()
        }
    }
    
    // ==================== User Preferences ====================
    
    fun getUserName(context: Context): String {
        return getPrefs(context).getString("user_name", "کاربر") ?: "کاربر"
    }
    
    fun saveUserName(context: Context, name: String) {
        getPrefs(context).edit().apply {
            putString("user_name", name)
            apply()
        }
    }

    // ==================== Smart Reminder Preferences ====================

    fun getSmartReminderShowNotification(context: Context): Boolean {
        return getPrefs(context).getBoolean("smart_reminder_show_notification", false)
    }

    fun setSmartReminderShowNotification(context: Context, enabled: Boolean) {
        getPrefs(context).edit().apply {
            putBoolean("smart_reminder_show_notification", enabled)
            apply()
        }
    }

    fun isOnlineTtsEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean("use_online_tts", false)
    }

    fun setOnlineTtsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().apply {
            putBoolean("use_online_tts", enabled)
            apply()
        }
    }

    fun isReminderServiceForegroundEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean("reminder_service_foreground", false)
    }

    fun setReminderServiceForegroundEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().apply {
            putBoolean("reminder_service_foreground", enabled)
            apply()
        }
    }
    
    // ==================== Sync Status ====================
    
    fun getLastSyncTime(context: Context): Long {
        return getPrefs(context).getLong("last_sync_time", 0)
    }
    
    fun updateSyncTime(context: Context) {
        getPrefs(context).edit().apply {
            putLong("last_sync_time", System.currentTimeMillis())
            apply()
        }
    }
}
