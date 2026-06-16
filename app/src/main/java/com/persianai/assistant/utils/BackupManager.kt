package com.persianai.assistant.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.persianai.assistant.config.FeatureFlags
import com.persianai.assistant.data.AccountingDB
import com.persianai.assistant.data.Transaction
import com.persianai.assistant.data.TransactionType
import com.persianai.assistant.data.CheckStatus
import com.persianai.assistant.data.ReminderBackupData
import com.persianai.assistant.data.SmartRemindersBackup
import com.persianai.assistant.models.Check
import com.persianai.assistant.models.Installment
import com.persianai.assistant.models.InstallmentStatus
import com.persianai.assistant.utils.SmartReminderManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class BackupManager(private val context: Context) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val db = AccountingDB(context)
    private val featureFlags = FeatureFlags(context)
    private val prefs = context.getSharedPreferences("backup_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val BACKUP_VERSION = 4
        private const val TAG = "BackupManager"

        suspend fun createBackup(context: Context): java.io.File {
            val manager = BackupManager(context)
            val backupData = manager.collectBackupData()
            val gson = GsonBuilder().setPrettyPrinting().create()
            val json = gson.toJson(backupData)
            
            val backupDir = java.io.File(context.cacheDir, "backups")
            backupDir.mkdirs()
            val backupFile = java.io.File(backupDir, "maliar_backup_${System.currentTimeMillis()}.json")
            backupFile.writeText(json)
            return backupFile
        }

        fun shareBackup(context: Context, backupFile: java.io.File) {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                backupFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "اشتراک‌گذاری بک‌آپ"))
        }

        suspend fun restoreBackup(context: Context, jsonContent: String): Boolean {
            return try {
                val manager = BackupManager(context)
                val result = manager.restoreFromJson(jsonContent)
                result.success
            } catch (e: Exception) {
                Log.e("BackupManager", "Restore error", e)
                false
            }
        }
    }

    data class BackupData(
        val version: Int = BACKUP_VERSION,
        val timestamp: Long = System.currentTimeMillis(),
        val appVersion: String = "5.12",
        val transactions: List<Transaction> = emptyList(),
        val checks: List<CheckExportData> = emptyList(),
        val installments: List<InstallmentExportData> = emptyList(),
        val smartReminders: SmartRemindersBackup = SmartRemindersBackup(),
        val featureFlags: Map<String, Boolean> = emptyMap()
    )

    data class CheckExportData(
        val id: Long = 0,
        val amount: Double = 0.0,
        val checkNumber: String = "",
        val recipient: String = "",
        val dueDate: Long = 0,
        val status: String = "PENDING",
        val description: String = "",
        val issueDate: Long = System.currentTimeMillis()
    )

    data class InstallmentExportData(
        val id: Long = 0,
        val title: String = "",
        val totalAmount: Double = 0.0,
        val monthlyAmount: Double = 0.0,
        val installmentCount: Int = 0,
        val paidInstallments: Int = 0,
        val paidAmount: Double = 0.0,
        val remainingAmount: Double = 0.0,
        val nextPaymentDate: Long = 0,
        val status: String = "ACTIVE",
        val description: String = "",
        val lender: String = ""
    )

    data class BackupResult(
        val success: Boolean,
        val message: String,
        val fileSize: Long = 0
    )

    data class RestoreResult(
        val success: Boolean,
        val message: String,
        val transactionsRestored: Int = 0,
        val checksRestored: Int = 0,
        val installmentsRestored: Int = 0,
        val remindersRestored: Int = 0
    )

    fun createLocalBackup(uri: Uri): BackupResult {
        return try {
            val backupData = collectBackupData()
            val json = gson.toJson(backupData)
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer -> writer.write(json) }
            }
            val fileSize = json.toByteArray().size.toLong()
            prefs.edit().putLong("last_backup_time", System.currentTimeMillis()).putLong("last_backup_size", fileSize).apply()
            BackupResult(true, "✅ پشتیبان با موفقیت ایجاد شد", fileSize)
        } catch (e: Exception) {
            Log.e("BackupManager", "خطا در ایجاد پشتیبان", e)
            BackupResult(false, "❌ خطا در ایجاد پشتیبان: ${e.message}")
        }
    }

    fun createBackupJson(): String {
        return gson.toJson(collectBackupData())
    }

    fun restoreFromLocalBackup(uri: Uri): RestoreResult {
        return try {
            val jsonString = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader -> reader.readText() }
            } ?: return RestoreResult(false, "❌ فایل پشتیبان خالی است")

            restoreFromJson(jsonString)
        } catch (e: Exception) {
            Log.e("BackupManager", "خطا در بازیابی", e)
            RestoreResult(false, "❌ خطا در بازیابی: ${e.message}")
        }
    }

    fun restoreFromJson(jsonString: String): RestoreResult {
        return try {
            val backupData = gson.fromJson(jsonString, BackupData::class.java)
                ?: return RestoreResult(false, "❌ فرمت JSON نامعتبر است")

            var transRestored = 0
            var checksRestored = 0
            var installmentsRestored = 0
            var remindersRestored = 0

            // Restore transactions
            backupData.transactions.forEach { transaction ->
                try { db.addTransaction(transaction); transRestored++ } 
                catch (_: Exception) {}
            }

            // Restore checks via AccountingDB
            val accountingDB = AccountingDB(context)
            backupData.checks.forEach { checkData ->
                try {
                    val check = Check(
                        id = checkData.id,
                        amount = checkData.amount,
                        checkNumber = checkData.checkNumber,
                        recipient = checkData.recipient,
                        dueDate = java.util.Date(checkData.dueDate),
                        status = try { com.persianai.assistant.models.CheckStatus.valueOf(checkData.status) } 
                            catch (_: Exception) { com.persianai.assistant.models.CheckStatus.PENDING },
                        description = checkData.description,
                        issueDate = java.util.Date(checkData.issueDate),
                        bankName = ""
                    )
                    accountingDB.addCheck(check)
                    checksRestored++
                } catch (_: Exception) {}
            }

            // Restore installments via AccountingDB
            backupData.installments.forEach { instData ->
                try {
                    val installment = Installment(
                        id = instData.id,
                        title = instData.title,
                        totalAmount = instData.totalAmount,
                        monthlyAmount = instData.monthlyAmount,
                        installmentCount = instData.installmentCount,
                        paidInstallments = instData.paidInstallments,
                        paidAmount = instData.paidAmount,
                        remainingAmount = instData.remainingAmount,
                        nextPaymentDate = java.util.Date(instData.nextPaymentDate),
                        status = try { InstallmentStatus.valueOf(instData.status) } 
                            catch (_: Exception) { InstallmentStatus.ACTIVE },
                        description = instData.description,
                        lender = instData.lender
                    )
                    accountingDB.addInstallment(installment)
                    installmentsRestored++
                } catch (_: Exception) {}
            }

            // Restore smart reminders
            try {
                val reminderManager = SmartReminderManager(context)
                backupData.smartReminders.reminders.forEach { reminderData ->
                    try {
                        val reminder = SmartReminderManager.SmartReminder(
                            id = reminderData.id,
                            title = reminderData.title,
                            description = reminderData.description,
                            type = try { SmartReminderManager.ReminderType.valueOf(reminderData.type) } 
                                catch (_: Exception) { SmartReminderManager.ReminderType.SIMPLE },
                            priority = try { SmartReminderManager.Priority.valueOf(reminderData.priority) } 
                                catch (_: Exception) { SmartReminderManager.Priority.MEDIUM },
                            alertType = try { SmartReminderManager.AlertType.valueOf(reminderData.alertType) } 
                                catch (_: Exception) { SmartReminderManager.AlertType.NOTIFICATION },
                            triggerTime = reminderData.triggerTime,
                            repeatPattern = try { SmartReminderManager.RepeatPattern.valueOf(reminderData.repeatPattern) } 
                                catch (_: Exception) { SmartReminderManager.RepeatPattern.ONCE },
                            customRepeatDays = reminderData.customRepeatDays,
                            isCompleted = reminderData.isCompleted,
                            completedAt = reminderData.completedAt,
                            createdAt = reminderData.createdAt
                        )
                        reminderManager.addReminderWithoutAlarm(reminder)
                        remindersRestored++
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}

            // Restore feature flags
            backupData.featureFlags.forEach { (key, value) ->
                when (key) {
                    "call" -> featureFlags.setCallEnabled(value)
                    "stt" -> featureFlags.setSTTEnabled(value)
                    "tts" -> featureFlags.setTTSEnabled(value)
                    "navigation" -> featureFlags.setNavigationEnabled(value)
                    "music" -> featureFlags.setMusicEnabled(value)
                    "weather" -> featureFlags.setWeatherEnabled(value)
                    "ai_chat" -> featureFlags.setAIChatEnabled(value)
                    "finance" -> featureFlags.setFinanceEnabled(value)
                    "reminders" -> featureFlags.setRemindersEnabled(value)
                }
            }

            val summary = buildString {
                append("✅ بازیابی با موفقیت انجام شد.")
                if (transRestored > 0) append(" $transRestored تراکنش")
                if (checksRestored > 0) append(" $checksRestored چک")
                if (installmentsRestored > 0) append(" $installmentsRestored قسط")
                if (remindersRestored > 0) append(" $remindersRestored یادآوری")
                append(" بازیابی شد.")
            }

            RestoreResult(
                success = true, 
                message = summary,
                transactionsRestored = transRestored,
                checksRestored = checksRestored,
                installmentsRestored = installmentsRestored,
                remindersRestored = remindersRestored
            )
        } catch (e: Exception) {
            Log.e("BackupManager", "Restore error", e)
            RestoreResult(false, "❌ خطا: ${e.message}")
        }
    }

    private fun collectBackupData(): BackupData {
        val accountingDB = AccountingDB(context)
        val reminderManager = SmartReminderManager(context)

        // Collect checks
        val checks = try {
            accountingDB.getAllChecks().map { check ->
                CheckExportData(
                    id = check.id,
                    amount = check.amount,
                    checkNumber = check.checkNumber,
                    recipient = check.recipient,
                    dueDate = check.dueDate.time,
                    status = check.status.name,
                    description = check.description,
                    issueDate = check.issueDate.time
                )
            }
        } catch (_: Exception) { emptyList() }

        // Collect installments
        val installments = try {
            accountingDB.getAllInstallments().map { inst ->
                InstallmentExportData(
                    id = inst.id,
                    title = inst.title,
                    totalAmount = inst.totalAmount,
                    monthlyAmount = inst.monthlyAmount,
                    installmentCount = inst.installmentCount,
                    paidInstallments = inst.paidInstallments,
                    paidAmount = inst.paidAmount,
                    remainingAmount = inst.remainingAmount,
                    nextPaymentDate = inst.nextPaymentDate.time,
                    status = inst.status.name,
                    description = inst.description,
                    lender = inst.lender
                )
            }
        } catch (_: Exception) { emptyList() }

        // Collect smart reminders
        val remindersBackup = try {
            val reminders = reminderManager.getAllReminders()
            SmartRemindersBackup(
                reminders = reminders.map { r ->
                    ReminderBackupData(
                        id = r.id,
                        title = r.title,
                        description = r.description,
                        type = r.type.name,
                        priority = r.priority.name,
                        alertType = r.alertType.name,
                        triggerTime = r.triggerTime,
                        repeatPattern = r.repeatPattern.name,
                        customRepeatDays = r.customRepeatDays,
                        isCompleted = r.isCompleted,
                        completedAt = r.completedAt,
                        createdAt = r.createdAt
                    )
                }
            )
        } catch (_: Exception) { SmartRemindersBackup() }

        return BackupData(
            transactions = db.getAllTransactions(),
            checks = checks,
            installments = installments,
            smartReminders = remindersBackup,
            featureFlags = featureFlags.getAllFlags()
        )
    }

    fun getLastBackupTime(): Long = prefs.getLong("last_backup_time", 0L)

    fun getBackupInfo(): String {
        val lastTime = getLastBackupTime()
        if (lastTime == 0L) return "هنوز پشتیبان گرفته نشده است"
        val date = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(lastTime))
        return "آخرین پشتیبان: $date"
    }
}