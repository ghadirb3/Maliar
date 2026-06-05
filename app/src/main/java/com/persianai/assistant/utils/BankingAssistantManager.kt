package com.persianai.assistant.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.persianai.assistant.data.Check
import com.persianai.assistant.utils.FormatUtils
import com.persianai.assistant.data.Installment
import java.util.concurrent.TimeUnit

/**
 * مدیریت امور بانکی و مالی
 * شامل: چک‌ها، اقساط، هشدارها، نظارت بر تراکنش‌های مشکوک
 */
class BankingAssistantManager(private val context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences("banking_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    
    companion object {
        private const val TAG = "BankingAssistant"
        private const val KEY_CHECKS = "checks"
        private const val KEY_INSTALLMENTS = "installments"
        private const val KEY_SUSPICIOUS_TRANSACTIONS = "suspicious_transactions"
    }
    
    /**
     * ثبت چک جدید
     */
    fun addCheck(
        amount: Long,
        dueDate: Long,
        recipient: String,
        bankName: String,
        checkNumber: String,
        notes: String = ""
    ): Check {
        val check = Check(
            id = System.currentTimeMillis().toString(),
            amount = amount,
            dueDate = dueDate,
            recipient = recipient,
            bankName = bankName,
            checkNumber = checkNumber,
            notes = notes,
            isPaid = false,
            createdAt = System.currentTimeMillis()
        )
        
        val checks = getAllChecks().toMutableList()
        checks.add(check)
        saveChecks(checks)
        
        // تنظیم هشدار
        scheduleCheckReminder(check)
        
        Log.i(TAG, "✅ چک جدید ثبت شد: $checkNumber - مبلغ ${formatAmount(amount)} تومان")
        
        return check
    }
    
    /**
     * ثبت قسط جدید
     */
    fun addInstallment(
        title: String,
        totalAmount: Long,
        monthlyAmount: Long,
        startDate: Long,
        totalMonths: Int,
        currentMonth: Int = 1,
        creditor: String = "",
        notes: String = ""
    ): Installment {
        val installment = Installment(
            id = System.currentTimeMillis().toString(),
            title = title,
            totalAmount = totalAmount,
            monthlyAmount = monthlyAmount,
            startDate = startDate,
            totalMonths = totalMonths,
            currentMonth = currentMonth,
            creditor = creditor,
            notes = notes,
            isCompleted = false,
            createdAt = System.currentTimeMillis()
        )
        
        val installments = getAllInstallments().toMutableList()
        installments.add(installment)
        saveInstallments(installments)
        
        // تنظیم هشدار
        scheduleInstallmentReminder(installment)
        
        Log.i(TAG, "✅ قسط جدید ثبت شد: $title - ${formatAmount(monthlyAmount)} تومان/$totalMonths ماه")
        
        return installment
    }
    
    /**
     * دریافت همه چک‌ها
     */
    fun getAllChecks(): List<Check> {
        val json = prefs.getString(KEY_CHECKS, "[]") ?: "[]"
        val type = object : TypeToken<List<Check>>() {}.type
        return gson.fromJson(json, type)
    }
    
    /**
     * دریافت چک‌های سررسید نزدیک (7 روز آینده)
     */
    fun getUpcomingChecks(): List<Check> {
        val now = System.currentTimeMillis()
        val sevenDaysLater = now + (7 * 24 * 60 * 60 * 1000)
        
        return getAllChecks()
            .filter { !it.isPaid && it.dueDate in now..sevenDaysLater }
            .sortedBy { it.dueDate }
    }
    
    /**
     * دریافت چک‌های سررسید گذشته
     */
    fun getOverdueChecks(): List<Check> {
        val now = System.currentTimeMillis()
        
        return getAllChecks()
            .filter { !it.isPaid && it.dueDate < now }
            .sortedBy { it.dueDate }
    }
    
    /**
     * دریافت همه اقساط
     */
    fun getAllInstallments(): List<Installment> {
        val json = prefs.getString(KEY_INSTALLMENTS, "[]") ?: "[]"
        val type = object : TypeToken<List<Installment>>() {}.type
        return gson.fromJson(json, type)
    }
    
    /**
     * دریافت اقساط فعال
     */
    fun getActiveInstallments(): List<Installment> {
        return getAllInstallments().filter { !it.isCompleted }
    }
    
    /**
     * دریافت اقساط سررسید نزدیک
     */
    fun getUpcomingInstallments(): List<Installment> {
        val now = System.currentTimeMillis()
        val thirtyDaysLater = now + (30 * 24 * 60 * 60 * 1000)
        
        return getActiveInstallments()
            .filter {
                val nextPaymentDate = calculateNextPaymentDate(it)
                nextPaymentDate in now..thirtyDaysLater
            }
            .sortedBy { calculateNextPaymentDate(it) }
    }
    
    /**
     * پرداخت چک
     */
    fun markCheckAsPaid(checkId: String): Boolean {
        val checks = getAllChecks().toMutableList()
        val index = checks.indexOfFirst { it.id == checkId }
        
        if (index != -1) {
            checks[index] = checks[index].copy(isPaid = true)
            saveChecks(checks)
            
            Log.i(TAG, "✅ چک $checkId پرداخت شد")
            return true
        }
        
        return false
    }
    
    /**
     * پرداخت قسط
     */
    fun payInstallment(installmentId: String): Boolean {
        val installments = getAllInstallments().toMutableList()
        val index = installments.indexOfFirst { it.id == installmentId }
        
        if (index != -1) {
            val installment = installments[index]
            val newCurrentMonth = installment.currentMonth + 1
            
            if (newCurrentMonth > installment.totalMonths) {
                // قسط تمام شد
                installments[index] = installment.copy(
                    currentMonth = newCurrentMonth,
                    isCompleted = true
                )
            } else {
                installments[index] = installment.copy(
                    currentMonth = newCurrentMonth
                )
            }
            
            saveInstallments(installments)
            
            Log.i(TAG, "✅ قسط $installmentId پرداخت شد (${newCurrentMonth}/${installment.totalMonths})")
            return true
        }
        
        return false
    }
    
    /**
     * حذف چک
     */
    fun deleteCheck(checkId: String): Boolean {
        val checks = getAllChecks().toMutableList()
        val removed = checks.removeIf { it.id == checkId }
        
        if (removed) {
            saveChecks(checks)
            Log.i(TAG, "🗑️ چک $checkId حذف شد")
        }
        
        return removed
    }
    
    /**
     * حذف قسط
     */
    fun deleteInstallment(installmentId: String): Boolean {
        val installments = getAllInstallments().toMutableList()
        val removed = installments.removeIf { it.id == installmentId }
        
        if (removed) {
            saveInstallments(installments)
            Log.i(TAG, "🗑️ قسط $installmentId حذف شد")
        }
        
        return removed
    }
    
    /**
     * محاسبه تاریخ پرداخت بعدی قسط
     */
    private fun calculateNextPaymentDate(installment: Installment): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = installment.startDate
        calendar.add(java.util.Calendar.MONTH, installment.currentMonth)
        return calendar.timeInMillis
    }
    
    /**
     * ذخیره چک‌ها
     */
    private fun saveChecks(checks: List<Check>) {
        val json = gson.toJson(checks)
        prefs.edit().putString(KEY_CHECKS, json).apply()
    }
    
    /**
     * ذخیره اقساط
     */
    private fun saveInstallments(installments: List<Installment>) {
        val json = gson.toJson(installments)
        prefs.edit().putString(KEY_INSTALLMENTS, json).apply()
    }
    
    /**
     * تنظیم هشدار برای چک
     */
    private fun scheduleCheckReminder(check: Check) {
        val threeDaysBefore = check.dueDate - (3 * 24 * 60 * 60 * 1000)
        val now = System.currentTimeMillis()
        
        if (threeDaysBefore > now) {
            val delay = threeDaysBefore - now
            
            val data = Data.Builder()
                .putString("type", "check")
                .putString("checkId", check.id)
                .putString("recipient", check.recipient)
                .putLong("amount", check.amount)
                .putString("checkNumber", check.checkNumber)
                .build()
            
            val workRequest = OneTimeWorkRequestBuilder<CheckReminderWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(data)
                .addTag("check_reminder_${check.id}")
                .build()
            
            WorkManager.getInstance(context).enqueue(workRequest)
            
            Log.d(TAG, "🔔 هشدار چک تنظیم شد: ${check.checkNumber}")
        }
    }
    
    /**
     * تنظیم هشدار برای قسط
     */
    private fun scheduleInstallmentReminder(installment: Installment) {
        val nextPaymentDate = calculateNextPaymentDate(installment)
        val threeDaysBefore = nextPaymentDate - (3 * 24 * 60 * 60 * 1000)
        val now = System.currentTimeMillis()
        
        if (threeDaysBefore > now) {
            val delay = threeDaysBefore - now
            
            val data = Data.Builder()
                .putString("type", "installment")
                .putString("installmentId", installment.id)
                .putString("title", installment.title)
                .putLong("amount", installment.monthlyAmount)
                .putInt("currentMonth", installment.currentMonth)
                .putInt("totalMonths", installment.totalMonths)
                .build()
            
            val workRequest = OneTimeWorkRequestBuilder<InstallmentReminderWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(data)
                .addTag("installment_reminder_${installment.id}")
                .build()
            
            WorkManager.getInstance(context).enqueue(workRequest)
            
            Log.d(TAG, "🔔 هشدار قسط تنظیم شد: ${installment.title}")
        }
    }
    
    /**
     * تشخیص تراکنش‌های مشکوک
     * در نسخه آینده: اتصال به API بانک یا دریافت SMS بانکی
     */
    fun detectSuspiciousTransaction(
        amount: Long,
        description: String,
        timestamp: Long
    ): Boolean {
        // قوانین ساده برای شناسایی تراکنش مشکوک:
        // 1. مبلغ بالای 50 میلیون تومان
        // 2. تراکنش در ساعات غیرمعمول (2 صبح تا 6 صبح)
        // 3. تراکنش‌های پی در پی با فاصله کم
        
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = timestamp
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        
        val isSuspicious = amount > 50_000_000 || hour in 2..6
        
        if (isSuspicious) {
            saveSuspiciousTransaction(amount, description, timestamp)
            sendSuspiciousTransactionAlert(amount, description)
            Log.w(TAG, "⚠️ تراکنش مشکوک شناسایی شد: ${formatAmount(amount)} - $description")
        }
        
        return isSuspicious
    }
    
    /**
     * ذخیره تراکنش مشکوک
     */
    private fun saveSuspiciousTransaction(amount: Long, description: String, timestamp: Long) {
        val json = prefs.getString(KEY_SUSPICIOUS_TRANSACTIONS, "[]") ?: "[]"
        val type = object : TypeToken<MutableList<SuspiciousTransaction>>() {}.type
        val transactions: MutableList<SuspiciousTransaction> = gson.fromJson(json, type)
        
        transactions.add(
            SuspiciousTransaction(
                id = System.currentTimeMillis().toString(),
                amount = amount,
                description = description,
                timestamp = timestamp,
                reviewed = false
            )
        )
        
        prefs.edit().putString(KEY_SUSPICIOUS_TRANSACTIONS, gson.toJson(transactions)).apply()
    }
    
    /**
     * ارسال هشدار تراکنش مشکوک
     */
    private fun sendSuspiciousTransactionAlert(amount: Long, description: String) {
        NotificationHelper.showGeneralNotification(
            context,
            title = "⚠️ هشدار: تراکنش مشکوک",
            message = "تراکنش ${formatAmount(amount)} تومان\n$description"
        )
    }
    
    private fun formatAmount(amount: Long): String = FormatUtils.formatMoney(amount)
    
    /**
     * دریافت گزارش مالی
     */
    fun getFinancialReport(): FinancialReport {
        val checks = getAllChecks()
        val installments = getAllInstallments()
        
        val totalCheckAmount = checks.filter { !it.isPaid }.sumOf { it.amount }
        val totalInstallmentAmount = installments.filter { !it.isCompleted }
            .sumOf { (it.totalMonths - it.currentMonth + 1) * it.monthlyAmount }
        
        val upcomingChecks = getUpcomingChecks()
        val overdueChecks = getOverdueChecks()
        val upcomingInstallments = getUpcomingInstallments()
        
        return FinancialReport(
            totalCheckAmount = totalCheckAmount,
            totalInstallmentAmount = totalInstallmentAmount,
            upcomingChecksCount = upcomingChecks.size,
            overdueChecksCount = overdueChecks.size,
            activeInstallmentsCount = getActiveInstallments().size,
            upcomingInstallmentsCount = upcomingInstallments.size
        )
    }
    
    data class SuspiciousTransaction(
        val id: String,
        val amount: Long,
        val description: String,
        val timestamp: Long,
        val reviewed: Boolean
    )
    
    data class FinancialReport(
        val totalCheckAmount: Long,
        val totalInstallmentAmount: Long,
        val upcomingChecksCount: Int,
        val overdueChecksCount: Int,
        val activeInstallmentsCount: Int,
        val upcomingInstallmentsCount: Int
    )
}

/**
 * Worker برای هشدار چک
 */
class CheckReminderWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    
    override fun doWork(): Result {
        val checkNumber = inputData.getString("checkNumber") ?: ""
        val recipient = inputData.getString("recipient") ?: ""
        val amount = inputData.getLong("amount", 0)
        
        NotificationHelper.showReminderNotification(
            applicationContext,
            title = "🔔 یادآوری سررسید چک",
            message = "چک شماره $checkNumber\nگیرنده: $recipient\nمبلغ: ${String.format("%,d", amount)} تومان\n\n3 روز تا سررسید باقی مانده"
        )
        
        return Result.success()
    }
}

/**
 * Worker برای هشدار قسط
 */
class InstallmentReminderWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    
    override fun doWork(): Result {
        val title = inputData.getString("title") ?: ""
        val amount = inputData.getLong("amount", 0)
        val currentMonth = inputData.getInt("currentMonth", 0)
        val totalMonths = inputData.getInt("totalMonths", 0)
        
        NotificationHelper.showReminderNotification(
            applicationContext,
            title = "🔔 یادآوری پرداخت قسط",
            message = "$title\nمبلغ: ${String.format("%,d", amount)} تومان\nقسط $currentMonth از $totalMonths\n\n3 روز تا موعد پرداخت باقی مانده"
        )
        
        return Result.success()
    }
}
