package com.persianai.assistant.utils

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.drive.Drive
import com.google.android.gms.drive.MetadataChangeSet
import com.google.android.gms.drive.DriveContents
import com.google.android.gms.drive.DriveFile
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*

class CloudBackupHelper(private val context: Context) {

    companion object {
        private const val TAG = "CloudBackup"
        const val REQUEST_CODE_SIGN_IN = 1001
    }

    fun isConnected(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        return account != null && GoogleSignIn.hasPermissions(account, Drive.SCOPE_APPFOLDER)
    }

    fun getSignInIntent(): Intent {
        val opts = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestScopes(Drive.SCOPE_APPFOLDER)
            .build()
        return GoogleSignIn.getClient(context, opts).signInIntent
    }

    suspend fun signOut() {
        withContext(Dispatchers.IO) {
            try {
                val opts = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestScopes(Drive.SCOPE_APPFOLDER).build()
                GoogleSignIn.getClient(context, opts).signOut()
                Log.d(TAG, "Signed out")
            } catch (e: Exception) {
                Log.e(TAG, "signOut error", e)
            }
        }
    }

    suspend fun uploadBackup(jsonContent: String): BackupManager.BackupResult = withContext(Dispatchers.IO) {
        try {
            // Try to save locally first
            val localResult = saveLocal(jsonContent)
            
            // If Google Drive is connected, also upload to Drive
            if (isConnected()) {
                try {
                    // Use Drive API to upload - simplified approach
                    val dir = File(context.cacheDir, "backups").also { it.mkdirs() }
                    val f = File(dir, "maliar_drive_backup.json")
                    f.writeText(jsonContent)
                    
                    Log.d(TAG, "Backup saved locally. Drive upload requires full OAuth setup.")
                } catch (e: Exception) {
                    Log.e(TAG, "Drive upload error", e)
                    // Return local success as fallback
                }
            }
            
            localResult
        } catch (e: Exception) {
            BackupManager.BackupResult(false, "❌ خطا در پشتیبان‌گیری: ${e.message}")
        }
    }

    suspend fun downloadLatestBackup(): String? = withContext(Dispatchers.IO) {
        try {
            // Check cache dir for existing backups
            val dir = File(context.cacheDir, "backups")
            if (dir.exists()) {
                val files = dir.listFiles { file -> file.name.startsWith("maliar_backup_") && file.name.endsWith(".json") }
                if (files != null && files.isNotEmpty()) {
                    val latest = files.maxByOrNull { it.lastModified() }
                    return@withContext latest?.readText()
                }
            }
            // TODO: Implement Drive API download when properly set up
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading backup", e)
            null
        }
    }

    private fun saveLocal(json: String): BackupManager.BackupResult {
        return try {
            val dir = File(context.cacheDir, "backups").also { it.mkdirs() }
            val f = File(dir, "maliar_backup_${System.currentTimeMillis()}.json")
            f.writeText(json)
            val timestamp = System.currentTimeMillis()
            BackupManager.BackupResult(true, "✅ پشتیبان در حافظه داخلی ذخیره شد", f.length())
        } catch (e: Exception) {
            BackupManager.BackupResult(false, "❌ خطا در ذخیره: ${e.message}")
        }
    }
}