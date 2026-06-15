package com.persianai.assistant.finance

import android.content.Context
import com.persianai.assistant.data.AccountingDB
import com.persianai.assistant.utils.PersianDateConverter
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * مدیریت چک‌ها با هشدارهای هوشمند
 */
class CheckManager(private val context: Context) {
    
    data class Check(
        val id: String,
        val checkNumber: String,
        val amount: Double,
        val issuer: String,
        val recipient: String,
        val issueDate: Long,
        val dueDate: Long,
        val status: CheckStatus,
        val bankName: String,
        val accountNumber: String,
        val description: String,
        val alertDays: Int = 7
    ) {
        fun getFormattedDueDate(): String {
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = dueDate
            val persianDate = PersianDateConverter.gregorianToPersian(
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH)
            )
            return persianDate.toReadableString()
        }
    }
    
    enum class CheckStatus {
        PENDING,
        PAID,
        BOUNCED,
        CANCELLED
    }
    
    private val prefs = context.getSharedPreferences("checks", Context.MODE_PRIVATE)
    private val accountingDB = AccountingDB(context)
    
    fun addCheck(
        checkNumber: String,
        amount: Double,
        issuer: String,
        recipient: String,
        issueDate: Long,
        dueDate: Long,
        bankName: String,
        accountNumber: String,
        description: String,
        alertDays: Int = 7
    ): String {
        val id = UUID.randomUUID().toString()
        val check = Check(
            id, checkNumber, amount, issuer, recipient,
            issueDate, dueDate, CheckStatus.PENDING,
            bankName, accountNumber, description, alertDays
        )
        
        val checks = getAllChecks().toMutableList()
        checks.add(check)
        saveChecks(checks)
        
        // Sync to AccountingDB - FIXED: Using proper model mapping
        try {
            accountingDB.addCheck(com.persianai.assistant.models.Check(
                checkNumber = checkNumber,
                amount = amount,
                recipient = recipient,
                issueDate = Date(issueDate),
                dueDate = Date(dueDate),
                status = com.persianai.assistant.models.CheckStatus.PENDING,
                description = description,
                bankName = bankName
            ))
        } catch (e: Exception) {
            android.util.Log.e("CheckManager", "Error syncing to AccountingDB", e)
        }
        
        return id
    }
    
    fun getAllChecks(): List<Check> {
        val json = prefs.getString("checks", "[]") ?: "[]"
        val array = JSONArray(json)
        val list = mutableListOf<Check>()
        
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(Check(
                obj.getString("id"),
                obj.getString("checkNumber"),
                obj.getDouble("amount"),
                obj.getString("issuer"),
                obj.getString("recipient"),
                obj.getLong("issueDate"),
                obj.getLong("dueDate"),
                CheckStatus.valueOf(obj.getString("status")),
                obj.getString("bankName"),
                obj.getString("accountNumber"),
                obj.getString("description"),
                obj.optInt("alertDays", 7)
            ))
        }
        
        return list.sortedBy { it.dueDate }
    }
    
    fun getUpcomingChecks(daysAhead: Int = 30): List<Check> {
        val now = System.currentTimeMillis()
        val future = now + (daysAhead * 24 * 60 * 60 * 1000L)
        
        return getAllChecks().filter {
            it.status == CheckStatus.PENDING && it.dueDate in now..future
        }
    }
    
    fun getChecksNeedingAlert(): List<Check> {
        val now = System.currentTimeMillis()
        
        return getAllChecks().filter {
            it.status == CheckStatus.PENDING &&
            (it.dueDate - now) <= (it.alertDays * 24 * 60 * 60 * 1000L)
        }
    }
    
    fun updateCheckStatus(id: String, status: CheckStatus) {
        val checks = getAllChecks().map {
            if (it.id == id) it.copy(status = status) else it
        }
        saveChecks(checks)
        
        // Sync status update to AccountingDB
        try {
            val accChecks = accountingDB.getAllChecks()
            val check = checks.first { it.id == id }
            accChecks.firstOrNull { it.checkNumber == check.checkNumber }?.let {
                accountingDB.updateCheckStatus(
                    it.id,
                    when (status) {
                        CheckStatus.PAID -> com.persianai.assistant.models.CheckStatus.DEPOSITED
                        CheckStatus.BOUNCED -> com.persianai.assistant.models.CheckStatus.BOUNCED
                        CheckStatus.CANCELLED -> com.persianai.assistant.models.CheckStatus.CANCELLED
                        else -> com.persianai.assistant.models.CheckStatus.PENDING
                    }
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("CheckManager", "Error syncing status to AccountingDB", e)
        }
    }
    
    fun deleteCheck(id: String) {
        val checks = getAllChecks().filter { it.id != id }
        saveChecks(checks)
        
        // Sync deletion to AccountingDB
        try {
            val deletedCheck = getAllChecks().firstOrNull() ?: return
            val accChecks = accountingDB.getAllChecks()
            accChecks.firstOrNull { it.checkNumber == deletedCheck.checkNumber }?.let {
                accountingDB.deleteCheck(it.id)
            }
        } catch (e: Exception) {
            android.util.Log.e("CheckManager", "Error syncing deletion to AccountingDB", e)
        }
    }
    
    /**
     * Export all checks as CSV
     */
    fun exportToCSV(): String {
        val sb = StringBuilder()
        sb.appendLine("ID,شماره چک,مبلغ,صادرکننده,دریافت‌کننده,تاریخ صدور,تاریخ سررسید,وضعیت,بانک,شماره حساب,توضیحات")
        getAllChecks().forEach { c ->
            sb.appendLine("${c.id},\"${c.checkNumber}\",${c.amount},\"${c.issuer}\",\"${c.recipient}\",${c.issueDate},${c.dueDate},${c.status.name},\"${c.bankName}\",\"${c.accountNumber}\",\"${c.description}\"")
        }
        return sb.toString()
    }
    
    private fun saveChecks(checks: List<Check>) {
        val array = JSONArray()
        checks.forEach { c ->
            array.put(JSONObject().apply {
                put("id", c.id)
                put("checkNumber", c.checkNumber)
                put("amount", c.amount)
                put("issuer", c.issuer)
                put("recipient", c.recipient)
                put("issueDate", c.issueDate)
                put("dueDate", c.dueDate)
                put("status", c.status.name)
                put("bankName", c.bankName)
                put("accountNumber", c.accountNumber)
                put("description", c.description)
                put("alertDays", c.alertDays)
            })
        }
        prefs.edit().putString("checks", array.toString()).apply()
    }
    
    fun getTotalPendingAmount(): Double {
        return getAllChecks()
            .filter { it.status == CheckStatus.PENDING }
            .sumOf { it.amount }
    }
    
    fun getChecksByStatus(status: CheckStatus): List<Check> {
        return getAllChecks().filter { it.status == status }
    }
}