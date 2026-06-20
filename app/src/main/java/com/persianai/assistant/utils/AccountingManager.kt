package com.persianai.assistant.utils

import android.content.Context
import com.persianai.assistant.data.AccountingDB
import com.persianai.assistant.data.Transaction as DBTransaction
import com.persianai.assistant.data.TransactionType as DBTransactionType
import com.persianai.assistant.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * مدیر حسابداری حرفه‌ای — اکنون از SQLite (`AccountingDB`) به‌عنوان منبع داده استفاده می‌کند.
 */
class AccountingManager(private val context: Context) {

    private val accountingDB: AccountingDB by lazy { AccountingDB(context) }
    private val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())

    // مدیریت تراکنش‌ها
    suspend fun addTransaction(transaction: FinancialTransaction): Boolean = withContext(Dispatchers.IO) {
        try {
            val dbType = when (transaction.type) {
                TransactionType.INCOME -> DBTransactionType.INCOME
                TransactionType.EXPENSE -> DBTransactionType.EXPENSE
                else -> DBTransactionType.EXPENSE
            }

            val dbTransaction = DBTransaction(
                id = 0,
                type = dbType,
                amount = transaction.amount,
                category = transaction.category.name,
                description = transaction.description,
                date = transaction.date.time
            )

            accountingDB.addTransaction(dbTransaction)

            if (transaction.isRecurring) createRecurringReminder(transaction)

            true
        } catch (e: Exception) {
            false
        }
    }

    // Compatibility overload: accept DB `Transaction` directly
    fun addTransaction(transaction: com.persianai.assistant.data.Transaction): Long {
        return try {
            accountingDB.addTransaction(transaction)
        } catch (e: Exception) {
            -1L
        }
    }

    suspend fun updateTransaction(transaction: FinancialTransaction): Boolean = withContext(Dispatchers.IO) {
        try {
            val dbType = when (transaction.type) {
                TransactionType.INCOME -> DBTransactionType.INCOME
                TransactionType.EXPENSE -> DBTransactionType.EXPENSE
                else -> DBTransactionType.EXPENSE
            }

            val dbTransaction = DBTransaction(
                id = transaction.id,
                type = dbType,
                amount = transaction.amount,
                category = transaction.category.name,
                description = transaction.description,
                date = transaction.date.time
            )

            accountingDB.updateTransaction(dbTransaction)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun deleteTransaction(transactionId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            accountingDB.deleteTransaction(transactionId)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun getAllTransactions(): List<FinancialTransaction> {
        return try {
            accountingDB.getAllTransactions().map { dbTx ->
                val type = when (dbTx.type) {
                    DBTransactionType.INCOME -> TransactionType.INCOME
                    DBTransactionType.EXPENSE -> TransactionType.EXPENSE
                    else -> TransactionType.TRANSFER
                }

                val category = try {
                    TransactionCategory.valueOf(dbTx.category)
                } catch (_: Exception) {
                    if (type == TransactionType.INCOME) TransactionCategory.OTHER_INCOME else TransactionCategory.OTHER_EXPENSE
                }

                FinancialTransaction(
                    id = dbTx.id,
                    type = type,
                    category = category,
                    amount = dbTx.amount,
                    description = dbTx.description ?: "",
                    date = Date(dbTx.date),
                    tags = emptyList(),
                    isRecurring = false,
                    recurringInterval = null,
                    attachments = emptyList()
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getTransactionsByCategory(category: TransactionCategory): List<FinancialTransaction> {
        return getAllTransactions().filter { it.category == category }
    }

    fun getTransactionsByDateRange(startDate: Date, endDate: Date): List<FinancialTransaction> {
        return getAllTransactions().filter { it.date >= startDate && it.date <= endDate }
    }

    // مدیریت چک‌ها
    suspend fun addCheck(check: Check): Boolean = withContext(Dispatchers.IO) {
        try {
            accountingDB.addCheck(check)
            createCheckReminder(check)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun updateCheck(check: Check): Boolean = withContext(Dispatchers.IO) {
        try {
            accountingDB.updateCheck(check)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun deleteCheck(checkId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            accountingDB.deleteCheck(checkId)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun getAllChecks(): List<Check> {
        return try {
            accountingDB.getAllChecks()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getPendingChecks(): List<Check> {
        return getAllChecks().filter { it.status == CheckStatus.PENDING }
    }

    // مدیریت اقساط
    suspend fun addInstallment(installment: Installment): Boolean = withContext(Dispatchers.IO) {
        try {
            accountingDB.addInstallment(installment)
            createInstallmentReminder(installment)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun updateInstallment(installment: Installment): Boolean = withContext(Dispatchers.IO) {
        try {
            accountingDB.updateInstallment(installment)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun payInstallment(installmentId: Long, amount: Double): Boolean = withContext(Dispatchers.IO) {
        try {
            accountingDB.payInstallment(installmentId)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun deleteInstallment(installmentId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            accountingDB.deleteInstallment(installmentId)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun getAllInstallments(): List<Installment> {
        return try {
            accountingDB.getAllInstallments()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getActiveInstallments(): List<Installment> {
        return getAllInstallments().filter { it.status == InstallmentStatus.ACTIVE }
    }

    fun getDelayedInstallments(): List<Installment> {
        val today = Date()
        return getAllInstallments().filter { it.status == InstallmentStatus.ACTIVE && it.nextPaymentDate < today }
    }

    // آمار و گزارش‌ها
    suspend fun getFinancialStatistics(): FinancialStatistics = withContext(Dispatchers.IO) {
        val transactions = getAllTransactions()
        val currentMonth = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }.time

        val monthlyTransactions = transactions.filter { it.date >= currentMonth }

        val totalIncome = monthlyTransactions.filter { it.type == TransactionType.INCOME }.sumOf { it.amount }
        val totalExpense = monthlyTransactions.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amount }
        val balance = totalIncome - totalExpense

        FinancialStatistics(
            totalIncome = totalIncome,
            totalExpense = totalExpense,
            balance = balance,
            transactionCount = monthlyTransactions.size,
            averageExpense = if (monthlyTransactions.isNotEmpty()) totalExpense / monthlyTransactions.size else 0.0
        )
    }

    suspend fun getMonthlyReport(): FinancialReport = withContext(Dispatchers.IO) {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        val startDate = calendar.time

        calendar.add(Calendar.MONTH, 1)
        calendar.add(Calendar.DAY_OF_MONTH, -1)
        val endDate = calendar.time

        generateReport(startDate, endDate, ReportPeriod.MONTHLY)
    }

    suspend fun getYearlyReport(): FinancialReport = withContext(Dispatchers.IO) {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.DAY_OF_YEAR, 1)
        val startDate = calendar.time

        calendar.add(Calendar.YEAR, 1)
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        val endDate = calendar.time

        generateReport(startDate, endDate, ReportPeriod.YEARLY)
    }

    // export و پشتیبان
    suspend fun exportToCSV(): String = withContext(Dispatchers.IO) {
        val transactions = getAllTransactions()
        val file = File(context.getExternalFilesDir(null), "financial_export.csv")

        FileWriter(file).use { writer ->
            // نوشتن هدر
            writer.appendLine("تاریخ,نوع,دسته‌بندی,مبلغ,توضیحات")

            // نوشتن تراکنش‌ها
            transactions.forEach { transaction ->
                val line = "${dateFormat.format(transaction.date)}," +
                        "${transaction.type}," +
                        "${transaction.category}," +
                        "${transaction.amount}," +
                        "\"${transaction.description}\""
                writer.appendLine(line)
            }
        }

        file.absolutePath
    }

    suspend fun createBackup(): String = withContext(Dispatchers.IO) {
        val backupData = AccountingBackup(
            transactions = getAllTransactions(),
            checks = getAllChecks(),
            installments = getAllInstallments(),
            exportDate = Date()
        )

        val gson = com.google.gson.Gson()
        val json = gson.toJson(backupData)
        val file = File(context.getExternalFilesDir(null), "accounting_backup.json")

        file.writeText(json)
        file.absolutePath
    }

    // توابع کمکی
    private fun generateReport(startDate: Date, endDate: Date, period: ReportPeriod): FinancialReport {
        val transactions = getTransactionsByDateRange(startDate, endDate)

        val totalIncome = transactions.filter { it.type == TransactionType.INCOME }.sumOf { it.amount }
        val totalExpense = transactions.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amount }
        val netIncome = totalIncome - totalExpense

        val categoryBreakdown = transactions.groupBy { it.category }
            .mapValues { it.value.sumOf { transaction -> transaction.amount } }

        val topExpenses = transactions.filter { it.type == TransactionType.EXPENSE }
            .sortedByDescending { it.amount }.take(10)

        val topIncome = transactions.filter { it.type == TransactionType.INCOME }
            .sortedByDescending { it.amount }.take(10)

        return FinancialReport(
            period = period,
            startDate = startDate,
            endDate = endDate,
            totalIncome = totalIncome,
            totalExpense = totalExpense,
            netIncome = netIncome,
            categoryBreakdown = categoryBreakdown,
            monthlyTrend = emptyList(), // TODO: پیاده‌سازی ترند ماهانه
            topExpenses = topExpenses,
            topIncome = topIncome
        )
    }

    private fun createRecurringReminder(transaction: FinancialTransaction) {
        // TODO: پیاده‌سازی یادآوری تراکنش تکراری
    }

    private fun createCheckReminder(check: Check) {
        // TODO: پیاده‌سازی یادآوری چک
    }

    private fun createInstallmentReminder(installment: Installment) {
        // TODO: پیاده‌سازی یادآوری قسط
    }
}

// مدل‌های کمکی
data class FinancialStatistics(
    val totalIncome: Double,
    val totalExpense: Double,
    val balance: Double,
    val transactionCount: Int,
    val averageExpense: Double
)

data class AccountingBackup(
    val transactions: List<FinancialTransaction>,
    val checks: List<Check>,
    val installments: List<Installment>,
    val exportDate: Date
)
