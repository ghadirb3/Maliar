package com.persianai.assistant.utils

import android.content.Context
import android.net.Uri
import com.persianai.assistant.data.AccountingDB
import com.persianai.assistant.data.Transaction
import com.persianai.assistant.data.TransactionType
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * مدیریت Export/Import CSV برای تراکنش‌ها
 */
class CSVManager(private val context: Context) {

    private val db = AccountingDB(context)
    private val accountingManager = com.persianai.assistant.utils.AccountingManager(context)

    /**
     * Export تمام تراکنش‌ها به CSV
     */
    fun exportTransactionsToCSV(): String {
        val transactions = db.getAllTransactions()
        val sb = StringBuilder()
        
        // Header
        sb.appendLine("ID,نوع,مبلغ,دسته‌بندی,توضیحات,تاریخ,شماره چک,وضعیت چک,قسط ID,شماره قسط,کل اقساط")
        
        transactions.forEach { t ->
            sb.appendLine(
                "${t.id},${t.type.name},${t.amount},\"${t.category}\",\"${t.description}\",${t.date}," +
                "${t.checkNumber ?: ""},${t.checkStatus?.name ?: ""},${t.installmentId ?: ""}," +
                "${t.installmentNumber ?: ""},${t.totalInstallments ?: ""}"
            )
        }
        
        return sb.toString()
    }

    /**
     * Export به فایل
     */
    fun exportToFile(uri: Uri): Boolean {
        return try {
            val csv = exportTransactionsToCSV()
            context.contentResolver.openOutputStream(uri)?.use { os ->
                OutputStreamWriter(os).use { writer ->
                    writer.write(csv)
                }
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("CSVManager", "Error exporting CSV", e)
            false
        }
    }

    /**
     * Import از فایل CSV
     */
    fun importFromFile(uri: Uri): ImportResult {
        var imported = 0
        var skipped = 0
        val errors = mutableListOf<String>()

        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    var headerLine = reader.readLine() ?: return ImportResult(0, 0, listOf("فایل خالی است"))
                    
                    // Try to determine if CSV has header
                    val hasHeader = headerLine.contains("ID") || headerLine.contains("نوع") || 
                                    headerLine.contains("مبلغ") || headerLine.contains("type")
                    
                    if (!hasHeader) {
                        // Try to parse first line as data
                        try {
                            val parts = parseCSVLine(headerLine)
                            if (parts.size >= 3 && parts[1].uppercase() in listOf("INCOME", "EXPENSE", "CHECK_IN", "CHECK_OUT", "INSTALLMENT")) {
                                // First line is data, rewind conceptually - just process it
                                if (importTransaction(parts)) imported++ else skipped++
                            }
                        } catch (_: Exception) {
                            // Probably header
                        }
                    }

                    var line: String?
                    var lineNum = 1
                    while (reader.readLine().also { line = it } != null) {
                        lineNum++
                        try {
                            val parts = parseCSVLine(line ?: continue)
                            if (parts.size < 3) {
                                skipped++
                                continue
                            }
                            if (importTransaction(parts)) imported++ else skipped++
                        } catch (e: Exception) {
                            errors.add("خط $lineNum: ${e.message}")
                            skipped++
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CSVManager", "Error importing CSV", e)
            return ImportResult(0, 0, listOf("خطا در خواندن فایل: ${e.message}"))
        }

        return ImportResult(imported, skipped, errors)
    }

    private fun importTransaction(parts: List<String>): Boolean {
        if (parts.size < 3) return false
        
        val typeStr = parts[1].trim().uppercase()
        val type = try {
            TransactionType.valueOf(typeStr)
        } catch (_: Exception) {
            when {
                typeStr in listOf("INCOME", "درآمد") -> TransactionType.INCOME
                typeStr in listOf("EXPENSE", "هزینه") -> TransactionType.EXPENSE
                typeStr in listOf("CHECK_IN") -> TransactionType.CHECK_IN
                typeStr in listOf("CHECK_OUT") -> TransactionType.CHECK_OUT
                typeStr in listOf("INSTALLMENT", "قسط") -> TransactionType.INSTALLMENT
                else -> return false
            }
        }

        val amount = parts[2].trim().toDoubleOrNull() ?: return false
        val category = parts.getOrNull(3)?.trim()?.trim('"') ?: ""
        val description = parts.getOrNull(4)?.trim()?.trim('"') ?: ""
        val date = parts.getOrNull(5)?.trim()?.toLongOrNull() ?: System.currentTimeMillis()
        val checkNumber = parts.getOrNull(6)?.trim()?.trim('"')?.takeIf { it.isNotBlank() }
        val checkStatus = parts.getOrNull(7)?.trim()?.takeIf { it.isNotBlank() }
        val installmentId = parts.getOrNull(8)?.trim()?.toLongOrNull()
        val installmentNumber = parts.getOrNull(9)?.trim()?.toIntOrNull()
        val totalInstallments = parts.getOrNull(10)?.trim()?.toIntOrNull()

        val transaction = Transaction(
            type = type,
            amount = amount,
            category = category,
            description = description,
            date = date,
            checkNumber = checkNumber,
            checkStatus = try { checkStatus?.let { com.persianai.assistant.data.CheckStatus.valueOf(it) } } catch (_: Exception) { null },
            installmentId = installmentId,
            installmentNumber = installmentNumber,
            totalInstallments = totalInstallments
        )

        accountingManager.addTransaction(transaction)
        return true
    }

    /**
     * Parse a CSV line handling quoted fields
     */
    private fun parseCSVLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        
        for (char in line) {
            when {
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    result.add(current.toString())
                    current.clear()
                }
                else -> current.append(char)
            }
        }
        result.add(current.toString())
        return result
    }

    data class ImportResult(
        val imported: Int,
        val skipped: Int,
        val errors: List<String>
    )
}