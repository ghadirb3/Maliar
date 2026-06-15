package com.persianai.assistant.utils

import android.content.Context
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

/**
 * مدیریت پشتیبان‌گیری و بازیابی اطلاعات
 * پشتیبانی از: Google Drive و فایل محلی
 */
class BackupManager(private val context: Context) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val db = AccountingDB(context)
    private val featureFlags = FeatureFlags(context)

    companion object {
        private const val BACKUP_VERSION = 2
        private const val BACKUP_MIME_TYPE = "application/json"
        private const val BACKUP_FILENAME = "maliar_backup.json"

        // Preference keys
        private const val PREF_BACKUP = "backup_prefs"
        private const val KEY_LAST_BACKUP_TIME = "last_backup_time"
        private const val KEY_LAST_BACKUP_SIZE = "last_backup_size"
        private const val KEY_AUTO_BACKUP_ENABLED = "auto_backup_enabled"
        private const val KEY_BACKUP_TO_CLOUD = "backup_to_cloud"
    }

    private val prefs = context.getSharedPreferences(PREF_BACKUP, Context.MODE_PRIVATE)

    // ==================== Data Model ====================

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

    // ==================== Local Backup/Restore ====================

    /**
     * ایجاد پشتیبان محلی و ذخیره در فایل
     */
    fun createLocalBackup(uri: Uri): BackupResult {
        return try {
            val backupData = collectBackupData()
            val json = gson.toJson(backupData)

            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    writer.write(json)
                }
            }

            val fileSize = json.toByteArray().size.toLong()
            saveLastBackupInfo(fileSize)

            BackupResult(true, "✅ پشتیبان با موفقیت ایجاد شد", fileSize)
        } catch (e: Exception) {
            android.util.Log.e("BackupManager", "خطا در ایجاد پشتیبان", e)
            BackupResult(false, "❌ خطا در ایجاد پشتیبان: ${e.message}")
        }
    }

    /**
     * بازیابی از فایل محلی
     */
    fun restoreFromLocalBackup(uri: Uri): RestoreResult {
        return try {
            val jsonString = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    reader.readText()
                }
            } ?: return RestoreResult(false, "❌ فایل پشتیبان خالی است")

            val backupData = gson.fromJson(jsonString, BackupData::class.java)
                ?: return RestoreResult(false, "❌ فرمت فایل پشتیبان نامعتبر است")

            var restoredCount = 0

            // Restore transactions
            backupData.transactions.forEach { transaction ->
                try {
                    db.addTransaction(transaction)
                    restoredCount++
                } catch (e: Exception) {
                    android.util.Log.w("BackupManager", "خطا در بازیابی تراکنش", e)
                }
            }

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

            RestoreResult(true, "✅ بازیابی با موفقیت انجام شد. $restoredCount تراکنش بازیابی شد.", restoredCount)
        } catch (e: Exception) {
            android.util.Log.e("BackupManager", "خطا در بازیابی", e)
            RestoreResult(false, "❌ خطا در بازیابی: ${e.message}")
        }
    }

    // ==================== Google Drive Backup/Restore ====================

    /**
     * ایجاد JSON برای آپلود به Google Drive
     */
    fun createBackupJson(): String {
        val backupData = collectBackupData()
        return gson.toJson(backupData)
    }

    /**
     * بازیابی از JSON دریافتی از Google Drive
     */
    fun restoreFromJson(jsonString: String): RestoreResult {
        return try {
            val backupData = gson.fromJson(jsonString, BackupData::class.java)
                ?: return RestoreResult(false, "❌ فرمت JSON نامعتبر است")

            var restoredCount = 0

            backupData.transactions.forEach { transaction ->
                try {
                    db.addTransaction(transaction)
                    restoredCount++
                } catch (e: Exception) {
                    android.util.Log.w("BackupManager", "Error restoring transaction", e)
                }
            }

            RestoreResult(true, "✅ بازیابی موفق: $restoredCount تراکنش", restoredCount)
        } catch (e: Exception) {
            android.util.Log.e("BackupManager", "Restore error", e)
            RestoreResult(false, "❌ خطا: ${e.message}")
        }
    }

    // ==================== Internal Methods ====================

    private fun collectBackupData(): BackupData {
        return BackupData(
            version = BACKUP_VERSION,
            timestamp = System.currentTimeMillis(),
            transactions = db.getAllTransactions(),
            featureFlags = featureFlags.getAllFlags()
        )
    }

    private fun saveLastBackupInfo(fileSize: Long) {
        prefs.edit()
            .putLong(KEY_LAST_BACKUP_TIME, System.currentTimeMillis())
            .putLong(KEY_LAST_BACKUP_SIZE, fileSize)
            .apply()
    }

    fun getLastBackupTime(): Long = prefs.getLong(KEY_LAST_BACKUP_TIME, 0L)

    fun getLastBackupSize(): Long = prefs.getLong(KEY_LAST_BACKUP_SIZE, 0L)

    fun isAutoBackupEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_BACKUP_ENABLED, false)

    fun setAutoBackupEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_BACKUP_ENABLED, enabled).apply()
    }

    fun isBackupToCloudEnabled(): Boolean = prefs.getBoolean(KEY_BACKUP_TO_CLOUD, false)

    fun setBackupToCloudEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BACKUP_TO_CLOUD, enabled).apply()
    }

    /**
     * Get formatted backup info string
     */
    fun getBackupInfo(): String {
        val lastTime = getLastBackupTime()
        if (lastTime == 0L) return "هنوز پشتیبان گرفته نشده است"

        val date = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(lastTime))
        val size = getLastBackupSize()
        val sizeStr = when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> String.format("%.1f KB", size / 1024.0)
            else -> String.format("%.1f MB", size / (1024.0 * 1024.0))
        }
        return "آخرین پشتیبان: $date ($sizeStr)"
    }
}