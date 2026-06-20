package com.persianai.assistant.finance

import android.content.Context
import com.persianai.assistant.data.AccountingDB
import com.persianai.assistant.utils.AccountingManager
import kotlinx.coroutines.runBlocking
import com.persianai.assistant.data.Transaction as DBTransaction
import com.persianai.assistant.data.TransactionType
import com.persianai.assistant.utils.PersianDateConverter
import java.text.SimpleDateFormat
import java.util.*

/**
 * سیستم حسابداری ساده - منبع حقیقت: `AccountingDB` (SQLite)
 */
class FinanceManager(private val context: Context) {

    private val accountingDB: AccountingDB by lazy { AccountingDB(context) }
    private val accountingManager: AccountingManager by lazy { AccountingManager(context) }

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

    fun addTransaction(amount: Double, type: String, category: String, desc: String): String {
        val now = System.currentTimeMillis()
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
                val dbId = try { accountingManager.addTransaction(dbTransaction) } catch (_: Exception) { -1L }
                if (dbId > 0) return dbId.toString()
            }
        } catch (e: Exception) {
            android.util.Log.e("FinanceManager", "خطا در ذخیره در AccountingDB", e)
        }

        // fallback
        return UUID.randomUUID().toString()
    }

    fun getAllTransactions(): List<Transaction> {
        return try {
            accountingDB.getAllTransactions().map { dbTx ->
                Transaction(
                    id = dbTx.id.toString(),
                    amount = dbTx.amount,
                    type = if (dbTx.type == TransactionType.INCOME) "income" else "expense",
                    category = dbTx.category,
                    description = dbTx.description,
                    date = dbTx.date
                )
            }.sortedByDescending { it.date }
        } catch (e: Exception) {
            android.util.Log.e("FinanceManager", "خطا در خواندن تراکنش‌ها از AccountingDB", e)
            emptyList()
        }
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
        return try {
            accountingDB.getBalance()
        } catch (e: Exception) {
            android.util.Log.e("FinanceManager", "خطا در محاسبه تراز", e)
            0.0
        }
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
                try {
                    runBlocking { accountingManager.deleteTransaction(dbId) }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }
}