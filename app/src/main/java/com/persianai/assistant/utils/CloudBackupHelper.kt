package com.persianai.assistant.utils

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.drive.Drive
import com.google.android.gms.drive.DriveClient
import com.google.android.gms.drive.DriveResourceClient
import com.persianai.assistant.config.FeatureFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.io.OutputStreamWriter

/**
 * مدیریت پشتیبان‌گیری در Google Drive
 * با استفاده از Google Drive REST API (Simple)
 */
class CloudBackupHelper(private val context: Context) {

    companion object {
        private const val TAG = "CloudBackup"
        const val REQUEST_CODE_SIGN_IN = 1001
    }

    /**
     * بررسی وضعیت اتصال
     */
    fun isConnected(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        return account != null && GoogleSignIn.hasPermissions(account, Drive.SCOPE_FILE)
    }

    /**
     * ایجاد intent برای Sign-In
     */
    fun getSignInIntent(): Intent {
        val signInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestScopes(Drive.SCOPE_FILE)
            .requestScopes(Drive.SCOPE_APPFOLDER)
            .build()
        val client = GoogleSignIn.getClient(context, signInOptions)
        return client.signInIntent
    }

    /**
     * خروج از حساب Google
     */
    suspend fun signOut() {
        withContext(Dispatchers.IO) {
            try {
                val signInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestScopes(Drive.SCOPE_FILE)
                    .build()
                GoogleSignIn.getClient(context, signInOptions).signOut()
            } catch (e: Exception) {
                Log.e(TAG, "Error signing out", e)
            }
        }
    }

    /**
     * آپلود بکاپ - با استفاده از Drive API
     * در صورت عدم اتصال، بکاپ محلی ذخیره می‌شود
     */
    suspend fun uploadBackup(jsonContent: String): BackupManager.BackupResult {
        if (!isConnected()) {
            // Save locally as fallback
            return try {
                val backupDir = java.io.File(context.cacheDir, "backups")
                backupDir.mkdirs()
                val backupFile = java.io.File(backupDir, "maliar_backup_${System.currentTimeMillis()}.json")
                backupFile.writeText(jsonContent)
                BackupManager.BackupResult(true, "✅ بکاپ به صورت محلی ذخیره شد (Google Drive متصل نیست)", backupFile.length())
            } catch (e: Exception) {
                BackupManager.BackupResult(false, "❌ خطا: ${e.message}")
            }
        }

        return try {
            val account = GoogleSignIn.getLastSignedInAccount(context)
            if (account == null) {
                return BackupManager.BackupResult(false, "❌ حساب Google یافت نشد")
            }

            val driveClient = Drive.getDriveClient(context, account)
            val driveResourceClient = Drive.getDriveResourceClient(context, account)

            // Create a temporary file to upload
            val tempFile = java.io.File(context.cacheDir, "drive_backup_temp.json")
            tempFile.writeText(jsonContent)

            // Upload to app-specific folder
            val contents = driveResourceClient.createContents().await()
            contents.outputStream.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    writer.write(jsonContent)
                }
            }

            val metadata = com.google.android.gms.drive.MetadataChangeSet.Builder()
                .setTitle("maliar_backup_${System.currentTimeMillis()}.json")
                .setMimeType("application/json")
                .build()

            driveResourceClient.createFile(
                com.google.android.gms.drive.DriveFolder(com.google.android.gms.drive.DriveId.ROOT_RESOURCE_ID),
                metadata,
                contents
            ).await()

            tempFile.delete()
            BackupManager.BackupResult(true, "✅ پشتیبان با موفقیت در Google Drive ذخیره شد", jsonContent.toByteArray().size.toLong())
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading to Drive", e)
            BackupManager.BackupResult(false, "❌ خطا در آپلود به Google Drive: ${e.message}")
        }
    }
}

/**
 * Extension to help with Task await - using kotlinx.coroutines.tasks.await
 * This function is provided by the kotlinx-coroutines-play-services library
 */
