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
 * سیستم حسابداری ساده
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
        val id = UUID.randomUUID().toString()
        val transaction = Transaction(id, amount, type, category, desc, System.currentTimeMillis())
        
        // ذخیره در SharedPreferences
        val transactions = getAllTransactions().toMutableList()
        transactions.add(transaction)
        saveTransactions(transactions)
        
        // ذخیره همزمان در AccountingDB برای نمایش در لیست‌ها
        try {
            if (type == "income" || type == "expense") {
                val dbType = if (type == "income") TransactionType.INCOME else TransactionType.EXPENSE
                val dbTransaction = DBTransaction(
                    type = dbType,
                    amount = amount,
                    category = category,
                    description = desc,
                    date = transaction.date
                )
                accountingDB.addTransaction(dbTransaction)
            }
        } catch (e: Exception) {
            android.util.Log.e("FinanceManager", "خطا در ذخیره در AccountingDB", e)
        }
        
        return id
    }
    
    fun getAllTransactions(): List<Transaction> {
        val json = prefs.getString("transactions", "[]") ?: "[]"
        val array = JSONArray(json)
        val list = mutableListOf<Transaction>()
        
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(Transaction(
                obj.getString("id"),
                obj.getDouble("amount"),
                obj.getString("type"),
                obj.getString("category"),
                obj.getString("description"),
                obj.getLong("date")
            ))
        }
        
        return list.sortedByDescending { it.date }
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
    
    /**
     * Export all transactions to CSV format
     */
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
    
    fun getMonthlyReport(year: Int, month: Int): Pair<Double, Double> {
        val calendar = Calendar.getInstance()
        calendar.set(year, month - 1, 1, 0, 0, 0)
        val startTime = calendar.timeInMillis
        
        calendar.set(Calendar.DAY_OF_MONTH, calendar.getActualMaximum(Calendar.DAY_OF_MONTH))
        val endTime = calendar.timeInMillis
        
        val transactions = getAllTransactions().filter { it.date in startTime..endTime }
        
        var income = 0.0
        var expense = 0.0
        
        transactions.forEach {
            if (it.type == "income") income += it.amount
            else expense += it.amount
        }
        
        return Pair(income, expense)
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
            "income" to income.toLong().toString(),
            "expense" to expense.toLong().toString(),
            "total" to balance.toLong().toString()
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
            .joinToString("\n") { "• ${it.key}: ${it.value.toLong()}" }

        val balance = income - expense
        return buildString {
            append("بازه: ")
            append(timeRange)
            append("\n")
            append("درآمد: ")
            append(income.toLong())
            append("\n")
            append("هزینه: ")
            append(expense.toLong())
            append("\n")
            append("خالص: ")
            append(balance.toLong())
            if (top.isNotBlank()) {
                append("\n\nبیشترین هزینه‌ها:\n")
                append(top)
            }
        }
    }
    
    fun deleteTransaction(id: String) {
        val transactions = getAllTransactions().filter { it.id != id }
        saveTransactions(transactions)
    }
}
