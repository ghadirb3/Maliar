package com.persianai.assistant.activities

import android.os.Bundle
import android.view.View
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.persianai.assistant.databinding.ActivityChatBinding
import com.persianai.assistant.models.MessageRole
import com.persianai.assistant.models.Reminder
import com.persianai.assistant.utils.PersianDate
import com.persianai.assistant.utils.SmartReminderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

class ReminderChatActivity : BaseChatActivity() {

    private lateinit var chatBinding: ActivityChatBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatBinding = ActivityChatBinding.inflate(layoutInflater)
        binding = chatBinding
        setContentView(chatBinding.root)

        setSupportActionBar(chatBinding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "چت با دستیار یادآوری"

        // Initialize UI components first, then setup chat
        setupChatUI()
    }

    override fun getRecyclerView(): androidx.recyclerview.widget.RecyclerView = chatBinding.messagesRecyclerView
    override fun getMessageInput(): com.google.android.material.textfield.TextInputEditText = chatBinding.messageInput
    override fun getSendButton(): View = chatBinding.sendButton
    override fun getVoiceButton(): View = chatBinding.voiceButton

    override fun getIntroMessage(): String {
        return "سلام! برای تنظیم یادآوری، فقط کافیه بگی. مثلا: «فردا ساعت ۱۰ صبح یادم بنداز جلسه دارم» یا می‌تونی هر چیزی که نیاز داری بپرسی."
    }

    override fun shouldUseOnlinePriority(): Boolean = true

    override fun getSystemPrompt(): String {
        return """
        شما یک دستیار هوشمند متخصص در زمینه مدیریت یادآوری‌ها هستید.
        وظیفه شما این است که درخواست‌های کاربر را تحلیل کرده و آن‌ها را به یک ساختار JSON مشخص برای افزودن یادآوری تبدیل کنید.
        قوانین:
        - همیشه یک آبجکت JSON با فیلد `action` برگردان.
        - اگر اطلاعات کافی نبود (مثلاً زمان یا متن یادآوری)، با یک سوال واضح از کاربر بپرس.
        اکشن‌های پشتیبانی‌شده:
        1) افزودن یادآوری:
           {"action":"add_reminder", "time":"HH:mm", "date":"YYYY/MM/DD", "message":"متن یادآوری", "repeat":"none|daily|weekly|monthly"}
        """
    }

    override suspend fun handleRequest(text: String): String {
        val offlineLocal = handleOfflineLocal(text)
        if (!offlineLocal.isNullOrBlank()) return offlineLocal

        val responseJson = super.handleRequest(text)
        return withContext(Dispatchers.Main) {
            try {
                // استخراج JSON از پاسخ (ممکن است بین ``` باشد)
                val jsonStr = extractJsonFromResponse(responseJson)
                val json = Gson().fromJson(jsonStr, JsonObject::class.java)
                
                if (json.has("action") && json.get("action").asString == "add_reminder") {
                    val message = json.get("message").asString
                    val time = if (json.has("time")) json.get("time").asString else "09:00"
                    val dateStr = if (json.has("date")) json.get("date").asString else ""
                    val repeatStr = if (json.has("repeat")) json.get("repeat").asString else "none"
                    
                    // تنظیم زمان
                    val calendar = Calendar.getInstance()
                    try {
                        val parts = time.split(":")
                        if (parts.size >= 2) {
                            calendar.set(Calendar.HOUR_OF_DAY, parts[0].toInt())
                            calendar.set(Calendar.MINUTE, parts[1].toInt())
                        }
                    } catch (e: Exception) {
                        calendar.set(Calendar.HOUR_OF_DAY, 9)
                        calendar.set(Calendar.MINUTE, 0)
                    }
                    calendar.set(Calendar.SECOND, 0)
                    
                    // اگر زمان گذشته، برای فردا تنظیم کن
                    if (calendar.timeInMillis < System.currentTimeMillis()) {
                        calendar.add(Calendar.DAY_OF_MONTH, 1)
                    }
                    
                    // تبدیل repeat
                    val repeatPattern = when (repeatStr.lowercase()) {
                        "daily" -> SmartReminderManager.RepeatPattern.DAILY
                        "weekly" -> SmartReminderManager.RepeatPattern.WEEKLY
                        "monthly" -> SmartReminderManager.RepeatPattern.MONTHLY
                        "yearly" -> SmartReminderManager.RepeatPattern.YEARLY
                        else -> SmartReminderManager.RepeatPattern.ONCE
                    }
                    
                    // ساخت یادآوری
                    val mgr = SmartReminderManager(this@ReminderChatActivity)
                    if (repeatPattern == SmartReminderManager.RepeatPattern.ONCE) {
                        val reminder = mgr.createSimpleReminder(
                            title = message,
                            description = "",
                            triggerTime = calendar.timeInMillis
                        )
                        android.util.Log.d("ReminderChatActivity", "✅ Created simple reminder: ${reminder.title} at ${reminder.triggerTime}")
                    } else {
                        val reminder = mgr.createRecurringReminder(
                            title = message,
                            description = "",
                            firstTriggerTime = calendar.timeInMillis,
                            repeatPattern = repeatPattern
                        )
                        android.util.Log.d("ReminderChatActivity", "✅ Created recurring reminder: ${reminder.title} at ${reminder.triggerTime}")
                    }
                    
                    "✅ یادآوری «$message» برای ساعت $time تنظیم شد."
                } else {
                    responseJson // Return the raw JSON if it's not an add_reminder action
                }
            } catch (e: Exception) {
                responseJson // If parsing fails, return the original response
            }
        }
    }

    private fun handleOfflineLocal(text: String): String? {
        val input = normalizeDigits(text).trim()
        if (input.isBlank()) return null

        val isReminder = input.contains("یادم بنداز") || input.contains("یادآوری") || input.contains("یادآور") ||
            input.contains("بیدارباش") || input.contains("آلارم") || input.contains("هشدار")
        if (!isReminder) return null

        val isDaily = input.contains("هر روز") || input.contains("روزانه")

        val calendar = Calendar.getInstance()
        val msg = extractReminderMessage(input) ?: return null

        // Date keywords
        when {
            input.contains("فردا") -> calendar.add(Calendar.DAY_OF_MONTH, 1)
            input.contains("پس فردا") -> calendar.add(Calendar.DAY_OF_MONTH, 2)
        }

        // Time extraction
        val time = extractTime(input)
        if (time != null) {
            calendar.set(Calendar.HOUR_OF_DAY, time.first)
            calendar.set(Calendar.MINUTE, time.second)
            calendar.set(Calendar.SECOND, 0)
        } else {
            // اگر زمان نگفت، یک زمان پیش‌فرض نزدیک بگذاریم
            calendar.add(Calendar.MINUTE, 5)
            calendar.set(Calendar.SECOND, 0)
        }

        // If time is in past, push to future (tomorrow)
        if (calendar.timeInMillis < System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_MONTH, 1)
        }

        return try {
            val mgr = SmartReminderManager(this)

            if (isDaily) {
                mgr.createRecurringReminder(
                    title = msg,
                    description = "",
                    firstTriggerTime = calendar.timeInMillis,
                    repeatPattern = SmartReminderManager.RepeatPattern.DAILY
                )
            } else {
                mgr.createSimpleReminder(
                    title = msg,
                    description = "",
                    triggerTime = calendar.timeInMillis
                )
            }

            val hh = calendar.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
            val mm = calendar.get(Calendar.MINUTE).toString().padStart(2, '0')
            if (isDaily) {
                "✅ یادآوری روزانه «$msg» از ساعت $hh:$mm تنظیم شد."
            } else {
                "✅ یادآوری «$msg» برای ساعت $hh:$mm تنظیم شد."
            }
        } catch (e: Exception) {
            "❌ خطا در ساخت یادآوری: ${e.message ?: "خطای نامشخص"}"
        }
    }

    private fun extractTime(text: String): Pair<Int, Int>? {
        // HH:MM
        Regex("(\\d{1,2})[:٫](\\d{1,2})").find(text)?.let { m ->
            val h = m.groupValues[1].toIntOrNull()
            val min = m.groupValues[2].toIntOrNull()
            if (h != null && min != null && h in 0..23 && min in 0..59) return h to min
        }

        // "ساعت 10" or "ساعت 10 و 30"
        Regex("ساعت\\s*(\\d{1,2})(?:\\s*(?:و|:|٫)\\s*(\\d{1,2}))?").find(text)?.let { m ->
            val h = m.groupValues[1].toIntOrNull()
            val min = m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
            if (h != null && h in 0..23 && min in 0..59) return h to min
        }

        return null
    }

    private fun extractReminderMessage(text: String): String? {
        var t = text
        t = t.replace("یادم بنداز", " ")
        t = t.replace("یادآوری", " ")
        t = t.replace("یادآور", " ")
        t = t.replace("بیدارباش", " ")
        t = t.replace("آلارم", " ")
        t = t.replace("هشدار", " ")
        t = t.replace("هر روز", " ")
        t = t.replace("روزانه", " ")
        t = t.replace("که", " ")
        t = t.replace("فردا", " ")
        t = t.replace("پس فردا", " ")
        t = t.replace(Regex("ساعت\\s*\\d{1,2}(?:\\s*(?:و|:|٫)\\s*\\d{1,2})?"), " ")
        t = t.replace(Regex("\\d{1,2}[:٫]\\d{1,2}"), " ")
        t = t.replace(Regex("\\s+"), " ").trim()
        return t.takeIf { it.isNotBlank() }
    }

    private fun normalizeDigits(input: String): String {
        val map = mapOf(
            '۰' to '0', '۱' to '1', '۲' to '2', '۳' to '3', '۴' to '4',
            '۵' to '5', '۶' to '6', '۷' to '7', '۸' to '8', '۹' to '9',
            '٠' to '0', '١' to '1', '٢' to '2', '٣' to '3', '٤' to '4',
            '٥' to '5', '٦' to '6', '٧' to '7', '٨' to '8', '٩' to '9'
        )
        val sb = StringBuilder(input.length)
        for (ch in input) sb.append(map[ch] ?: ch)
        return sb.toString()
    }
    
    private fun extractJsonFromResponse(response: String): String {
        // جستجو برای JSON بین { و }
        val startIdx = response.indexOf('{')
        val endIdx = response.lastIndexOf('}')
        
        return if (startIdx >= 0 && endIdx > startIdx) {
            response.substring(startIdx, endIdx + 1)
        } else {
            response
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
