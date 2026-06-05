package com.persianai.assistant.finance

import android.content.Context
import android.util.Log
import com.persianai.assistant.data.AccountingDB
import com.persianai.assistant.utils.PersianDateConverter
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

/**
 * مدیریت اقساط با هشدارهای هوشمند
 */
class InstallmentManager(private val context: Context) {
    
    companion object {
        private const val TAG = "InstallmentManager"
    }

    data class Installment(
        val id: String,
        val title: String, // عنوان (مثلاً: قسط ماشین، قسط خانه)
        val totalAmount: Double, // مبلغ کل
        val installmentAmount: Double, // مبلغ هر قسط
        val totalInstallments: Int, // تعداد کل اقساط
        val paidInstallments: Int, // تعداد اقساط پرداخت شده
        val startDate: Long, // تاریخ شروع
        val paymentDay: Int, // روز پرداخت در ماه (1-31)
        val recipient: String, // دریافت کننده
        val description: String,
        val alertDaysBefore: Int = 3, // چند روز قبل هشدار بده
        val autoRemind: Boolean = true // یادآوری خودکار
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
        
        // TODO: Sync to AccountingDB once model structures are aligned
        
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
            return null // همه اقساط پرداخت شده
        }
        
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = installment.startDate
        
        // اضافه کردن ماه‌های پرداخت شده
        calendar.add(Calendar.MONTH, installment.paidInstallments)
        
        // تنظیم روز پرداخت
        calendar.set(Calendar.DAY_OF_MONTH, installment.paymentDay)
        
        return calendar.timeInMillis
    }
    
    fun payInstallment(id: String): Boolean {
        val installments = getAllInstallments().map {
            if (it.id == id && it.paidInstallments < it.totalInstallments) {
                it.copy(paidInstallments = it.paidInstallments + 1)
            } else {
                it
            }
        }
        saveInstallments(installments)
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
        val installments = getAllInstallments().filter { it.id != id }
        saveInstallments(installments)
        // TODO: Sync deletion to AccountingDB once id types are aligned
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
}
