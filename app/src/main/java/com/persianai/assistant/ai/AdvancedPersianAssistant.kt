package com.persianai.assistant.ai

import android.content.Context
import android.util.Log
import com.persianai.assistant.models.AIModel
import com.persianai.assistant.models.AIProvider
import com.persianai.assistant.models.APIKey
import com.persianai.assistant.models.ChatMessage
import com.persianai.assistant.models.MessageRole
import com.persianai.assistant.finance.CheckManager
import com.persianai.assistant.finance.FinanceManager
import com.persianai.assistant.finance.InstallmentManager
import com.persianai.assistant.utils.PreferencesManager
import com.persianai.assistant.utils.SmartReminderManager
import com.persianai.assistant.utils.TravelPlannerManager
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * دستیار مکالمه‌ای پیشرفته فارسی با پشتیبانی از:
 * - مدیریت مالی (چک، قسط، هزینه)
 * - یادآوری‌های هوشمند
 * - دستورات چندمرحله‌ای
 * - هشدارهای زمینه‌محور
 */
class AdvancedPersianAssistant(private val context: Context) {
    
    private val TAG = "AdvancedPersianAssistant"
    private val checkManager = CheckManager(context)
    private val installmentManager = InstallmentManager(context)
    private val financeManager = FinanceManager(context)
    private val prefsManager = PreferencesManager(context)
    private val reminderManager = SmartReminderManager(context)
    private val travelManager = TravelPlannerManager(context)
    
    /**
     * پردازش درخواست کاربر با NLP ساده فارسی
     */
    fun processRequest(userInput: String): AssistantResponse {
        val normalized = normalizeText(userInput)
        val intent = detectIntent(normalized)
        
        return when (intent.type) {
            IntentType.CHECK_INQUIRY -> handleCheckInquiry(intent)
            IntentType.CHECK_ADD -> handleCheckAdd(intent)
            IntentType.INSTALLMENT_INQUIRY -> handleInstallmentInquiry(intent)
            IntentType.INSTALLMENT_ADD -> handleInstallmentAdd(intent)
            IntentType.INSTALLMENT_PAY -> handleInstallmentPay(intent)
            IntentType.FINANCE_REPORT -> handleFinanceReport(intent)
            IntentType.FINANCE_ADD -> handleFinanceAdd(intent)
            IntentType.REMINDER_ADD -> handleReminderAdd(intent)
            IntentType.REMINDER_LIST -> handleReminderList(intent)
            IntentType.TRAVEL_PLAN -> handleTravelPlan(intent)
            IntentType.TRAVEL_ALERT -> handleTravelAlert(intent)
            IntentType.FAMILY_EVENT -> handleFamilyEvent(intent)
            IntentType.BANKING_ALERT -> handleBankingAlert(intent)
            IntentType.GENERAL_QUESTION -> handleGeneralQuestion(intent)
            IntentType.UNKNOWN -> AssistantResponse(
                text = "متوجه منظور شما نشدم. لطفاً واضح‌تر توضیح دهید یا از این دستورات استفاده کنید:\n\n" +
                       "💰 مالی: «چک‌های من»، «اقساط این ماه»، «گزارش مالی»\n" +
                       "⏰ یادآوری: «فردا ساعت 9 یادم بنداز...»\n" +
                       "❓ سوال: «تفاوت چک و سفته چیست؟»"
            )
        }
    }

    /**
     * دریافت لیست مدل‌ها برای fallback با اولویت‌بندی درخواست کاربر
     */
    private fun getFallbackModels(apiKeys: List<APIKey>): List<AIModel> {
        val activeProviders = apiKeys.filter { it.isActive }.map { it.provider }.toSet()
        val models = mutableListOf<AIModel>()
        
        // اولویت ۱: gpt-5-nano از لیارا (درخواست اصلی کاربر - مدل معتبر از مستندات)
        if (activeProviders.contains(AIProvider.LIARA)) {
            models.add(AIModel.LIARA_GPT_5_NANO)
        }
        
        // اولویت ۲: gpt-4o-mini از لیارا (fallback معتبر)
        if (activeProviders.contains(AIProvider.LIARA)) {
            models.add(AIModel.LIARA_GPT_4O_MINI)
        }
        
        // اولویت ۳: gpt-5-nano از GAPGPT (درخواست کاربر)
        if (activeProviders.contains(AIProvider.GAPGPT)) {
            models.add(AIModel.GAPGPT_GPT_5_NANO)
        }
        
        // اولویت ۴: gpt-4o-mini از GAPGPT (fallback معتبر)
        if (activeProviders.contains(AIProvider.GAPGPT)) {
            models.add(AIModel.GAPGPT_GPT_4O_MINI)
        }
        
        // اولویت ۵: deepseek از GAPGPT (درخواست کاربر)
        if (activeProviders.contains(AIProvider.GAPGPT)) {
            models.add(AIModel.GAPGPT_DEEPSEEK_V3)
        }
        
        // اولویت ۶: مدل اصلی OpenAI (backup)
        if (activeProviders.contains(AIProvider.OPENAI)) {
            models.add(AIModel.GPT_4O_MINI)
        }
        
        return models
    }

    suspend fun processRequestWithAI(userInput: String, contextHint: String? = null): AssistantResponse {
        val baseResponse = processRequest(userInput)

        val workingMode = prefsManager.getWorkingMode()
        val apiKeys = prefsManager.getAPIKeys()
        
        // Check for any active API keys (not just OpenAI)
        val hasActiveKeys = apiKeys.any { it.isActive }

        val canUseOnline = (workingMode == PreferencesManager.WorkingMode.ONLINE ||
                workingMode == PreferencesManager.WorkingMode.HYBRID) && hasActiveKeys

        if (!canUseOnline) {
            if (workingMode == PreferencesManager.WorkingMode.ONLINE && !hasActiveKeys) {
                return AssistantResponse(
                    text = "برای استفاده از مدل آنلاین، ابتدا کلیدهای API را در تنظیمات وارد کنید."
                )
            }
            android.util.Log.d("AdvancedPersianAssistant", "Using offline mode - workingMode=$workingMode, hasActiveKeys=$hasActiveKeys")
            return baseResponse
        }

        return try {
            val aiClient = AIClient(context, apiKeys)
            // Use the best available model instead of hardcoded GPT-4O-MINI
            val model = chooseBestModel(apiKeys, prefsManager.getProviderPreference())

            android.util.Log.d("AdvancedPersianAssistant", "Using online model: ${model.modelId} (${model.provider})")

            suspend fun callOnline(prompt: String): String {
                val availableModels = getFallbackModels(apiKeys)
                
                for (currentModel in availableModels) {
                    try {
                        android.util.Log.d("AdvancedPersianAssistant", "Trying model: ${currentModel.modelId} (${currentModel.provider})")
                        
                        val resp = aiClient.sendMessage(
                            model = currentModel,
                            messages = listOf(ChatMessage(role = MessageRole.USER, content = prompt))
                        )
                        val content = resp.content.trim()
                        
                        // Check if response is meaningful
                        if (content.isNotBlank() && 
                            !content.equals("none", ignoreCase = true) && 
                            !content.equals("cancel", ignoreCase = true) &&
                            content.length > 3) {
                            
                            android.util.Log.d("AdvancedPersianAssistant", "Success with model ${currentModel.modelId}: ${content.take(50)}...")
                            return content
                        } else {
                            android.util.Log.w("AdvancedPersianAssistant", "Model ${currentModel.modelId} returned empty/invalid response: '$content'")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("AdvancedPersianAssistant", "Model ${currentModel.modelId} failed: ${e.message}")
                    }
                }
                
                android.util.Log.w("AdvancedPersianAssistant", "All models failed, returning empty response")
                return ""
            }

            if (baseResponse.actionType == ActionType.NEEDS_AI) {
                val contextLine = contextHint?.takeIf { it.isNotBlank() }?.let { "زمینه/بخش: $it.\n" } ?: ""
                val prompt = """
                    تو یک دستیار هوشمند فارسی هستی.
                    $contextLine
                    به سوال/درخواست کاربر پاسخ کامل، دقیق و کوتاه بده.
                    اگر کاربر درخواست پیشنهاد فیلم دارد، چند پیشنهاد مناسب با توضیح یک‌خطی بده.
                    اگر اطلاعات کافی نیست، فقط یک سوال کوتاه برای روشن شدن بپرس.

                    درخواست کاربر:
                    "$userInput"
                """.trimIndent()

                val aiText = callOnline(prompt)
                if (aiText.isNotBlank()) return AssistantResponse(text = aiText)
                return baseResponse
            }

            val contextLine = contextHint?.takeIf { it.isNotBlank() }?.let { "زمینه گفتگو: $it.\n" } ?: ""
            val baseSummary = baseResponse.text.take(400)

            val prompt = """
                تو یک دستیار هوشمند فارسی هستی.
                $contextLine
                کاربر می‌گوید:
                "$userInput"

                پاسخ پیشنهادی داخلی برنامه:
                "$baseSummary"

                همین پاسخ را با لحن مودب، واضح و نسبتاً کوتاه فقط به زبان فارسی بازنویسی کن.
                اطلاعات و نتیجه را عوض نکن، فقط بیان را بهتر کن.
            """.trimIndent()

            val aiText = callOnline(prompt)
            if (aiText.isNotBlank()) {
                baseResponse.copy(text = aiText)
            } else {
                baseResponse
            }
        } catch (e: Exception) {
            android.util.Log.e("AdvancedPersianAssistant", "Online AI request failed", e)
            baseResponse
        }
    }
    
    private fun chooseBestModel(apiKeys: List<APIKey>, providerPreference: PreferencesManager.ProviderPreference): AIModel {
        val activeKeys = apiKeys.filter { it.isActive }
        
        // Priority based on provider preference and availability
        val preferredProvider: AIProvider? = when (providerPreference) {
            PreferencesManager.ProviderPreference.OPENAI_ONLY ->
                activeKeys.firstOrNull { it.provider == AIProvider.OPENAI }?.provider
            PreferencesManager.ProviderPreference.SMART_ROUTE ->
                activeKeys.firstOrNull { it.provider == AIProvider.OPENAI }?.provider
                    ?: activeKeys.firstOrNull { it.provider == AIProvider.LIARA }?.provider
                    ?: activeKeys.firstOrNull()?.provider
            PreferencesManager.ProviderPreference.AUTO ->
                activeKeys.firstOrNull { it.provider == AIProvider.LIARA }?.provider
                    ?: activeKeys.firstOrNull { it.provider == AIProvider.OPENAI }?.provider
                    ?: activeKeys.firstOrNull()?.provider
        }
        
        // Choose best model based on available providers
        val finalProvider = preferredProvider ?: activeKeys.firstOrNull()?.provider
        return when (finalProvider) {
            AIProvider.LIARA -> {
                // Use LIARA_GPT_5_NANO to match BaseChatActivity priority
                AIModel.LIARA_GPT_5_NANO
            }
            AIProvider.OPENAI -> AIModel.GPT_4O_MINI
            AIProvider.GAPGPT -> AIModel.GAPGPT_GPT_5_NANO  // Use GPT-5-nano as priority for GAPGPT
            else -> AIModel.GPT_4O_MINI
        }
    }
    
    private fun normalizeText(text: String): String {
        // نرمال‌سازی متن فارسی
        val map = mapOf(
            '۰' to '0', '۱' to '1', '۲' to '2', '۳' to '3', '۴' to '4',
            '۵' to '5', '۶' to '6', '۷' to '7', '۸' to '8', '۹' to '9',
            '٠' to '0', '١' to '1', '٢' to '2', '٣' to '3', '٤' to '4',
            '٥' to '5', '٦' to '6', '٧' to '7', '٨' to '8', '٩' to '9'
        )

        val sb = StringBuilder(text.length)
        for (ch in text) sb.append(map[ch] ?: ch)

        return sb.toString()
            .trim()
            .replace("ی", "ی")
            .replace("ک", "ک")
            .replace("  +".toRegex(), " ")
            .lowercase()
    }
    
    private fun detectIntent(text: String): Intent {
        // چک‌های من
        if (text.contains("چک") && (text.contains("من") || text.contains("دارم") || text.contains("لیست"))) {
            return Intent(IntentType.CHECK_INQUIRY)
        }
        
        // افزودن چک
        if (text.contains("چک") && (text.contains("اضافه") || text.contains("ثبت") || text.contains("جدید"))) {
            return Intent(IntentType.CHECK_ADD, extractCheckData(text))
        }
        
        // اقساط
        if (text.contains("قسط") && (text.contains("من") || text.contains("دارم") || text.contains("لیست"))) {
            return Intent(IntentType.INSTALLMENT_INQUIRY)
        }
        
        if (text.contains("قسط") && (text.contains("اضافه") || text.contains("ثبت") || text.contains("جدید"))) {
            return Intent(IntentType.INSTALLMENT_ADD, extractInstallmentData(text))
        }
        
        if (text.contains("قسط") && (text.contains("پرداخت") || text.contains("دادم") || text.contains("واریز"))) {
            return Intent(IntentType.INSTALLMENT_PAY, extractInstallmentData(text))
        }
        
        // گزارش مالی
        if ((text.contains("گزارش") || text.contains("وضعیت")) && text.contains("مال")) {
            return Intent(IntentType.FINANCE_REPORT)
        }

        // ثبت هزینه/درآمد
        if ((text.contains("هزینه") || text.contains("خرج")) && Regex("\\d+").containsMatchIn(text)) {
            return Intent(IntentType.FINANCE_ADD, extractFinanceData(text, "expense"))
        }

        if ((text.contains("درآمد") || text.contains("واریز")) && Regex("\\d+").containsMatchIn(text)) {
            return Intent(IntentType.FINANCE_ADD, extractFinanceData(text, "income"))
        }
        
        // یادآوری
        if (text.contains("یاد") && (text.contains("بنداز") || text.contains("بده") || text.contains("آور"))) {
            return Intent(IntentType.REMINDER_ADD, extractReminderData(text))
        }
        
        // عبارت‌هایی مثل «یه یادآوری تنظیم کن که فردا ساعت ۹ ...»
        if (text.contains("یادآوری") &&
            (text.contains("ثبت") || text.contains("تنظیم") || text.contains("بساز") ||
             text.contains("بذار") || text.contains("کن") || text.startsWith("یادآوری"))) {
            return Intent(IntentType.REMINDER_ADD, extractReminderData(text))
        }
        
        if (text.contains("یادآوری") && (text.contains("من") || text.contains("لیست"))) {
            return Intent(IntentType.REMINDER_LIST)
        }

        // سفر
        if (text.contains("سفر") || text.contains("سفرنامه") || text.contains("مسافرت")) {
            return when {
                text.contains("برنامه") || text.contains("پلان") || text.contains("plan") -> Intent(IntentType.TRAVEL_PLAN, extractTravelData(text))
                text.contains("هشدار") || text.contains("شرایط") || text.contains("مسیر") -> Intent(IntentType.TRAVEL_ALERT, extractTravelData(text))
                else -> Intent(IntentType.TRAVEL_PLAN, extractTravelData(text))
            }
        }

        // رویداد خانوادگی
        if (text.contains("تولد") || text.contains("سالگرد") || text.contains("مهمانی")) {
            return Intent(IntentType.FAMILY_EVENT, extractFamilyData(text))
        }

        // هشدار بانکی / حسابی
        if ((text.contains("بانک") || text.contains("کارت") || text.contains("حساب")) &&
            (text.contains("هشدار") || text.contains("بدهی") || text.contains("کسری") || text.contains("اعلان"))) {
            return Intent(IntentType.BANKING_ALERT, extractBankingContext(text))
        }

        // سوال عمومی
        if (text.contains("چیست") || text.contains("چیه") || text.contains("چطور") || 
            text.contains("؟") || text.contains("توضیح")) {
            return Intent(IntentType.GENERAL_QUESTION)
        }
        
        return Intent(IntentType.UNKNOWN)
    }
    
    private fun extractCheckData(text: String): Map<String, Any> {
        val data = mutableMapOf<String, Any>()
        
        // استخراج مبلغ (اعداد فارسی و انگلیسی)
        val amountRegex = """(\d+[\d,]*)\s*(تومان|ریال|میلیون)?""".toRegex()
        amountRegex.find(text)?.let {
            val amount = it.groupValues[1].replace(",", "").toDoubleOrNull()
            if (amount != null) {
                val unit = it.groupValues[2]
                data["amount"] = when (unit) {
                    "میلیون" -> amount * 1000000
                    "ریال" -> amount / 10
                    else -> amount
                }
            }
        }
        
        // استخراج تاریخ
        val dateRegex = """(\d{4})/(\d{1,2})/(\d{1,2})""".toRegex()
        dateRegex.find(text)?.let {
            // پردازش تاریخ
            data["date"] = it.value
        }
        
        return data
    }

    private fun extractFinanceData(text: String, type: String): Map<String, Any> {
        val data = mutableMapOf<String, Any>()
        data["type"] = type

        val match = Regex("([0-9]+(?:,[0-9]{3})*)\\s*(میلیون|هزار|ریال)?").find(text)
        val base = match?.groupValues?.getOrNull(1)?.replace(",", "")?.toDoubleOrNull() ?: 0.0
        val unit = match?.groupValues?.getOrNull(2).orEmpty()
        val amount = when {
            unit.contains("میلیون") -> base * 1_000_000
            unit.contains("هزار") -> base * 1_000
            unit.contains("ریال") -> base / 10
            else -> base
        }
        data["amount"] = amount

        val desc = when {
            type == "expense" && text.contains("هزینه") -> text.substringAfter("هزینه", "").trim()
            type == "expense" && text.contains("خرج") -> text.substringAfter("خرج", "").trim()
            type == "income" && text.contains("درآمد") -> text.substringAfter("درآمد", "").trim()
            type == "income" && text.contains("واریز") -> text.substringAfter("واریز", "").trim()
            else -> ""
        }.ifBlank { null }

        if (desc != null) data["description"] = desc
        return data
    }

    private fun extractTravelData(text: String): Map<String, Any> {
        val data = mutableMapOf<String, Any>()
        val destinationRegex = """(به|برای)?\s*(مشهد|تهران|اصفهان|شیراز|تبریز|[آ-ی]+)""".toRegex()
        destinationRegex.find(text)?.let {
            val dest = it.groupValues.last().trim()
            if (dest.isNotEmpty()) data["destination"] = dest
        }
        val dateRegex = """(\d{4})/(\d{1,2})/(\d{1,2})""".toRegex()
        dateRegex.find(text)?.let { data["date"] = it.value }
        val transport = when {
            text.contains("هواپیما") || text.contains("پرواز") -> TravelPlannerManager.TransportType.PLANE.name
            text.contains("قطار") -> TravelPlannerManager.TransportType.TRAIN.name
            text.contains("اتوبوس") -> TravelPlannerManager.TransportType.BUS.name
            text.contains("ماشین") || text.contains("خودرو") -> TravelPlannerManager.TransportType.CAR.name
            else -> TravelPlannerManager.TransportType.OTHER.name
        }
        data["transport"] = transport
        return data
    }

    private fun extractFamilyData(text: String): Map<String, Any> {
        val data = mutableMapOf<String, Any>()
        val personRegex = """برای\s+([آ-ی]+)""".toRegex()
        personRegex.find(text)?.let { data["person"] = it.groupValues[1] }
        data["type"] = when {
            text.contains("تولد") -> SmartReminderManager.ReminderType.BIRTHDAY.name
            text.contains("سالگرد") -> SmartReminderManager.ReminderType.ANNIVERSARY.name
            else -> SmartReminderManager.ReminderType.FAMILY.name
        }
        return data
    }

    private fun extractBankingContext(text: String): Map<String, Any> {
        val data = mutableMapOf<String, Any>()
        if (text.contains("کارت") || text.contains("بانک")) {
            data["channel"] = "card"
        }
        if (text.contains("بدهی") || text.contains("دین")) {
            data["focus"] = "debt"
        } else if (text.contains("کسری") || text.contains("منفی")) {
            data["focus"] = "cashflow"
        }
        return data
    }
    
    private fun extractInstallmentData(text: String): Map<String, Any> {
        val data = mutableMapOf<String, Any>()
        
        // استخراج عنوان قسط
        if (text.contains("ماشین") || text.contains("خودرو")) {
            data["title"] = "قسط خودرو"
        } else if (text.contains("خانه") || text.contains("خونه")) {
            data["title"] = "قسط خانه"
        }
        
        // استخراج مبلغ
        val amountRegex = """(\d+[\d,]*)\s*(تومان|ریال|میلیون)?""".toRegex()
        amountRegex.find(text)?.let {
            val amount = it.groupValues[1].replace(",", "").toDoubleOrNull()
            if (amount != null) {
                data["amount"] = amount
            }
        }
        
        return data
    }
    
    private fun extractReminderData(text: String): Map<String, Any> {
        val data = mutableMapOf<String, Any>()
        
        // استخراج زمان با فرمت HH:mm
        val timeRegex = """(\d{1,2}):(\d{2})""".toRegex()
        timeRegex.find(text)?.let {
            data["hour"] = it.groupValues[1].toInt()
            data["minute"] = it.groupValues[2].toInt()
        }
        
        // اگر فرمت HH:mm نبود، ابتدا الگوی «ساعت ۶ و ۴۲ دقیقه صبح» را امتحان کن
        if (!data.containsKey("hour")) {
            val detailedTimeRegex = """ساعت\s*(\d{1,2})\s*و\s*(\d{1,2})\s*دقیقه\s*(صبح|ظهر|عصر|شب)?""".toRegex()
            detailedTimeRegex.find(text)?.let {
                val rawHour = it.groupValues[1].toIntOrNull() ?: 0
                val minute = it.groupValues[2].toIntOrNull() ?: 0
                val period = it.groupValues.getOrNull(3) ?: ""
                val hour24 = when (period) {
                    "ظهر", "عصر", "شب" -> if (rawHour in 1..11) rawHour + 12 else rawHour
                    else -> rawHour
                }
                data["hour"] = hour24
                data["minute"] = minute
            }
        }

        // اگر هنوز ساعت مشخص نیست، الگوی ساده «ساعت ۹ صبح/عصر/شب» را امتحان کن
        if (!data.containsKey("hour")) {
            val fuzzyTimeRegex = """ساعت\s*(\d{1,2})\s*(صبح|ظهر|عصر|شب)?""".toRegex()
            fuzzyTimeRegex.find(text)?.let {
                val rawHour = it.groupValues[1].toIntOrNull() ?: 0
                val period = it.groupValues.getOrNull(2) ?: ""
                val hour24 = when (period) {
                    "ظهر", "عصر", "شب" -> if (rawHour in 1..11) rawHour + 12 else rawHour
                    else -> rawHour
                }
                data["hour"] = hour24
                data["minute"] = 0
            }
        }
        
        // استخراج روز
        when {
            text.contains("پس‌فردا") || text.contains("پس فردا") -> data["day"] = "dayAfterTomorrow"
            text.contains("فردا") -> data["day"] = "tomorrow"
            text.contains("امروز") -> data["day"] = "today"
        }
        
        // روزهای هفته برای تکرار سفارشی و بازه‌ها
        val weekdayMap = mapOf(
            "شنبه" to java.util.Calendar.SATURDAY,
            "یکشنبه" to java.util.Calendar.SUNDAY,
            "دوشنبه" to java.util.Calendar.MONDAY,
            "سه‌شنبه" to java.util.Calendar.TUESDAY,
            "سه شنبه" to java.util.Calendar.TUESDAY,
            "چهارشنبه" to java.util.Calendar.WEDNESDAY,
            "پنجشنبه" to java.util.Calendar.THURSDAY,
            "پنج‌شنبه" to java.util.Calendar.THURSDAY,
            "جمعه" to java.util.Calendar.FRIDAY
        )

        // بازه‌هایی مثل «از شنبه تا چهارشنبه»
        val rangeRegex = """از\s+(شنبه|یکشنبه|دوشنبه|سه‌شنبه|سه شنبه|چهارشنبه|پنجشنبه|پنج‌شنبه|جمعه)\s+تا\s+(شنبه|یکشنبه|دوشنبه|سه‌شنبه|سه شنبه|چهارشنبه|پنجشنبه|پنج‌شنبه|جمعه)""".toRegex()
        rangeRegex.find(text)?.let { matchResult ->
            val startName = matchResult.groupValues[1]
            val endName = matchResult.groupValues[2]
            val start = weekdayMap[startName]
            val end = weekdayMap[endName]
            if (start != null && end != null) {
                val days = mutableListOf<Int>()
                var d = start!!
                while (true) {
                    days.add(d)
                    if (d == end) break
                    d = if (d == java.util.Calendar.SATURDAY) java.util.Calendar.SUNDAY else d + 1
                }
                data["repeat"] = "custom"
                data["customDays"] = days
            }
        }

        // الگوهایی مثل «هر شنبه» برای تکرار هفتگی در روزهای مشخص
        if (!data.containsKey("customDays")) {
            val customDays = mutableListOf<Int>()
            weekdayMap.forEach { (name, dayConst) ->
                if (text.contains("هر $name")) {
                    customDays.add(dayConst)
                }
            }
            if (customDays.isNotEmpty()) {
                data["repeat"] = "custom"
                data["customDays"] = customDays
            }
        }

        // الگوی تکرار ساده روزانه
        if (!data.containsKey("repeat") && (text.contains("هر روز") || text.contains("روزانه"))) {
            data["repeat"] = "daily"
        }

        // زمان‌های نسبی مثل «10 دقیقه دیگه» یا «2 ساعت بعد»
        val relativeTimeRegex = """(\d+|نیم)\s+(دقیقه|ساعت)\s+(دیگه|بعد|آینده)""".toRegex()
        relativeTimeRegex.find(text)?.let {
            val value = it.groupValues[1]
            val unit = it.groupValues[2]
            val amount = if (value == "نیم") 0.5 else value.toDoubleOrNull() ?: 0.0

            if (amount > 0) {
                val millis = when (unit) {
                    "دقیقه" -> amount * 60 * 1000
                    "ساعت" -> amount * 60 * 60 * 1000
                    else -> 0.0
                }
                if (millis > 0) {
                    data["relativeMillis"] = millis.toLong()
                }
            }
        }
        
        // استخراج متن یادآوری
        val messageRegex = """(یادم بنداز|یاد بده|یادآوری کن)\s+(.+)""".toRegex()
        messageRegex.find(text)?.let {
            data["message"] = it.groupValues[2].trim()
        }
        
        if (!data.containsKey("message")) {
            var msg = text
                .replace("یادم بنداز", "")
                .replace("یاد بده", "")
                .replace("یادآوری کن", "")
                .trim()
            if (msg.isNotEmpty()) {
                data["message"] = msg
            }
        }
        
        return data
    }
    
    private fun handleCheckInquiry(intent: Intent): AssistantResponse {
        val checks = checkManager.getAllChecks()
        val pending = checks.filter { it.status == CheckManager.CheckStatus.PENDING }
        val upcoming = checkManager.getUpcomingChecks(30)
        val needAlert = checkManager.getChecksNeedingAlert()
        
        if (checks.isEmpty()) {
            return AssistantResponse(
                text = "📋 شما هیچ چکی ثبت نکرده‌اید.\n\nمی‌توانید با گفتن «ثبت چک جدید» یک چک اضافه کنید.",
                actionType = ActionType.OPEN_CHECKS
            )
        }
        
        val response = buildString {
            appendLine("📋 وضعیت چک‌های شما:\n")
            appendLine("💰 کل چک‌های در انتظار: ${pending.size} عدد")
            appendLine("💵 مبلغ کل: ${formatMoney(checkManager.getTotalPendingAmount())} تومان")
            
            if (needAlert.isNotEmpty()) {
                appendLine("\n⚠️ توجه: ${needAlert.size} چک نزدیک به سررسید است!")
                needAlert.take(3).forEach { check ->
                    val days = ((check.dueDate - System.currentTimeMillis()) / (24 * 60 * 60 * 1000)).toInt()
                    appendLine("• چک ${check.checkNumber}: $days روز دیگر (${formatMoney(check.amount)} تومان)")
                }
            }
            
            if (upcoming.isNotEmpty()) {
                appendLine("\n📅 چک‌های 30 روز آینده: ${upcoming.size} عدد")
            }
        }
        
        return AssistantResponse(
            text = response,
            actionType = ActionType.OPEN_CHECKS,
            data = mapOf("checks" to checks)
        )
    }
    
    private fun handleCheckAdd(intent: Intent): AssistantResponse {
        return AssistantResponse(
            text = "✅ برای افزودن چک جدید، لطفاً اطلاعات زیر را بدهید:\n\n" +
                   "• شماره چک\n" +
                   "• مبلغ (تومان)\n" +
                   "• تاریخ سررسید\n" +
                   "• نام صادرکننده\n" +
                   "• نام بانک\n\n" +
                   "یا روی دکمه زیر بزنید تا فرم را باز کنم.",
            actionType = ActionType.ADD_CHECK
        )
    }
    
    private fun handleInstallmentInquiry(intent: Intent): AssistantResponse {
        val installments = installmentManager.getActiveInstallments()
        val upcoming = installmentManager.getUpcomingPayments(7)
        val totalRemaining = installmentManager.getTotalRemainingAmount()
        
        if (installments.isEmpty()) {
            return AssistantResponse(
                text = "💳 شما هیچ قسطی ثبت نکرده‌اید.\n\nمی‌توانید با گفتن «ثبت قسط جدید» یک قسط اضافه کنید.",
                actionType = ActionType.OPEN_INSTALLMENTS
            )
        }
        
        val response = buildString {
            appendLine("💳 وضعیت اقساط شما:\n")
            appendLine("📊 اقساط فعال: ${installments.size} مورد")
            appendLine("💰 مبلغ کل باقیمانده: ${formatMoney(totalRemaining)} تومان")
            
            if (upcoming.isNotEmpty()) {
                appendLine("\n⏰ پرداخت‌های 7 روز آینده:")
                upcoming.take(3).forEach { (installment, dueDate) ->
                    val days = ((dueDate - System.currentTimeMillis()) / (24 * 60 * 60 * 1000)).toInt()
                    appendLine("• ${installment.title}: $days روز دیگر (${formatMoney(installment.installmentAmount)} تومان)")
                }
            }
            
            appendLine("\n📈 جزئیات:")
            installments.take(5).forEach { i ->
                val progress = (i.paidInstallments.toFloat() / i.totalInstallments * 100).toInt()
                appendLine("• ${i.title}: $progress% پرداخت شده (${i.paidInstallments}/${i.totalInstallments})")
            }
        }
        
        return AssistantResponse(
            text = response,
            actionType = ActionType.OPEN_INSTALLMENTS,
            data = mapOf("installments" to installments)
        )
    }
    
    private fun handleInstallmentAdd(intent: Intent): AssistantResponse {
        return AssistantResponse(
            text = "✅ برای افزودن قسط جدید، اطلاعات زیر را بدهید:\n\n" +
                   "• عنوان قسط (مثل: قسط ماشین)\n" +
                   "• مبلغ کل\n" +
                   "• مبلغ هر قسط\n" +
                   "• تعداد اقساط\n" +
                   "• روز پرداخت در ماه\n\n" +
                   "یا روی دکمه زیر بزنید.",
            actionType = ActionType.ADD_INSTALLMENT
        )
    }
    
    private fun handleInstallmentPay(intent: Intent): AssistantResponse {
        return AssistantResponse(
            text = "💳 کدام قسط را پرداخت کرده‌اید؟\n\nلطفاً نام قسط را بگویید.",
            actionType = ActionType.OPEN_INSTALLMENTS
        )
    }
    
    private fun handleFinanceReport(intent: Intent): AssistantResponse {
        val balance = financeManager.getBalance()
        val calendar = Calendar.getInstance()
        val (income, expense) = financeManager.getMonthlyReport(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1
        )
        
        val checksTotal = checkManager.getTotalPendingAmount()
        val installmentsTotal = installmentManager.getTotalRemainingAmount()
        
        val response = buildString {
            appendLine("💰 گزارش مالی شما:\n")
            appendLine("📊 موجودی کل: ${formatMoney(balance)} تومان")
            appendLine("📈 درآمد این ماه: ${formatMoney(income)} تومان")
            appendLine("📉 هزینه این ماه: ${formatMoney(expense)} تومان")
            appendLine("💵 سود/زیان: ${formatMoney(income - expense)} تومان")
            
            appendLine("\n💼 تعهدات:")
            appendLine("📋 چک‌های در انتظار: ${formatMoney(checksTotal)} تومان")
            appendLine("💳 اقساط باقیمانده: ${formatMoney(installmentsTotal)} تومان")
            
            val netWorth = balance - checksTotal - installmentsTotal
            appendLine("\n💎 خالص دارایی: ${formatMoney(netWorth)} تومان")
            
            if (netWorth < 0) {
                appendLine("\n⚠️ توجه: شما ${formatMoney(-netWorth)} تومان بدهی دارید.")
            } else {
                appendLine("\n✅ وضعیت مالی شما مناسب است.")
            }
        }
        
        return AssistantResponse(text = response)
    }

    private fun handleFinanceAdd(intent: Intent): AssistantResponse {
        val type = intent.data["type"] as? String
        val amount = intent.data["amount"] as? Double
        if (type.isNullOrBlank() || amount == null || amount <= 0.0) {
            return AssistantResponse("⚠️ برای ثبت هزینه/درآمد، مبلغ را هم بگویید. مثلا: «هزینه 50 هزار تاکسی»")
        }

        val desc = intent.data["description"] as? String ?: ""
        val category = if (type == "income") "درآمد" else "هزینه"
        val id = financeManager.addTransaction(amount = amount, type = type, category = category, desc = desc)
        val label = if (type == "income") "درآمد" else "هزینه"

        return AssistantResponse(
            text = "✅ $label ثبت شد: ${formatMoney(amount)} تومان" + (if (desc.isNotBlank()) "\n📝 $desc" else ""),
            data = mapOf("transactionId" to id)
        )
    }
    
    private fun handleReminderAdd(intent: Intent): AssistantResponse {
        val data = intent.data
        
        if (data.isEmpty()) {
            return AssistantResponse(
                text = "⏰ برای تنظیم یادآوری، زمان و متن را بگویید.\n\n" +
                       "مثال:\n" +
                       "• فردا ساعت 9 یادم بنداز قرص بخورم\n" +
                       "• امروز 5 بعدازظهر یاد بده سوپرمارکت برم"
            )
        }
        
        val message = (data["message"] as? String)?.takeIf { it.isNotBlank() }
        if (message == null) {
            return AssistantResponse(
                text = "برای تنظیم یادآوری، متن کار را هم مشخص کنید. مثلاً: «فردا ساعت ۹ یادم بنداز قبض برق رو پرداخت کنم.»"
            )
        }
        
        val relativeMillis = data["relativeMillis"] as? Long
        val triggerTime: Long

        if (relativeMillis != null) {
            triggerTime = System.currentTimeMillis() + relativeMillis
        } else {
            val hour = data["hour"] as? Int
            val minute = data["minute"] as? Int ?: 0

            if (hour == null) {
                return AssistantResponse(
                    text = "⚠️ ساعت یادآوری مشخص نیست. لطفاً زمانی مثل «ساعت ۹ صبح»، «۱۸:۳۰» یا «۱۰ دقیقه دیگه» بگویید."
                )
            }

            val day = data["day"] as? String
            val calendar = Calendar.getInstance()

            when (day) {
                "tomorrow" -> calendar.add(Calendar.DAY_OF_MONTH, 1)
                "dayAfterTomorrow" -> calendar.add(Calendar.DAY_OF_MONTH, 2)
            }

            calendar.set(Calendar.HOUR_OF_DAY, hour)
            calendar.set(Calendar.MINUTE, minute)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)

            if (calendar.timeInMillis <= System.currentTimeMillis() && (day == null || day == "today")) {
                calendar.add(Calendar.DAY_OF_MONTH, 1)
            }
            triggerTime = calendar.timeInMillis
        }

        val repeat = data["repeat"] as? String
        val customDays = data["customDays"] as? List<Int>

        val title = message.take(40)
        val description = if (message.length > 40) message else ""
        
        val createdReminder = when {
            repeat == "daily" -> {
                reminderManager.createRecurringReminder(
                    title = title,
                    description = description,
                    firstTriggerTime = triggerTime,
                    repeatPattern = SmartReminderManager.RepeatPattern.DAILY
                )
            }
            repeat == "custom" && customDays != null && customDays.isNotEmpty() -> {
                reminderManager.createRecurringReminder(
                    title = title,
                    description = description,
                    firstTriggerTime = triggerTime,
                    repeatPattern = SmartReminderManager.RepeatPattern.CUSTOM,
                    customDays = customDays
                )
            }
            else -> {
                reminderManager.createSimpleReminder(
                    title = title,
                    description = description,
                    triggerTime = triggerTime
                )
            }
        }
        
        val readableTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(triggerTime))
        val repeatText = when (repeat) {
            "daily" -> "🔁 هر روز"
            "custom" -> "🔁 روزهای خاص هفته"
            else -> "یکبار"
        }
        
        return AssistantResponse(
            text = "✅ یادآوری تنظیم شد:\n" +
                   "⏰ $readableTime\n" +
                   "📝 $message\n" +
                   "📌 $repeatText",
            actionType = ActionType.ADD_REMINDER,
            data = mapOf("reminderId" to createdReminder.id)
        )
    }
    
    private fun handleReminderList(intent: Intent): AssistantResponse {
        val activeReminders = reminderManager.getActiveReminders().sortedBy { it.triggerTime }

        if (activeReminders.isEmpty()) {
            return AssistantResponse(
                text = "⏰ شما هیچ یادآوری فعالی ندارید.",
                actionType = ActionType.OPEN_REMINDERS
            )
        }

        val responseText = buildString {
            appendLine("⏰ یادآوری‌های فعال شما:")
            activeReminders.take(5).forEach { reminder ->
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(reminder.triggerTime))
                appendLine("• ${reminder.title} - ساعت $time")
            }
            if (activeReminders.size > 5) {
                appendLine("... و ${activeReminders.size - 5} مورد دیگر.")
            }
        }

        return AssistantResponse(
            text = responseText,
            actionType = ActionType.OPEN_REMINDERS
        )
    }

    private fun handleTravelPlan(intent: Intent): AssistantResponse {
        val destination = intent.data["destination"] as? String ?: "مقصد نامشخص"
        val transport = intent.data["transport"] as? String ?: TravelPlannerManager.TransportType.CAR.name
        val summary = buildString {
            appendLine("🧳 برنامه سفر به $destination")
            appendLine("وسیله: ${TravelPlannerManager.TransportType.valueOf(transport).displayName}")
            appendLine("برای دریافت پیشنهاد دقیق، تاریخ و افراد هم بگویید.")
        }
        return AssistantResponse(summary, actionType = ActionType.OPEN_TRAVEL)
    }

    private fun handleTravelAlert(intent: Intent): AssistantResponse {
        val destination = intent.data["destination"] as? String ?: return AssistantResponse(
            "برای بررسی هشدار سفر، مقصد را بگویید.", actionType = ActionType.OPEN_TRAVEL
        )
        val smartAlerts = SmartAlertBuilder().buildTravelAlerts(destination)
        return AssistantResponse(
            text = smartAlerts,
            actionType = ActionType.OPEN_TRAVEL,
            data = mapOf("destination" to destination)
        )
    }

    private fun handleFamilyEvent(intent: Intent): AssistantResponse {
        val person = intent.data["person"] as? String ?: "یکی از اعضای خانواده"
        val reminder = reminderManager.createBirthdayReminder(person, System.currentTimeMillis() + 24 * 60 * 60 * 1000)
        return AssistantResponse(
            text = "🎉 یادآوری ${reminder.title} ثبت شد!",
            actionType = ActionType.ADD_REMINDER,
            data = mapOf("reminderId" to reminder.id)
        )
    }

    private fun handleBankingAlert(intent: Intent): AssistantResponse {
        val alerts = SmartAlertBuilder().buildBankingAlerts(checkManager, installmentManager)
        return AssistantResponse(
            text = alerts,
            actionType = ActionType.OPEN_CHECKS
        )
    }
    
    private fun handleGeneralQuestion(intent: Intent): AssistantResponse {
        return AssistantResponse(
            text = "❓ سوال شما نیاز به جستجو یا مدل AI دارد.\n\nلطفاً صبر کنید...",
            actionType = ActionType.NEEDS_AI
        )
    }
    
    private fun formatMoney(amount: Double): String {
        return String.format("%,.0f", amount)
    }
    
    data class Intent(
        val type: IntentType,
        val data: Map<String, Any> = emptyMap()
    )
    
    enum class IntentType {
        CHECK_INQUIRY,
        CHECK_ADD,
        INSTALLMENT_INQUIRY,
        INSTALLMENT_ADD,
        INSTALLMENT_PAY,
        FINANCE_REPORT,
        FINANCE_ADD,
        REMINDER_ADD,
        REMINDER_LIST,
        TRAVEL_PLAN,
        TRAVEL_ALERT,
        FAMILY_EVENT,
        BANKING_ALERT,
        GENERAL_QUESTION,
        UNKNOWN
    }
    
    data class AssistantResponse(
        val text: String,
        val actionType: ActionType? = null,
        val data: Map<String, Any> = emptyMap()
    )
    
    enum class ActionType {
        OPEN_CHECKS,
        ADD_CHECK,
        OPEN_INSTALLMENTS,
        ADD_INSTALLMENT,
        OPEN_REMINDERS,
        ADD_REMINDER,
        NEEDS_AI,
        OPEN_TRAVEL
    }

    private class SmartAlertBuilder {
        fun buildTravelAlerts(destination: String): String {
            val tips = listOf(
                "شرایط مسیر به $destination را قبل از حرکت بررسی کنید.",
                "آب‌وهوای مقصد را از کارت سفر مشاهده کنید.",
                "برای خانواده پیام وضعیت ارسال کنید."
            )
            return "🚦 هشدارهای سفر به $destination:\n" + tips.joinToString("\n") { "• $it" }
        }

        fun buildBankingAlerts(checkManager: CheckManager, installmentManager: InstallmentManager): String {
            val upcomingChecks = checkManager.getUpcomingChecks(7)
            val upcomingInstallments = installmentManager.getUpcomingPayments(7)
            return buildString {
                appendLine("🏦 هشدارهای بانکی:")
                if (upcomingChecks.isEmpty()) {
                    appendLine("• چک بحرانی تا یک هفته آینده ندارید.")
                } else {
                    upcomingChecks.take(3).forEach {
                        val days = ((it.dueDate - System.currentTimeMillis()) / (24 * 60 * 60 * 1000)).coerceAtLeast(0)
                        appendLine("• چک ${it.checkNumber} ${days}روز دیگر سررسید می‌شود.")
                    }
                }
                if (upcomingInstallments.isEmpty()) {
                    appendLine("• قسط بحرانی تا یک هفته آینده ندارید.")
                } else {
                    upcomingInstallments.take(3).forEach { (installment, dueDate) ->
                        val days = ((dueDate - System.currentTimeMillis()) / (24 * 60 * 60 * 1000)).coerceAtLeast(0)
                        appendLine("• ${installment.title} ${days}روز دیگر پرداخت می‌شود.")
                    }
                }
            }
        }
    }
}
