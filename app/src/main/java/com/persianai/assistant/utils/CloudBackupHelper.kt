package com.persianai.assistant.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * نسخه ساده‌شدهٔ CloudBackupHelper: فعلاً فقط پشتیبان‌گیری محلی انجام می‌شود.
 * حذف تماس‌های مستقیم با Drive SDK تا سازگاری حفظ شود.
 */
class CloudBackupHelper(private val context: Context) {

    companion object {
        private const val TAG = "CloudBackup"
    }

    suspend fun uploadBackup(jsonContent: String): BackupManager.BackupResult = withContext(Dispatchers.IO) {
        try {
            val res = saveLocal(jsonContent)
            Log.d(TAG, "Local backup saved: ${res.message}")
            res
        } catch (e: Exception) {
            Log.e(TAG, "Error saving backup locally", e)
            BackupManager.BackupResult(false, "❌ خطا در ذخیره: ${e.message}")
        }
    }

    suspend fun downloadLatestBackup(): String? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "backups")
            if (dir.exists()) {
                val files = dir.listFiles { file ->
                    file.name.startsWith("maliar_backup_") && file.name.endsWith(".json")
                }
                if (files != null && files.isNotEmpty()) {
                    return@withContext files.maxByOrNull { it.lastModified() }?.readText()
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error reading local backups", e)
            null
        }
    }

    private fun saveLocal(json: String): BackupManager.BackupResult {
        return try {
            val dir = File(context.cacheDir, "backups").also { it.mkdirs() }
            val f = File(dir, "maliar_backup_${System.currentTimeMillis()}.json")
            f.writeText(json)
            BackupManager.BackupResult(true, "✅ پشتیبان در حافظه داخلی ذخیره شد", f.length())
        } catch (e: Exception) {
            BackupManager.BackupResult(false, "❌ خطا در ذخیره: ${e.message}")
        }
    }
}
