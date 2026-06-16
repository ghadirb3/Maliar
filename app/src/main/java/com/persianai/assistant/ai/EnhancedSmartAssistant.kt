package com.persianai.assistant.ai

import android.content.Context
import android.util.Log
import com.persianai.assistant.config.FeatureFlags
import com.persianai.assistant.data.AccountingDB
import com.persianai.assistant.data.TransactionType
import com.persianai.assistant.finance.CheckManager
import com.persianai.assistant.finance.FinanceManager
import com.persianai.assistant.finance.InstallmentManager
import com.persianai.assistant.models.AIModel
import com.persianai.assistant.models.APIKey
import com.persianai.assistant.models.ChatMessage
import com.persianai.assistant.models.MessageRole
import com.persianai.assistant.utils.PreferencesManager
import com.persianai.assistant.utils.SmartReminderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class EnhancedSmartAssistant(private val context: Context) {

    private val TAG = "EnhancedSmartAssistant"
    private val prefsManager = PreferencesManager(context)
    private val featureFlags = FeatureFlags(context)
    private val reminderManager = SmartReminderManager(context)
    private val checkManager = CheckManager(context)
    private val installmentManager = InstallmentManager(context)
    private val financeManager = FinanceManager(context)
    private val accountingDB = AccountingDB(context)

    suspend fun processRequest(
        userMessage: String,
        contextHint: String? = null
    ): String = withContext(Dispatchers.IO) {
        
        val normalized = normalizeText(userMessage)
        
        val result = when {
            normalized.contains("یادآوری") || normalized.contains("یادم بنداز") || 
            normalized.contains("یاد بده") || normalized.contains("یادآور") -> {
                if (!featureFlags.isRemindersEnabled()) {
                    "⏰ بخش یادآوری‌ها در حال حاضر غیرفعال است."
                } else {
                    val intent = detectReminderIntent(normalized, userMessage)
                    processReminderIntent(intent)
                }
            }

            normalized.contains("گزارش") || normalized.contains("وضعیت مالی") ||
            normalized.contains("حساب") || normalized.contains("تراکنش") -> {
                if (!featureFlags.isFinanceEnabled()) {
                    "💰 بخش مالی در حال حاضر غیرفعال است."
                } else {
                    processFinanceIntent(normalized)
                }
            }

            (normalized.contains("هزینه") || normalized.contains("درآمد") || 
             normalized.contains("خرج") || normalized.contains("واریز")) && 
             Regex("\\d+").containsMatchIn(normalized) -> {
                if (!featureFlags.isFinanceEnabled()) {
                    "💰 بخش مالی در حال حاضر غیرفعال است."
                } else {
                    processTransactionAdd(normalized, userMessage)
                }
            }

            normalized.contains("چک") -> {
                if (!featureFlags.isFinanceEnabled()) {
                    "💰 بخش مالی در حال حاضر غیرفعال است."
                } else {
                    processCheckIntent(normalized)
                }
            }

            normalized.contains("قسط") -> {
                if (!featureFlags.isFinanceEnabled()) {
                    "💰 بخش مالی در حال حاضر غیرفعال است."
                } else {
                    processInstallmentIntent(normalized)
                }
            }

            else -> {
                processGeneralWithAI(userMessage, contextHint)
            }
        }

        result
    }

    data class ReminderIntent(
        var hour: Int? = null,
        var minute: Int? = null,
        var dayOffset: Int? = null,
        var relativeMillis: Long? = null,
        var message: String? = null,
        var repeatPattern: SmartReminderManager.RepeatPattern? = null
    )

    private fun detectReminderIntent(text: String, original: String): ReminderIntent {
        val intent = ReminderIntent()
        
        val timeRegex = """(\d{1,2}):(\d{2})""".toRegex()
        timeRegex.find(text)?.let {
            intent.hour = it.groupValues[1].toInt()
            intent.minute = it.groupValues[2].toInt()
        }

        if (intent.hour == null) {
            val fuzzyTime = """ساعت\s*(\d{1,2})\s*(?:و\s*(\d{1,2}))?\s*(صبح|ظهر|عصر|شب)?""".toRegex()
            fuzzyTime.find(text)?.let {
                val raw = it.groupValues[1].toIntOrNull() ?: 0
                val min = it.groupValues[2].toIntOrNull() ?: 0
                val period = it.groupValues.getOrNull(3) ?: ""
                intent.hour = when (period) {
                    "ظهر", "عصر", "شب" -> if (raw in 1..11) raw + 12 else raw
                    else -> raw
                }
                intent.minute = min
            }
        }

        val relative = """(\d+|نیم)\s*(دقیقه|ساعت)\s*(دیگه|بعد)""".toRegex()
        relative.find(text)?.let {
            val value = it.groupValues[1]
            val unit = it.groupValues[2]
            val amount = if (value == "نیم") 0.5 else value.toDoubleOrNull() ?: 0.0
            intent.relativeMillis = when (unit) {
                "دقیقه" -> (amount * 60 * 1000).toLong()
                "ساعت" -> (amount * 60 * 60 * 1000).toLong()
                else -> null
            }
        }

        when {
            text.contains("پس‌فردا") || text.contains("پس فردا") -> intent.dayOffset = 2
            text.contains("فردا") -> intent.dayOffset = 1
            text.contains("امروز") -> intent.dayOffset = 0
        }

        when {
            text.contains("هر روز") || text.contains("روزانه") -> intent.repeatPattern = SmartReminderManager.RepeatPattern.DAILY
            text.contains("هر هفته") || text.contains("هفتگی") -> intent.repeatPattern = SmartReminderManager.RepeatPattern.WEEKLY
            text.contains("هر ماه") || text.contains("ماهانه") -> intent.repeatPattern = SmartReminderManager.RepeatPattern.MONTHLY
        }

        val messageRegex = """(یادم بنداز|یاد بده|یادآوری کن|یادآوری)\s+(.+)""".toRegex()
        messageRegex.find(original)?.let {
            intent.message = it.groupValues[2].trim()
        }

        if (intent.message == null) {
            intent.message = original
                .replace(Regex("""\d{1,2}:\d{2}"""), "")
                .replace(Regex("""ساعت\s*\d{1,2}"""), "")
                .replace("یادم بنداز", "").replace("یاد بده", "")
                .replace("یادآوری کن", "").replace("یادآوری", "")
                .replace("فردا", "").replace("امروز", "").replace("پس فردا", "").replace("پس‌فردا", "")
                .replace("هر روز", "").replace("روزانه", "").replace("هر هفته", "").replace("هفتگی", "")
                .trim().ifBlank { "یادآوری جدید" }
        }

        return intent
    }

    @Suppress("UNUSED")
    private fun processReminderIntent(intent: ReminderIntent): String {
        val triggerTime: Long
        
        val relativeMs = intent.relativeMillis
        if (relativeMs != null) {
            triggerTime = System.currentTimeMillis() + relativeMs
        } else {
            val hour = intent.hour ?: return "⚠️ لطفاً ساعت یادآوری را مشخص کنید.\nمثال: «فردا ساعت ۹ یادم بنداز جلسه دارم»"
            val minute = intent.minute ?: 0
            
            val calendar = Calendar.getInstance().apply {
                val offset = intent.dayOffset
                if (offset != null) add(Calendar.DAY_OF_MONTH, offset)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                
                if (timeInMillis <= System.currentTimeMillis() && intent.dayOffset == null) {
                    add(Calendar.DAY_OF_MONTH, 1)
                }
            }
            triggerTime = calendar.timeInMillis
        }

        val message = intent.message ?: "یادآوری"

        val reminderTitle = if (FeatureFlags.FEATURE_REMINDER_AI_NATURAL_LANGUAGE) {
            generateNaturalReminderText(message)
        } else {
            message
        }

        when (intent.repeatPattern) {
            SmartReminderManager.RepeatPattern.DAILY,
            SmartReminderManager.RepeatPattern.WEEKLY,
            SmartReminderManager.RepeatPattern.MONTHLY -> {
                reminderManager.createRecurringReminder(
                    title = reminderTitle,
                    description = message,
                    firstTriggerTime = triggerTime,
                    repeatPattern = intent.repeatPattern ?: SmartReminderManager.RepeatPattern.ONCE
                )
            }
            else -> {
                reminderManager.createSimpleReminder(
                    title = reminderTitle,
                    description = message,
                    triggerTime = triggerTime
                )
            }
        }

        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(triggerTime))
        val dayStr = when (intent.dayOffset) {
            1 -> "فردا "
            2 -> "پس‌فردا "
            else -> ""
        }
        
        val repeatStr = when (intent.repeatPattern) {
            SmartReminderManager.RepeatPattern.DAILY -> "🔁 هر روز"
            SmartReminderManager.RepeatPattern.WEEKLY -> "🔁 هر هفته"
            SmartReminderManager.RepeatPattern.MONTHLY -> "🔁 هر ماه"
            else -> ""
        }

        return "✅ یادآوری هوشمند تنظیم شد:\n" +
               "⏰ ${dayStr}ساعت $timeStr\n" +
               "📝 $reminderTitle\n" +
               (if (repeatStr.isNotBlank()) "$repeatStr\n" else "") +
               (if (message != reminderTitle) "💡 $message" else "")
    }

    private fun generateNaturalReminderText(message: String): String {
        return try {
            val apiKeys = prefsManager.getAPIKeys().filter { it.isActive }
            if (apiKeys.isNotEmpty()) {
                val aiClient = AIClient(context, apiKeys)
                runBlockingOnIO {
                    val prompt = """
                        کاربر یک یادآوری ثبت کرده: "$message"
                        
                        لطفاً همین جمله را به صورت طبیعی‌تر و دوستانه‌تر بازنویسی کن، طوری که انگار یک دوست به کاربر یادآوری می‌کند.
                        فقط یک جمله کوتاه و مستقیم بگو.
                        مثال:
                        - «قراره یه جلسه مهم داشته باشی، یادت نره!»
                        - «زمان خرید سوپرمارکته، لیستت رو آماده کن.»
                        - «فراموش نکن که باید قرص‌هات رو بخوری.»
                    """.trimIndent()
                    
                    val response = aiClient.sendMessage(
                        model = AIModel.GPT_4O_MINI,
                        messages = listOf(ChatMessage(role = MessageRole.USER, content = prompt)),
                        systemPrompt = "تو یک دستیار صمیمی و مهربان هستی که یادآوری‌ها را به صورت طبیعی و دوستانه بیان می‌کنی."
                    )
                    
                    val content = response.content.trim()
                    if (content.length in 10..150) {
                        return@runBlockingOnIO content
                    }
                    message
                }
            } else {
                message
            }
        } catch (e: Exception) {
            Log.w(TAG, "AI natural reminder failed, using original", e)
            message
        }
    }

    private fun processFinanceIntent(text: String): String {
        return when {
            text.contains("امروز") || text.contains("روز") -> financeManager.generateReport("day")
            text.contains("هفته") -> financeManager.generateReport("week")
            text.contains("ماه") || text.contains("ماهیانه") || text.contains("تراز") -> {
                val calendar = Calendar.getInstance()
                val year = calendar.get(Calendar.YEAR)
                val month = calendar.get(Calendar.MONTH) + 1
                
                if (text.contains("تراز")) {
                    financeManager.getMonthlyBalanceReport(year, month)
                } else {
                    val (income, expense, count) = financeManager.getMonthlyReport(year, month)
                    
                    val balance = income - expense
                    val checksTotal = checkManager.getTotalPendingAmount()
                    val installmentsTotal = installmentManager.getTotalRemainingAmount()
                    
                    buildString {
                        appendLine("💰 گزارش مالی این ماه:\n")
                        appendLine("📈 درآمد: ${formatAmount(income)} تومان")
                        appendLine("📉 هزینه: ${formatAmount(expense)} تومان")
                        appendLine("💵 خالص: ${formatAmount(balance)} تومان")
                        appendLine("📝 تراکنش‌ها: $count مورد")
                        
                        if (checksTotal > 0 || installmentsTotal > 0) {
                            appendLine("\n📋 تعهدات:")
                            if (checksTotal > 0) appendLine("• چک‌ها: ${formatAmount(checksTotal)} تومان")
                            if (installmentsTotal > 0) appendLine("• اقساط: ${formatAmount(installmentsTotal)} تومان")
                        }
                        
                        val netWorth = balance - checksTotal - installmentsTotal
                        appendLine("\n💎 دارایی خالص: ${formatAmount(netWorth)} تومان")
                        
                        if (netWorth < 0) {
                            appendLine("\n⚠️ شما ${formatAmount(-netWorth)} تومان بدهی دارید.")
                        } else {
                            appendLine("\n✅ وضعیت مالی شما مناسب است.")
                        }
                    }
                }
            }
            text.contains("سال") -> {
                if (text.contains("تراز")) {
                    val calendar = Calendar.getInstance()
                    financeManager.getYearlyBalanceReport(calendar.get(Calendar.YEAR))
                } else {
                    financeManager.generateReport("year")
                }
            }
            else -> financeManager.generateReport("month")
        }
    }

    private fun processTransactionAdd(text: String, original: String): String {
        val numbers = Regex("\\d+(?:\\.\\d+)?").findAll(text).map { it.value.toDoubleOrNull() ?: 0.0 }.toList()
        if (numbers.isEmpty()) return "⚠️ مبلغ مشخص نشده است."
        
        val rawAmount = numbers.first()
        val unitMatch = Regex("(میلیون|هزار|ریال)").find(text)
        val amount = when {
            unitMatch?.value?.contains("میلیون") == true -> rawAmount * 1_000_000
            unitMatch?.value?.contains("هزار") == true -> rawAmount * 1_000
            unitMatch?.value?.contains("ریال") == true -> rawAmount / 10
            else -> rawAmount
        }

        val isIncome = text.contains("درآمد") || text.contains("دریافت") || text.contains("واریز") || text.contains("سود")
        val type = if (isIncome) "income" else "expense"
        val category = if (isIncome) "درآمد" else "هزینه"
        
        val description = original
            .replace(Regex("\\d+(?:\\.\\d+)?"), "")
            .replace(Regex("(میلیون|هزار|ریال)"), "")
            .replace("هزینه", "").replace("درآمد", "")
            .replace("واریز", "").replace("دریافت", "")
            .trim()

        financeManager.addTransaction(amount, type, category, description)
        val typeText = if (isIncome) "درآمد" else "هزینه"
        
        return "✅ $typeText ${formatAmount(amount)} تومان ثبت شد\n📝 ${description.ifBlank { "بدون توضیحات" }}"
    }

    private fun processCheckIntent(text: String): String {
        val checks = checkManager.getAllChecks()
        if (checks.isEmpty()) return "📋 شما هیچ چکی ثبت نکرده‌اید."
        
        val pending = checks.filter { it.status == CheckManager.CheckStatus.PENDING }
        val upcoming = checkManager.getUpcomingChecks(7)
        
        return buildString {
            appendLine("📋 وضعیت چک‌ها:\n")
            appendLine("💰 چک‌های در انتظار: ${pending.size} عدد")
            appendLine("💵 مبلغ کل: ${formatAmount(checkManager.getTotalPendingAmount())} تومان")
            
            if (upcoming.isNotEmpty()) {
                appendLine("\n⏰ سررسیدهای 7 روز آینده:")
                upcoming.take(3).forEach { check ->
                    val days = ((check.dueDate - System.currentTimeMillis()) / (24 * 60 * 60 * 1000)).toInt()
                    appendLine("• چک ${check.checkNumber}: $days روز دیگر (${formatAmount(check.amount)} تومان)")
                }
            }
        }
    }

    private fun processInstallmentIntent(text: String): String {
        val installments = installmentManager.getActiveInstallments()
        if (installments.isEmpty()) return "💳 شما هیچ قسط فعالی ندارید."
        
        val totalRemaining = installmentManager.getTotalRemainingAmount()
        val upcoming = installmentManager.getUpcomingPayments(7)
        
        return buildString {
            appendLine("💳 وضعیت اقساط:\n")
            appendLine("📊 اقساط فعال: ${installments.size} مورد")
            appendLine("💰 باقیمانده: ${formatAmount(totalRemaining)} تومان")
            
            if (upcoming.isNotEmpty()) {
                appendLine("\n⏰ پرداخت‌های 7 روز آینده:")
                upcoming.take(3).forEach { (inst, dueDate) ->
                    val days = ((dueDate - System.currentTimeMillis()) / (24 * 60 * 60 * 1000)).toInt()
                    appendLine("• ${inst.title}: $days روز دیگر (${formatAmount(inst.installmentAmount)} تومان)")
                }
            }
        }
    }

    private fun processGeneralWithAI(userMessage: String, contextHint: String?): String {
        if (!featureFlags.isAIChatEnabled()) {
            return "💬 بخش چت هوشمند در حال حاضر غیرفعال است."
        }

        return try {
            val apiKeys = prefsManager.getAPIKeys().filter { it.isActive }
            if (apiKeys.isEmpty()) {
                return "❌ برای استفاده از دستیار هوشمند، لطفاً ابتدا کلید API را در تنظیمات وارد کنید."
            }

            val aiClient = AIClient(context, apiKeys)
            val systemPrompt = buildString {
                appendLine("تو یک دستیار هوشمند فارسی به نام «مالیار» هستی.")
                appendLine("تو می‌توانی به کاربر در مدیریت امور مالی، یادآوری‌ها، برنامه‌ریزی و سوالات عمومی کمک کنی.")
                appendLine("پاسخ‌هایت باید مفید، دقیق و نسبتاً کوتاه باشد.")
                if (!contextHint.isNullOrBlank()) {
                    appendLine("زمینه: $contextHint")
                }
                appendLine()
                appendLine("اگر کاربر درباره وضعیت مالی یا یادآوری‌ها سوال کرد، به او بگو از عباراتی مثل «گزارش مالی» یا «یادآوری‌های من» استفاده کند.")
            }

            runBlockingOnIO {
                val response = aiClient.sendMessage(
                    model = AIModel.GPT_4O_MINI,
                    messages = listOf(ChatMessage(role = MessageRole.USER, content = userMessage)),
                    systemPrompt = systemPrompt
                )
                response.content
            }
        } catch (e: Exception) {
            Log.e(TAG, "AI request failed", e)
            "❌ خطا در ارتباط با سرور: ${e.localizedMessage ?: "خطای ناشناخته"}"
        }
    }

    suspend fun getDailySmartSummary(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance()
        
        sb.appendLine("☀️ خلاصه هوشمند امروز")
        sb.appendLine("${calendar.get(Calendar.YEAR)}/${calendar.get(Calendar.MONTH) + 1}/${calendar.get(Calendar.DAY_OF_MONTH)}")
        sb.appendLine()
        
        if (featureFlags.isRemindersEnabled()) {
            val todayReminders = reminderManager.getTodayReminders()
            if (todayReminders.isNotEmpty()) {
                sb.appendLine("⏰ یادآوری‌های امروز (${todayReminders.size} مورد):")
                todayReminders.take(5).forEach { reminder ->
                    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(reminder.triggerTime))
                    sb.appendLine("  • $time - ${reminder.title}")
                }
                sb.appendLine()
            }
        }
        
        if (featureFlags.isFinanceEnabled()) {
            val monthlyReport = financeManager.getMonthlyReport(
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1
            )
            val income = monthlyReport.first
            val expense = monthlyReport.second
            sb.appendLine("💰 وضعیت مالی این ماه:")
            sb.appendLine("  درآمد: ${formatAmount(income)} تومان")
            sb.appendLine("  هزینه: ${formatAmount(expense)} تومان")
            
            val upcomingChecks = checkManager.getUpcomingChecks(7)
            val upcomingInstallments = installmentManager.getUpcomingPayments(7)
            
            if (upcomingChecks.isNotEmpty() || upcomingInstallments.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("⚠️ تعهدات 7 روز آینده:")
                upcomingChecks.take(3).forEach { check ->
                    val days = ((check.dueDate - now) / (24 * 60 * 60 * 1000)).toInt()
                    sb.appendLine("  • چک ${check.checkNumber}: $days روز دیگر")
                }
                upcomingInstallments.take(3).forEach { (inst, dueDate) ->
                    val days = ((dueDate - now) / (24 * 60 * 60 * 1000)).toInt()
                    sb.appendLine("  • قسط ${inst.title}: $days روز دیگر")
                }
            }
        }
        
        sb.toString()
    }

    private fun normalizeText(text: String): String {
        val map = mapOf(
            '۰' to '0', '۱' to '1', '۲' to '2', '۳' to '3', '۴' to '4',
            '۵' to '5', '۶' to '6', '۷' to '7', '۸' to '8', '۹' to '9'
        )
        return text.map { map[it] ?: it }.joinToString("").trim().lowercase()
    }

    private fun formatAmount(amount: Double): String = String.format("%,.0f", amount)

    /**
     * Helper to run suspend function from non-suspend context inside IO dispatcher
     */
    private fun <T> runBlockingOnIO(block: suspend () -> T): T {
        return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            block()
        }
    }
}