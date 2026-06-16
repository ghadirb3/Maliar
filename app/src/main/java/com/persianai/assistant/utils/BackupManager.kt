package com.persianai.assistant.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.persianai.assistant.config.FeatureFlags
import com.persianai.assistant.data.AccountingDB
import com.persianai.assistant.data.Transaction
import com.persianai.assistant.data.TransactionType
import com.persianai.assistant.data.CheckStatus
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
        private const val BACKUP_VERSION = 2

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
                android.util.Log.e("BackupManager", "Restore error", e)
                false
            }
        }
    }

    data class BackupData(
        val version: Int = BACKUP_VERSION,
        val timestamp: Long = System.currentTimeMillis(),
        val appVersion: String = "5.12",
        val transactions: List<Transaction> = emptyList(),
        val featureFlags: Map<String, Boolean> = emptyMap()
    )

    data class BackupResult(
        val success: Boolean,
        val message: String,
        val fileSize: Long = 0
    )

    data class RestoreResult(
        val success: Boolean,
        val message: String,
        val transactionsRestored: Int = 0
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
            android.util.Log.e("BackupManager", "خطا در ایجاد پشتیبان", e)
            BackupResult(false, "❌ خطا در ایجاد پشتیبان: ${e.message}")
        }
    }

    fun restoreFromLocalBackup(uri: Uri): RestoreResult {
        return try {
            val jsonString = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader -> reader.readText() }
            } ?: return RestoreResult(false, "❌ فایل پشتیبان خالی است")

            val backupData = gson.fromJson(jsonString, BackupData::class.java)
                ?: return RestoreResult(false, "❌ فرمت فایل پشتیبان نامعتبر است")

            var restoredCount = 0
            backupData.transactions.forEach { transaction ->
                try { db.addTransaction(transaction); restoredCount++ } 
                catch (_: Exception) {}
            }

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

            RestoreResult(true, "✅ بازیابی با موفقیت انجام شد. $restoredCount تراکنش بازیابی شد.", restoredCount)
        } catch (e: Exception) {
            android.util.Log.e("BackupManager", "خطا در بازیابی", e)
            RestoreResult(false, "❌ خطا در بازیابی: ${e.message}")
        }
    }

    fun createBackupJson(): String {
        return gson.toJson(collectBackupData())
    }

    fun restoreFromJson(jsonString: String): RestoreResult {
        return try {
            val backupData = gson.fromJson(jsonString, BackupData::class.java)
                ?: return RestoreResult(false, "❌ فرمت JSON نامعتبر است")
            var restoredCount = 0
            backupData.transactions.forEach { transaction ->
                try { db.addTransaction(transaction); restoredCount++ } 
                catch (_: Exception) {}
            }
            RestoreResult(true, "✅ بازیابی موفق: $restoredCount تراکنش", restoredCount)
        } catch (e: Exception) {
            android.util.Log.e("BackupManager", "Restore error", e)
            RestoreResult(false, "❌ خطا: ${e.message}")
        }
    }

    private fun collectBackupData(): BackupData {
        return BackupData(
            transactions = db.getAllTransactions(),
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