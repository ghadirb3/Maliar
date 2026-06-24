package com.persianai.assistant.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues

class AccountingDB(context: Context) : SQLiteOpenHelper(context, "accounting.db", null, 3) {
    
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE transactions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            type TEXT, amount REAL, category TEXT, description TEXT,
            date INTEGER, checkNumber TEXT, checkStatus TEXT,
            installmentId INTEGER, installmentNumber INTEGER, totalInstallments INTEGER)""")
        
        db.execSQL("""CREATE TABLE checks (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            amount REAL,
            checkNumber TEXT,
            issuer TEXT,
            recipient TEXT,
            dueDate INTEGER,
            status TEXT,
            type TEXT,
            description TEXT,
            bankName TEXT,
            accountNumber TEXT,
            createdDate INTEGER)""")
        
        db.execSQL("""CREATE TABLE installments (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            totalAmount REAL, monthlyAmount REAL, totalMonths INTEGER, paidMonths INTEGER,
            startDate INTEGER, description TEXT, category TEXT, reminderEnabled INTEGER, createdDate INTEGER)""")
    }
    
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS checks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                amount REAL, checkNumber TEXT, issuer TEXT, recipient TEXT, dueDate INTEGER,
                status TEXT, type TEXT, description TEXT, bankName TEXT, accountNumber TEXT, createdDate INTEGER)""")
            
            db.execSQL("""CREATE TABLE IF NOT EXISTS installments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                totalAmount REAL, monthlyAmount REAL, totalMonths INTEGER, paidMonths INTEGER,
                startDate INTEGER, description TEXT, category TEXT, reminderEnabled INTEGER, createdDate INTEGER)""")
        }

        if (old < 3) {
            // add missing columns if upgrading from older schema
            try { db.execSQL("ALTER TABLE checks ADD COLUMN recipient TEXT") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE checks ADD COLUMN bankName TEXT") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE checks ADD COLUMN accountNumber TEXT") } catch (_: Exception) {}
        }
    }
    
    fun addTransaction(t: Transaction): Long {
        // Temporary dedupe guard: if the latest transaction matches type/amount/description
        // and is within 60 seconds, return existing id to avoid duplicates during migration.
        try {
            val recentCursor = readableDatabase.rawQuery(
                "SELECT id, type, amount, description, date FROM transactions ORDER BY date DESC LIMIT 1",
                null
            )
            if (recentCursor.moveToFirst()) {
                val lastId = recentCursor.getLong(0)
                val lastType = recentCursor.getString(1)
                val lastAmount = recentCursor.getDouble(2)
                val lastDesc = recentCursor.getString(3) ?: ""
                val lastDate = recentCursor.getLong(4)
                recentCursor.close()

                val sameType = (lastType == t.type.name)
                val sameAmount = kotlin.math.abs(lastAmount - t.amount) < 0.001
                val sameDesc = (lastDesc.trim() == (t.description ?: "").trim())
                val withinWindow = (System.currentTimeMillis() - lastDate) <= 60_000L

                if (sameType && sameAmount && sameDesc && withinWindow) {
                    return lastId
                }
            } else {
                recentCursor.close()
            }
        } catch (_: Exception) {}

        val values = ContentValues().apply {
            put("type", t.type.name)
            put("amount", t.amount)
            put("category", t.category)
            put("description", t.description)
            put("date", t.date)
            put("checkNumber", t.checkNumber)
            put("checkStatus", t.checkStatus?.name)
            put("installmentId", t.installmentId)
            put("installmentNumber", t.installmentNumber)
            put("totalInstallments", t.totalInstallments)
        }
        return writableDatabase.insert("transactions", null, values)
    }
    
    fun getBalance(): Double {
        val cursor = readableDatabase.rawQuery(
            "SELECT type, SUM(amount) FROM transactions GROUP BY type", null)
        var balance = 0.0
        while (cursor.moveToNext()) {
            val type = cursor.getString(0)
            val sum = cursor.getDouble(1)
            balance += if (type == "INCOME") sum else -sum
        }
        cursor.close()
        return balance
    }
    
    fun getMonthlyExpenses(): Double {
        val cursor = readableDatabase.rawQuery(
            "SELECT SUM(amount) FROM transactions WHERE type = 'EXPENSE' AND date >= strftime('%s', 'now', 'start of month') * 1000", null)
        var expenses = 0.0
        if (cursor.moveToFirst()) {
            expenses = cursor.getDouble(0)
        }
        cursor.close()
        return expenses
    }
    
    fun getMonthlyIncome(): Double {
        val cursor = readableDatabase.rawQuery(
            "SELECT SUM(amount) FROM transactions WHERE type = 'INCOME' AND date >= strftime('%s', 'now', 'start of month') * 1000", null)
        var income = 0.0
        if (cursor.moveToFirst()) {
            income = cursor.getDouble(0)
        }
        cursor.close()
        return income
    }
    
    fun getAllTransactions(): List<Transaction> {
        val transactions = mutableListOf<Transaction>()
        val cursor = readableDatabase.rawQuery("SELECT * FROM transactions ORDER BY date DESC LIMIT 200", null)

        while (cursor.moveToNext()) {
            val transaction = Transaction(
                id = cursor.getLong(0),
                type = TransactionType.valueOf(cursor.getString(1)),
                amount = cursor.getDouble(2),
                category = cursor.getString(3) ?: "",
                description = cursor.getString(4) ?: "",
                date = cursor.getLong(5),
                checkNumber = cursor.getString(6),
                checkStatus = cursor.getString(7)?.let { CheckStatus.valueOf(it) },
                installmentId = if (cursor.isNull(8)) null else cursor.getLong(8),
                installmentNumber = if (cursor.isNull(9)) null else cursor.getInt(9),
                totalInstallments = if (cursor.isNull(10)) null else cursor.getInt(10)
            )
            transactions.add(transaction)
        }
        cursor.close()
        return transactions
    }

    fun getAllTransactions(type: TransactionType): List<Transaction> {
        val transactions = mutableListOf<Transaction>()
        val cursor = readableDatabase.rawQuery(
            "SELECT * FROM transactions WHERE type = ? ORDER BY date DESC LIMIT 50", arrayOf(type.name))

        while (cursor.moveToNext()) {
            val transaction = Transaction(
                id = cursor.getLong(0),
                type = TransactionType.valueOf(cursor.getString(1)),
                amount = cursor.getDouble(2),
                category = cursor.getString(3) ?: "",
                description = cursor.getString(4) ?: "",
                date = cursor.getLong(5),
                checkNumber = cursor.getString(6),
                checkStatus = cursor.getString(7)?.let { CheckStatus.valueOf(it) },
                installmentId = if (cursor.isNull(8)) null else cursor.getLong(8),
                installmentNumber = if (cursor.isNull(9)) null else cursor.getInt(9),
                totalInstallments = if (cursor.isNull(10)) null else cursor.getInt(10)
            )
            transactions.add(transaction)
        }
        cursor.close()
        return transactions
    }
    
    fun updateTransaction(transaction: Transaction): Int {
        val values = ContentValues().apply {
            put("type", transaction.type.name)
            put("amount", transaction.amount)
            put("category", transaction.category)
            put("description", transaction.description)
            put("date", transaction.date)
            put("checkNumber", transaction.checkNumber)
            put("checkStatus", transaction.checkStatus?.name)
            put("installmentId", transaction.installmentId)
            put("installmentNumber", transaction.installmentNumber)
            put("totalInstallments", transaction.totalInstallments)
        }
        return writableDatabase.update("transactions", values, "id = ?", arrayOf(transaction.id.toString()))
    }
    
    fun deleteTransaction(id: Long): Int {
        return writableDatabase.delete("transactions", "id = ?", arrayOf(id.toString()))
    }
    
    // ==================== متدهای چک ====================
    
    fun addCheck(check: com.persianai.assistant.models.Check): Long {
        val values = ContentValues().apply {
            put("amount", check.amount)
            put("checkNumber", check.checkNumber)
            put("issuer", check.issuer)
            put("recipient", check.recipient)
            put("dueDate", check.dueDate.time)
            put("status", check.status.name)
            put("type", "PAYMENT") // Default type
            put("description", check.description)
            put("bankName", check.bankName)
            put("accountNumber", check.accountNumber)
            put("createdDate", check.issueDate.time)
        }
        return writableDatabase.insert("checks", null, values)
    }
    
    fun getAllChecks(): List<com.persianai.assistant.models.Check> {
        val checks = mutableListOf<com.persianai.assistant.models.Check>()
        val cursor = readableDatabase.rawQuery(
            "SELECT * FROM checks ORDER BY dueDate ASC", null)

        while (cursor.moveToNext()) {
            try {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow("id"))
                val amount = cursor.getDouble(cursor.getColumnIndexOrThrow("amount"))
                val checkNumber = cursor.getString(cursor.getColumnIndexOrThrow("checkNumber")) ?: ""
                val issuer = cursor.getString(cursor.getColumnIndexOrThrow("issuer")) ?: ""
                val recipient = try { cursor.getString(cursor.getColumnIndexOrThrow("recipient")) ?: "" } catch (_: Exception) { "" }
                val dueDateVal = cursor.getLong(cursor.getColumnIndexOrThrow("dueDate"))
                val statusString = cursor.getString(cursor.getColumnIndexOrThrow("status")) ?: "PENDING"
                val description = try { cursor.getString(cursor.getColumnIndexOrThrow("description")) ?: "" } catch (_: Exception) { "" }
                val createdDate = try { cursor.getLong(cursor.getColumnIndexOrThrow("createdDate")) } catch (_: Exception) { System.currentTimeMillis() }
                val bankName = try { cursor.getString(cursor.getColumnIndexOrThrow("bankName")) ?: "" } catch (_: Exception) { "" }
                val accountNumber = try { cursor.getString(cursor.getColumnIndexOrThrow("accountNumber")) ?: "" } catch (_: Exception) { "" }

                val status = try {
                    com.persianai.assistant.models.CheckStatus.valueOf(statusString)
                } catch (e: Exception) {
                    com.persianai.assistant.models.CheckStatus.PENDING
                }

                checks.add(com.persianai.assistant.models.Check(
                    id = id,
                    checkNumber = checkNumber,
                    amount = amount,
                    recipient = recipient,
                    issuer = issuer,
                    issueDate = java.util.Date(createdDate),
                    dueDate = java.util.Date(dueDateVal),
                    status = status,
                    description = description,
                    bankName = bankName,
                    accountNumber = accountNumber
                ))
            } catch (e: Exception) {
                android.util.Log.w("AccountingDB", "Skipping malformed check row: ${e.message}")
            }
        }
        cursor.close()
        return checks
    }
    
    fun updateCheckStatus(id: Long, status: CheckStatus): Int {
        val values = ContentValues().apply {
            put("status", status.name)
        }
        return writableDatabase.update("checks", values, "id = ?", arrayOf(id.toString()))
    }
    
    fun updateCheck(check: com.persianai.assistant.models.Check): Int {
        val values = ContentValues().apply {
            put("amount", check.amount)
            put("checkNumber", check.checkNumber)
            put("issuer", check.issuer)
            put("recipient", check.recipient)
            put("dueDate", check.dueDate.time)
            put("status", check.status.name)
            put("type", "PAYMENT")
            put("description", check.description)
            put("bankName", check.bankName)
            put("accountNumber", check.accountNumber)
            put("createdDate", check.issueDate.time)
        }
        return writableDatabase.update("checks", values, "id = ?", arrayOf(check.id.toString()))
    }
    
    fun deleteCheck(id: Long): Int {
        return writableDatabase.delete("checks", "id = ?", arrayOf(id.toString()))
    }
    
    // ==================== متدهای اقساط ====================
    
    fun addInstallment(installment: com.persianai.assistant.models.Installment): Long {
        val values = ContentValues().apply {
            put("totalAmount", installment.totalAmount)
            put("monthlyAmount", installment.monthlyAmount)
            put("totalMonths", installment.installmentCount)
            put("paidMonths", installment.paidInstallments)
            put("startDate", installment.nextPaymentDate.time) // Simplified
            put("description", installment.title)
            put("category", installment.lender)
            put("reminderEnabled", 1)
            put("createdDate", System.currentTimeMillis())
        }
        return writableDatabase.insert("installments", null, values)
    }
    
    fun getAllInstallments(): List<com.persianai.assistant.models.Installment> {
        val installments = mutableListOf<com.persianai.assistant.models.Installment>()
        val cursor = readableDatabase.rawQuery(
            "SELECT * FROM installments ORDER BY startDate DESC", null)

        while (cursor.moveToNext()) {
            val totalMonths = cursor.getInt(3)
            val paidMonths = cursor.getInt(4)
            val totalAmount = cursor.getDouble(1)
            val monthlyAmount = cursor.getDouble(2)

            val status = when {
                paidMonths >= totalMonths -> com.persianai.assistant.models.InstallmentStatus.COMPLETED
                // Add logic for DELAYED if possible from DB data
                else -> com.persianai.assistant.models.InstallmentStatus.ACTIVE
            }

            installments.add(com.persianai.assistant.models.Installment(
                id = cursor.getLong(0),
                title = cursor.getString(6) ?: "قسط",
                totalAmount = totalAmount,
                monthlyAmount = monthlyAmount,
                installmentCount = totalMonths,
                paidInstallments = paidMonths,
                paidAmount = paidMonths * monthlyAmount,
                remainingAmount = (totalMonths - paidMonths) * monthlyAmount,
                nextPaymentDate = java.util.Date(cursor.getLong(5)),
                status = status,
                description = "",
                lender = cursor.getString(7) ?: ""
            ))
        }
        cursor.close()
        return installments
    }
    
    fun payInstallment(id: Long): Boolean {
        val cursor = readableDatabase.rawQuery(
            "SELECT paidMonths, totalMonths FROM installments WHERE id = ?", arrayOf(id.toString()))
        
        if (cursor.moveToFirst()) {
            val paidMonths = cursor.getInt(0)
            val totalMonths = cursor.getInt(1)
            cursor.close()
            
            if (paidMonths < totalMonths) {
                val values = ContentValues().apply {
                    put("paidMonths", paidMonths + 1)
                }
                writableDatabase.update("installments", values, "id = ?", arrayOf(id.toString()))
                return true
            }
        } else {
            cursor.close()
        }
        return false
    }
    
    fun updateInstallment(installment: com.persianai.assistant.models.Installment): Int {
        val values = ContentValues().apply {
            put("totalAmount", installment.totalAmount)
            put("monthlyAmount", installment.monthlyAmount)
            put("totalMonths", installment.installmentCount)
            put("paidMonths", installment.paidInstallments)
            put("startDate", installment.nextPaymentDate.time)
            put("description", installment.title)
            put("category", installment.lender)
            put("reminderEnabled", 1)
        }
        return writableDatabase.update("installments", values, "id = ?", arrayOf(installment.id.toString()))
    }
    
    fun deleteInstallment(id: Long): Int {
        return writableDatabase.delete("installments", "id = ?", arrayOf(id.toString()))
    }
    
    // ==================== گزارشات ====================
    
    fun getMonthlyReport(year: Int, month: Int): Map<String, Double> {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(year, month - 1, 1, 0, 0, 0)
        val startTime = calendar.timeInMillis
        
        calendar.add(java.util.Calendar.MONTH, 1)
        val endTime = calendar.timeInMillis
        
        val cursor = readableDatabase.rawQuery(
            "SELECT type, SUM(amount) FROM transactions WHERE date >= ? AND date < ? GROUP BY type",
            arrayOf(startTime.toString(), endTime.toString()))
        
        val report = mutableMapOf<String, Double>()
        while (cursor.moveToNext()) {
            report[cursor.getString(0)] = cursor.getDouble(1)
        }
        cursor.close()
        return report
    }
    
    fun getYearlyReport(year: Int): Map<String, Double> {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(year, 0, 1, 0, 0, 0)
        val startTime = calendar.timeInMillis
        
        calendar.add(java.util.Calendar.YEAR, 1)
        val endTime = calendar.timeInMillis
        
        val cursor = readableDatabase.rawQuery(
            "SELECT type, SUM(amount) FROM transactions WHERE date >= ? AND date < ? GROUP BY type",
            arrayOf(startTime.toString(), endTime.toString()))
        
        val report = mutableMapOf<String, Double>()
        while (cursor.moveToNext()) {
            report[cursor.getString(0)] = cursor.getDouble(1)
        }
        cursor.close()
        return report
    }
}
