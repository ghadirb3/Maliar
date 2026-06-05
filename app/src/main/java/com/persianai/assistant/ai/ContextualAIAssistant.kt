package com.persianai.assistant.ai

import android.content.Context
import android.util.Log
import com.persianai.assistant.data.AccountingDB
import com.persianai.assistant.utils.FormatUtils
import com.persianai.assistant.data.Transaction
import com.persianai.assistant.data.TransactionType
import com.persianai.assistant.api.AIModelManager
import com.persianai.assistant.utils.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.*

/**
 * دستیار هوشمند که با مدل AI کار می‌کند
 */
class ContextualAIAssistant(private val context: Context) {
    
    private val TAG = "ContextualAIAssistant"
    private val nlp = PersianNLP()
    private val aiModelManager = AIModelManager(context)
    private val prefsManager = PreferencesManager(context)
    
    suspend fun processAccountingCommand(userMessage: String, db: AccountingDB): AIResponse = withContext(Dispatchers.IO) {
        val workingMode = prefsManager.getWorkingMode()
        val canUseOnline = (workingMode == PreferencesManager.WorkingMode.ONLINE ||
                workingMode == PreferencesManager.WorkingMode.HYBRID) && aiModelManager.hasApiKey()

        // اول در صورت مجاز بودن، سعی می‌کنیم با مدل آنلاین پردازش کنیم
        if (canUseOnline) {
            try {
                val prompt = """تو یک دستیار مالی هستی. کاربر می‌گوید: "$userMessage"
اگر درخواست ثبت هزینه یا درآمد است، به فرمت JSON پاسخ بده:
{"type": "expense/income", "amount": مبلغ, "description": "توضیحات"}
اگر سوال است، مستقیم پاسخ بده."""
                
                val response = aiModelManager.generateText(prompt)
                if (response.contains("{") && response.contains("}")) {
                    val json = JSONObject(response.substring(response.indexOf("{"), response.lastIndexOf("}") + 1))
                    val type = json.optString("type")
                    val amount = json.optDouble("amount", 0.0)
                    val desc = json.optString("description", "")
                    
                    if (amount > 0) {
                        val transType = if (type == "income") TransactionType.INCOME else TransactionType.EXPENSE
                        val t = Transaction(0, transType, amount, "", desc, System.currentTimeMillis())
                        db.addTransaction(t)
                        return@withContext AIResponse(true, "✅ ${if (type == "income") "درآمد" else "هزینه"} ${formatMoney(amount)} تومان ثبت شد", "add_transaction")
                    }
                }
                return@withContext AIResponse(true, response, "chat")
            } catch (e: Exception) {
                Log.e(TAG, "Error using AI model", e)
                // در حالت HYBRID اگر خطا رخ دهد، به حالت آفلاین برمی‌گردیم
            }
        } else if (workingMode == PreferencesManager.WorkingMode.ONLINE && !aiModelManager.hasApiKey()) {
            // حالت فقط آنلاین ولی بدون کلید API
            return@withContext AIResponse(
                success = false,
                message = "برای استفاده از دستیار مالی آنلاین، ابتدا کلید API را در تنظیمات وارد کنید.",
                action = "error"
            )
        }

        // اگر مدل آنلاین مجاز نبود یا خطا داد، از NLP ساده آفلاین استفاده می‌کنیم
        val cmd = nlp.parse(userMessage)
        return@withContext when(cmd.type) {
            PersianNLP.Type.EXPENSE -> {
                if (cmd.amount != null && cmd.amount > 0) {
                    val t = Transaction(0, TransactionType.EXPENSE, cmd.amount, "", cmd.text ?: "", System.currentTimeMillis())
                    db.addTransaction(t)
                    AIResponse(true, "✅ هزینه ${formatMoney(cmd.amount)} تومان ثبت شد", "add_transaction")
                } else {
                    AIResponse(false, "مبلغ نامعتبر است", "error")
                }
            }
            PersianNLP.Type.INCOME -> {
                if (cmd.amount != null && cmd.amount > 0) {
                    val t = Transaction(0, TransactionType.INCOME, cmd.amount, "", cmd.text ?: "", System.currentTimeMillis())
                    db.addTransaction(t)
                    AIResponse(true, "✅ درآمد ${formatMoney(cmd.amount)} تومان ثبت شد", "add_transaction")
                } else {
                    AIResponse(false, "مبلغ نامعتبر است", "error")
                }
            }
            else -> extractAccountingCommandManually(userMessage, db)
        }
    }
    
    suspend fun processReminderCommand(userMessage: String): AIResponse = withContext(Dispatchers.IO) {
        val cmd = nlp.parse(userMessage)
        return@withContext when (cmd.type) {
            PersianNLP.Type.REMINDER -> {
                if (cmd.time != null) {
                    val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
                    val editor = prefs.edit()
                    val count = prefs.getInt("count", 0)

                    val calendar = Calendar.getInstance().apply {
                        timeInMillis = cmd.time
                    }
                    val hour = calendar.get(Calendar.HOUR_OF_DAY)
                    val minute = calendar.get(Calendar.MINUTE)
                    val timeStr = String.format("%02d:%02d", hour, minute)

                    editor.putString("time_$count", timeStr)
                    editor.putString("message_$count", cmd.text ?: "یادآوری")
                    editor.putBoolean("completed_$count", false)
                    editor.putLong("timestamp_$count", calendar.timeInMillis)
                    editor.putInt("count", count + 1)
                    editor.apply()

                    AIResponse(true, "✅ یادآوری ساعت $timeStr ثبت شد", "add_reminder")
                } else {
                    AIResponse(false, "زمان نامعتبر است", "error")
                }
            }
            else -> extractReminderCommandManually(userMessage)
        }
    }
    
    suspend fun processMusicCommand(userMessage: String): AIResponse = withContext(Dispatchers.IO) {
        return@withContext extractMusicCommandManually(userMessage)
    }
    
    suspend fun processNavigationCommand(userMessage: String): AIResponse = withContext(Dispatchers.IO) {
        return@withContext extractNavigationCommandManually(userMessage)
    }
    
    private fun extractAccountingCommandManually(message: String, db: AccountingDB): AIResponse {
        val lowerMessage = message.lowercase()
        
        // استخراج اعداد
        val numbers = Regex("\\d+(?:\\.\\d+)?").findAll(message).map { it.value.toDoubleOrNull() ?: 0.0 }.toList()
        
        if (numbers.isNotEmpty()) {
            val rawAmount = numbers.first()
            val unitMatch = Regex("(میلیون|هزار|ریال)").find(lowerMessage)
            val amount = when {
                unitMatch?.value?.contains("میلیون") == true -> rawAmount * 1_000_000
                unitMatch?.value?.contains("هزار") == true -> rawAmount * 1_000
                unitMatch?.value?.contains("ریال") == true -> rawAmount / 10
                else -> rawAmount
            }
            val isIncome = lowerMessage.contains("درآمد") || lowerMessage.contains("دریافت") || lowerMessage.contains("سود")
            
            val type = if (isIncome) TransactionType.INCOME else TransactionType.EXPENSE
            val description = message.replace(Regex("\\d+(?:\\.\\d+)?"), "").trim()
            
            val transaction = Transaction(0, type, amount, "", description, System.currentTimeMillis())
            db.addTransaction(transaction)
            
            val typeText = if (isIncome) "درآمد" else "هزینه"
            return AIResponse(true, "✅ $typeText ${formatMoney(amount)} تومان ثبت شد", "add_transaction")
        }
        
        return AIResponse(false, "متاسفانه متوجه منظور شما نشدم. لطفا مبلغ را مشخص کنید.", "error")
    }
    
    private fun extractReminderCommandManually(message: String): AIResponse {
        return AIResponse(false, "برای ثبت یادآوری، لطفا زمان و موضوع را مشخص کنید.", "error")
    }
    
    private fun extractMusicCommandManually(message: String): AIResponse {
        return AIResponse(false, "برای کنترل موسیقی، از دستورات پخش، توقف، یا بعدی استفاده کنید.", "error")
    }
    
    private fun extractNavigationCommandManually(message: String): AIResponse {
        return AIResponse(false, "برای مسیریابی، لطفا مبدأ و مقصد را مشخص کنید.", "error")
    }
    
    private fun formatMoney(amount: Double): String = FormatUtils.formatMoney(amount)
}

/**
 * کلاس پاسخ هوش مصنوعی
 */
data class AIResponse(
    val success: Boolean,
    val message: String,
    val action: String
)

