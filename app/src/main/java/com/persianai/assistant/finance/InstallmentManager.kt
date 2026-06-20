package com.persianai.assistant.finance

import android.content.Context
import com.persianai.assistant.data.AccountingDB
import com.persianai.assistant.utils.PersianDateConverter
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

/**
 * مدیریت اقساط با هشدارهای هوشمند
 */
class InstallmentManager(private val context: Context) {
    
    data class Installment(
        val id: String,
        val title: String,
        val totalAmount: Double,
        val installmentAmount: Double,
        val totalInstallments: Int,
        val paidInstallments: Int,
        val startDate: Long,
        val paymentDay: Int,
        val recipient: String,
        val description: String,
        val alertDaysBefore: Int = 3,
        val autoRemind: Boolean = true
    ) {
        fun getFormattedStartDate(): String {
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = startDate
            val persianDate = PersianDateConverter.gregorianToPersian(
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH)
            )
            return persianDate.toReadableString()
        }
    }
    
    private val prefs = context.getSharedPreferences("installments", Context.MODE_PRIVATE)
    private val accountingDB = AccountingDB(context)
    private val accountingManager = com.persianai.assistant.utils.AccountingManager(context)
    
    fun addInstallment(
        title: String,
        totalAmount: Double,
        installmentAmount: Double,
        totalInstallments: Int,
        startDate: Long,
        paymentDay: Int,
        recipient: String,
        description: String,
        alertDaysBefore: Int = 3
    ): String {
        val id = UUID.randomUUID().toString()
        val installment = Installment(
            id, title, totalAmount, installmentAmount,
            totalInstallments, 0, startDate, paymentDay,
            recipient, description, alertDaysBefore, true
        )
        
        val installments = getAllInstallments().toMutableList()
        installments.add(installment)
        saveInstallments(installments)
        
        // Sync to AccountingDB - FIXED: Using proper model mapping
        try {
            val model = com.persianai.assistant.models.Installment(
                id = 0,
                title = title,
                totalAmount = totalAmount,
                monthlyAmount = installmentAmount,
                installmentCount = totalInstallments,
                paidInstallments = 0,
                paidAmount = 0.0,
                remainingAmount = totalAmount,
                nextPaymentDate = Date(startDate),
                status = com.persianai.assistant.models.InstallmentStatus.ACTIVE,
                description = description,
                lender = recipient
            )
            runBlocking {
                accountingManager.addInstallment(model)
            }
            android.util.Log.d("InstallmentManager", "Synced to AccountingDB: title=$title")
        } catch (e: Exception) {
            android.util.Log.e("InstallmentManager", "Error syncing to AccountingDB", e)
        }
        
        return id
    }
    
    fun getAllInstallments(): List<Installment> {
        val json = prefs.getString("installments", "[]") ?: "[]"
        val array = JSONArray(json)
        val list = mutableListOf<Installment>()
        
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(Installment(
                obj.getString("id"),
                obj.getString("title"),
                obj.getDouble("totalAmount"),
                obj.getDouble("installmentAmount"),
                obj.getInt("totalInstallments"),
                obj.getInt("paidInstallments"),
                obj.getLong("startDate"),
                obj.getInt("paymentDay"),
                obj.getString("recipient"),
                obj.getString("description"),
                obj.optInt("alertDaysBefore", 3),
                obj.optBoolean("autoRemind", true)
            ))
        }
        
        return list
    }
    
    fun getActiveInstallments(): List<Installment> {
        return getAllInstallments().filter {
            it.paidInstallments < it.totalInstallments
        }
    }
    
    fun getUpcomingPayments(daysAhead: Int = 7): List<Pair<Installment, Long>> {
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance()
        val upcoming = mutableListOf<Pair<Installment, Long>>()
        
        getActiveInstallments().forEach { installment ->
            val nextPaymentDate = calculateNextPaymentDate(installment)
            if (nextPaymentDate != null && nextPaymentDate <= now + (daysAhead * 24 * 60 * 60 * 1000L)) {
                upcoming.add(Pair(installment, nextPaymentDate))
            }
        }
        
        return upcoming.sortedBy { it.second }
    }
    
    fun calculateNextPaymentDate(installment: Installment): Long? {
        if (installment.paidInstallments >= installment.totalInstallments) {
            return null
        }
        
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = installment.startDate
        
        calendar.add(Calendar.MONTH, installment.paidInstallments)
        calendar.set(Calendar.DAY_OF_MONTH, installment.paymentDay)
        
        return calendar.timeInMillis
    }
    
    fun payInstallment(id: String): Boolean {
        val installment = getAllInstallments().firstOrNull { it.id == id } ?: return false
        if (installment.paidInstallments >= installment.totalInstallments) return false

        val installments = getAllInstallments().map {
            if (it.id == id) it.copy(paidInstallments = it.paidInstallments + 1) else it
        }
        saveInstallments(installments)

        FinanceManager(context).addTransaction(
            installment.installmentAmount,
            "expense",
            "قسط",
            "پرداخت قسط ${installment.paidInstallments + 1} از ${installment.totalInstallments}: ${installment.title}"
        )
        
        // Sync payment to AccountingDB
        try {
            val accInstallments = accountingManager.getAllInstallments()
            val titleToFind = installments.first { i -> i.id == id }.title
            accInstallments.firstOrNull { it.title == titleToFind }?.let {
                runBlocking {
                    accountingManager.payInstallment(it.id, installment.installmentAmount)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("InstallmentManager", "Error syncing payment to AccountingDB", e)
        }
        
        return true
    }
    
    fun getTotalRemainingAmount(): Double {
        return getActiveInstallments().sumOf { 
            (it.totalInstallments - it.paidInstallments) * it.installmentAmount
        }
    }
    
    fun getInstallmentsNeedingAlert(): List<Pair<Installment, Long>> {
        val now = System.currentTimeMillis()
        val needAlert = mutableListOf<Pair<Installment, Long>>()
        
        getActiveInstallments().forEach { installment ->
            if (!installment.autoRemind) return@forEach
            
            val nextPaymentDate = calculateNextPaymentDate(installment) ?: return@forEach
            val alertTime = nextPaymentDate - (installment.alertDaysBefore * 24 * 60 * 60 * 1000L)
            
            if (now >= alertTime && now < nextPaymentDate) {
                needAlert.add(Pair(installment, nextPaymentDate))
            }
        }
        
        return needAlert
    }
    
    fun deleteInstallment(id: String) {
        val allInstallments = getAllInstallments()
        val deletedInstallment = allInstallments.firstOrNull { it.id == id } ?: return
        saveInstallments(allInstallments.filter { it.id != id })

        try {
            accountingManager.getAllInstallments()
                .firstOrNull {
                    it.title == deletedInstallment.title &&
                        it.totalAmount == deletedInstallment.totalAmount
                }
                ?.let { runBlocking { accountingManager.deleteInstallment(it.id) } }
        } catch (e: Exception) {
            android.util.Log.e("InstallmentManager", "Error syncing deletion to AccountingDB", e)
        }
    }
    
    /**
     * Export all installments as CSV
     */
    fun exportToCSV(): String {
        val sb = StringBuilder()
        sb.appendLine("ID,عنوان,مبلغ کل,مبلغ هر قسط,تعداد کل اقساط,قسط پرداخت شده,تاریخ شروع,روز پرداخت,دریافت‌کننده,توضیحات")
        getAllInstallments().forEach { inst ->
            sb.appendLine("${inst.id},${inst.title},${inst.totalAmount},${inst.installmentAmount},${inst.totalInstallments},${inst.paidInstallments},${inst.startDate},${inst.paymentDay},${inst.recipient},${inst.description}")
        }
        return sb.toString()
    }
    
    private fun saveInstallments(installments: List<Installment>) {
        val array = JSONArray()
        installments.forEach { i ->
            array.put(JSONObject().apply {
                put("id", i.id)
                put("title", i.title)
                put("totalAmount", i.totalAmount)
                put("installmentAmount", i.installmentAmount)
                put("totalInstallments", i.totalInstallments)
                put("paidInstallments", i.paidInstallments)
                put("startDate", i.startDate)
                put("paymentDay", i.paymentDay)
                put("recipient", i.recipient)
                put("description", i.description)
                put("alertDaysBefore", i.alertDaysBefore)
                put("autoRemind", i.autoRemind)
            })
        }
        prefs.edit().putString("installments", array.toString()).apply()
    }

    fun importInstallments(installments: List<Installment>) {
        saveInstallments(installments)
    }

    fun updateInstallment(
        id: String,
        title: String,
        totalAmount: Double,
        installmentAmount: Double,
        totalInstallments: Int,
        startDate: Long,
        paymentDay: Int,
        recipient: String,
        description: String
    ): Boolean {
        val all = getAllInstallments()
        val existing = all.firstOrNull { it.id == id } ?: return false
        if (totalInstallments < existing.paidInstallments) return false

        val updated = existing.copy(
            title = title,
            totalAmount = totalAmount,
            installmentAmount = installmentAmount,
            totalInstallments = totalInstallments,
            startDate = startDate,
            paymentDay = paymentDay,
            recipient = recipient,
            description = description
        )
        saveInstallments(all.map { if (it.id == id) updated else it })
        return true
    }

    fun generateReport(): String {
        val all = getAllInstallments()
        if (all.isEmpty()) return "📭 هیچ قسطی ثبت نشده است."

        val active = all.filter { it.paidInstallments < it.totalInstallments }
        val completed = all.size - active.size
        val totalRemaining = getTotalRemainingAmount()
        val totalPaid = all.sumOf { it.paidInstallments * it.installmentAmount }
        val now = System.currentTimeMillis()
        val overdue = active.count { hasOverduePayment(it, now) }
        val upcoming = getUpcomingPayments(7)

        return buildString {
            appendLine("📊 گزارش اقساط")
            appendLine("=".repeat(28))
            appendLine("تعداد کل: ${all.size}")
            appendLine("فعال: ${active.size} | تکمیل‌شده: $completed")
            appendLine("پرداخت شده: ${String.format("%,.0f", totalPaid)} تومان")
            appendLine("باقیمانده: ${String.format("%,.0f", totalRemaining)} تومان")
            if (overdue > 0) appendLine("❌ عقب‌افتاده: $overdue مورد")
            if (upcoming.isNotEmpty()) {
                appendLine("\n⏰ سررسید ۷ روز آینده:")
                upcoming.take(5).forEach { (inst, due) ->
                    val days = ((due - now) / (24 * 60 * 60 * 1000)).toInt()
                    appendLine("• ${inst.title}: $days روز دیگر (${String.format("%,.0f", inst.installmentAmount)} تومان)")
                }
            }
        }
    }

    private fun hasOverduePayment(installment: Installment, now: Long): Boolean {
        val next = calculateNextPaymentDate(installment) ?: return false
        return next < now
    }
}