package com.persianai.assistant.finance

import android.content.Context
import com.persianai.assistant.data.AccountingDB
import com.persianai.assistant.data.Transaction as DBTransaction
import com.persianai.assistant.data.TransactionType
import com.persianai.assistant.utils.PersianDateConverter
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * سیستم حسابداری ساده - یکپارچه با AccountingDB
 * 
 * توجه: تنها منبع حقیقت (source of truth) دیتابیس SQLite است.
 * SharedPreferences فقط برای سازگاری با نسخه‌های قدیمی (Legacy) نگه داشته شده
 * و هنگام خواندن تراکنش‌ها اولویت با دیتابیس است و SharedPreferences مکمل.
 */
class FinanceManager(private val context: Context) {
    
    private val accountingDB: AccountingDB by lazy { AccountingDB(context) }
    
    data class Transaction(
        val id: String,
        val amount: Double,
        val type: String, // "income" or "expense"
        val category: String,
        val description: String,
        val date: Long
    ) {
        fun getFormattedDate(): String {
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = date
            val persianDate = PersianDateConverter.gregorianToPersian(
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH)
            )
            return persianDate.toReadableString()
        }
    }
    
    private val prefs = context.getSharedPreferences("finance", Context.MODE_PRIVATE)
    
    fun addTransaction(amount: Double, type: String, category: String, desc: String): String {
        val now = System.currentTimeMillis()
        var finalId: String = UUID.randomUUID().toString()

        try {
            if (type == "income" || type == "expense") {
                val dbType = if (type == "income") TransactionType.INCOME else TransactionType.EXPENSE
                val dbTransaction = DBTransaction(
                    type = dbType,
                    amount = amount,
                    category = category,
                    description = desc,
                    date = now
                )
                val dbId = accountingDB.addTransaction(dbTransaction)
                if (dbId > 0) {
                    finalId = dbId.toString()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("FinanceManager", "خطا در ذخیره در AccountingDB", e)
        }

        // ساخت شیٔ نهایی تراکنش با شناسهٔ نهایی (db id یا uuid)
        val transaction = Transaction(finalId, amount, type, category, desc, now)

        // ذخیره در SharedPreferences (برای سازگاری و تاریخچه)
        // فقط تراکنش‌های قدیمی را نگه می‌داریم که در دیتابیس نیستند
        val existingPrefs = getLegacyPrefsTransactions()
        // اگر قبلاً تراکنشی با همان id وجود داشته باشد آن را حذف می‌کنیم
        val filtered = existingPrefs.filter { it.id != transaction.id }.toMutableList()
        filtered.add(transaction)
        saveTransactions(filtered)

        return finalId
    }
    
    /**
     * خواندن فقط از SharedPreferences (برای مهاجرت)
     */
    private fun getLegacyPrefsTransactions(): List<Transaction> {
        val json = prefs.getString("transactions", "[]") ?: "[]"
        val array = JSONArray(json)
        val list = mutableListOf<Transaction>()
        
        for (i in 0 until array.length()) {
            try {
                val obj = array.getJSONObject(i)
                list.add(Transaction(
                    obj.getString("id"),
                    obj.getDouble("amount"),
                    obj.getString("type"),
                    obj.getString("category"),
                    obj.getString("description"),
                    obj.getLong("date")
                ))
            } catch (_: Exception) {}
        }
        return list
    }
    
    fun getAllTransactions(): List<Transaction> {
        // منبع اصلی: AccountingDB (جدیدترین و معتبرترین منبع)
        val dbList = try {
            accountingDB.getAllTransactions().map { dbTx ->
                Transaction(
                    id = dbTx.id.toString(),
                    amount = dbTx.amount,
                    type = if (dbTx.type == TransactionType.INCOME) "income" else "expense",
                    category = dbTx.category,
                    description = dbTx.description,
                    date = dbTx.date
                )
            }
        } catch (_: Exception) { emptyList<Transaction>() }
        
        // منبع ثانویه: SharedPreferences (برای تراکنش‌های قدیمی که در دیتابیس نیستند)
        val prefsList = getLegacyPrefsTransactions()
        
        // ادغام: تراکنش‌های دیتابیس اولویت دارند
        // تراکنش‌های SharedPreferences که ID آن‌ها در دیتابیس نیست اضافه می‌شوند
        val dbIds = dbList.map { it.id }.toSet()
        val onlyInPrefs = prefsList.filter { it.id !in dbIds }
        
        val allTransactions = (dbList + onlyInPrefs)
            .sortedByDescending { it.date }
        
        return allTransactions
    }
    
    private fun saveTransactions(transactions: List<Transaction>) {
        val array = JSONArray()
        transactions.forEach { t ->
            array.put(JSONObject().apply {
                put("id", t.id)
                put("amount", t.amount)
                put("type", t.type)
                put("category", t.category)
                put("description", t.description)
                put("date", t.date)
            })
        }
        prefs.edit().putString("transactions", array.toString()).apply()
    }
    
    fun exportToCSV(): String {
        val sb = StringBuilder()
        sb.appendLine("ID,مبلغ,نوع,دسته‌بندی,توضیحات,تاریخ")
        getAllTransactions().forEach { t ->
            sb.appendLine("${t.id},${t.amount},${t.type},\"${t.category}\",\"${t.description}\",${t.date}")
        }
        return sb.toString()
    }
    
    fun getBalance(): Double {
        val transactions = getAllTransactions()
        var balance = 0.0
        transactions.forEach {
            if (it.type == "income") {
                balance += it.amount
            } else {
                balance -= it.amount
            }
        }
        return balance
    }
    
    fun getMonthlyReport(year: Int, month: Int): Triple<Double, Double, Int> {
        val calendar = Calendar.getInstance()
        calendar.set(year, month - 1, 1, 0, 0, 0)
        val startTime = calendar.timeInMillis
        
        calendar.set(Calendar.DAY_OF_MONTH, calendar.getActualMaximum(Calendar.DAY_OF_MONTH))
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        val endTime = calendar.timeInMillis
        
        val transactions = getAllTransactions().filter { it.date in startTime..endTime }
        
        var income = 0.0
        var expense = 0.0
        
        transactions.forEach {
            if (it.type == "income") income += it.amount
            else expense += it.amount
        }
        
        return Triple(income, expense, transactions.size)
    }

    fun getYearlyReport(year: Int): Triple<Double, Double, Int> {
        val calendar = Calendar.getInstance()
        calendar.set(year, 0, 1, 0, 0, 0)
        val startTime = calendar.timeInMillis
        
        calendar.set(Calendar.MONTH, 11)
        calendar.set(Calendar.DAY_OF_MONTH, 31)
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        val endTime = calendar.timeInMillis
        
        val transactions = getAllTransactions().filter { it.date in startTime..endTime }
        
        var income = 0.0
        var expense = 0.0
        
        transactions.forEach {
            if (it.type == "income") income += it.amount
            else expense += it.amount
        }
        
        return Triple(income, expense, transactions.size)
    }

    fun getMonthlyBalanceReport(year: Int, month: Int): String {
        val (income, expense, count) = getMonthlyReport(year, month)
        val balance = income - expense
        val persianMonth = try {
            PersianDateConverter.gregorianToPersian(year, month, 1).toReadableString().split(" ").getOrNull(1) ?: "$month"
        } catch (_: Exception) { "$month" }
        
        return buildString {
            appendLine("📊 تراز ماه $persianMonth $year")
            appendLine("=".repeat(30))
            appendLine("📈 درآمد: ${String.format("%,.0f", income)} تومان")
            appendLine("📉 هزینه: ${String.format("%,.0f", expense)} تومان")
            appendLine("💵 تراز: ${String.format("%,.0f", balance)} تومان")
            appendLine("📝 تعداد تراکنش‌ها: $count")
            if (balance > 0) appendLine("✅ مثبت") else if (balance < 0) appendLine("⚠️ منفی") else appendLine("⚖️ صفر")
        }
    }

    fun getYearlyBalanceReport(year: Int): String {
        val (income, expense, count) = getYearlyReport(year)
        val balance = income - expense
        
        return buildString {
            appendLine("📊 تراز سال $year")
            appendLine("=".repeat(30))
            appendLine("📈 درآمد: ${String.format("%,.0f", income)} تومان")
            appendLine("📉 هزینه: ${String.format("%,.0f", expense)} تومان")
            appendLine("💵 خالص: ${String.format("%,.0f", balance)} تومان")
            appendLine("📝 تعداد تراکنش‌ها: $count")
            if (balance > 0) appendLine("✅ سودده") else if (balance < 0) appendLine("⚠️ زیانده") else appendLine("⚖️ سر به سر")
        }
    }

    fun getSummary(): Map<String, String> {
        val transactions = getAllTransactions()
        var income = 0.0
        var expense = 0.0
        transactions.forEach {
            if (it.type == "income") income += it.amount else if (it.type == "expense") expense += it.amount
        }
        val balance = income - expense
        return mapOf(
            "income" to String.format("%.0f", income),
            "expense" to String.format("%.0f", expense),
            "total" to String.format("%.0f", balance)
        )
    }

    fun generateReport(timeRange: String): String {
        val tr = getAllTransactions()
        if (tr.isEmpty()) return "اطلاعاتی ثبت نشده است"

        val now = System.currentTimeMillis()
        val from = when (timeRange.lowercase()) {
            "day", "today", "روز" -> now - 24L * 60L * 60L * 1000L
            "week", "هفته" -> now - 7L * 24L * 60L * 60L * 1000L
            "year", "سال" -> now - 365L * 24L * 60L * 60L * 1000L
            else -> now - 30L * 24L * 60L * 60L * 1000L // month
        }

        val scoped = tr.filter { it.date in from..now }
        if (scoped.isEmpty()) return "در بازه انتخابی تراکنشی یافت نشد"

        var income = 0.0
        var expense = 0.0
        val topExpenseCategories = mutableMapOf<String, Double>()
        scoped.forEach {
            if (it.type == "income") {
                income += it.amount
            } else if (it.type == "expense") {
                expense += it.amount
                topExpenseCategories[it.category] = (topExpenseCategories[it.category] ?: 0.0) + it.amount
            }
        }

        val top = topExpenseCategories.entries
            .sortedByDescending { it.value }
            .take(5)
            .joinToString("\n") { "• ${it.key}: ${String.format("%,.0f", it.value)} تومان" }

        val balance = income - expense
        return buildString {
            appendLine("📊 گزارش مالی ($timeRange)")
            appendLine("=".repeat(25))
            appendLine("📈 درآمد: ${String.format("%,.0f", income)} تومان")
            appendLine("📉 هزینه: ${String.format("%,.0f", expense)} تومان")
            appendLine("💵 خالص: ${String.format("%,.0f", balance)} تومان")
            if (top.isNotBlank()) {
                appendLine()
                appendLine("بیشترین هزینه‌ها:")
                append(top)
            }
        }
    }
    
    fun deleteTransaction(id: String) {
        // حذف از دیتابیس
        try {
            val dbId = id.toLongOrNull()
            if (dbId != null) {
                accountingDB.deleteTransaction(dbId)
            }
        } catch (_: Exception) {}
        
        // حذف از SharedPreferences
        val prefsTransactions = getLegacyPrefsTransactions().filter { it.id != id }
        saveTransactions(prefsTransactions)
    }
}