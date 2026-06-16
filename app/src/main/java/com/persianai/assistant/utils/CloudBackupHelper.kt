package com.persianai.assistant.utils

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.drive.*
import com.google.android.gms.drive.query.Filters
import com.google.android.gms.drive.query.Query
import com.google.android.gms.drive.query.SearchableField
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
        return account != null && GoogleSignIn.hasPermissions(account, Drive.SCOPE_FILE)
    }

    fun getSignInIntent(): Intent {
        val signInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestScopes(Drive.SCOPE_FILE)
            .requestScopes(Drive.SCOPE_APPFOLDER)
            .build()
        return GoogleSignIn.getClient(context, signInOptions).signInIntent
    }

    suspend fun signOut() {
        withContext(Dispatchers.IO) {
            try {
                val opts = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestScopes(Drive.SCOPE_FILE).build()
                GoogleSignIn.getClient(context, opts).signOut()
            } catch (e: Exception) {
                Log.e(TAG, "signOut error", e)
            }
        }
    }

    suspend fun uploadBackup(jsonContent: String): BackupManager.BackupResult = withContext(Dispatchers.IO) {
        if (!isConnected()) return@withContext saveLocal(jsonContent)

        try {
            val account = GoogleSignIn.getLastSignedInAccount(context) ?: return@withContext saveLocal(jsonContent)
            val drc = Drive.getDriveResourceClient(context, account)

            // Create content
            val contents = Tasks.await(drc.createContents())
            OutputStreamWriter(contents.outputStream).use { it.write(jsonContent) }

            // Create file metadata
            val meta = MetadataChangeSet.Builder()
                .setTitle("maliar_backup_${System.currentTimeMillis()}.json")
                .setMimeType("application/json")
                .build()

            // Create file in app folder
            Tasks.await(drc.createFile(meta, contents))

            BackupManager.BackupResult(true, "✅ بکاپ در Google Drive ذخیره شد", jsonContent.length.toLong())
        } catch (e: Exception) {
            Log.e(TAG, "Drive upload error", e)
            saveLocal(jsonContent)
        }
    }

    suspend fun downloadLatestBackup(): String? = withContext(Dispatchers.IO) {
        if (!isConnected()) return@withContext null

        try {
            val account = GoogleSignIn.getLastSignedInAccount(context) ?: return@withContext null
            val drc = Drive.getDriveResourceClient(context, account)

            val q = Query.Builder()
                .addFilter(Filters.eq(SearchableField.MIME_TYPE, "application/json"))
                .addFilter(Filters.contains(SearchableField.TITLE, "maliar_backup"))
                .build()

            val buf = Tasks.await(drc.queryChildren(q))
            if (buf.count == 0) { buf.release(); return@withContext null }

            val file = Tasks.await(drc.openContents(buf[0].driveId.asDriveFile()))
            buf.release()

            file.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Drive download error", e)
            null
        }
    }

    private fun saveLocal(json: String): BackupManager.BackupResult {
        return try {
            val dir = File(context.cacheDir, "backups").also { it.mkdirs() }
            val f = File(dir, "maliar_backup_${System.currentTimeMillis()}.json")
            f.writeText(json)
            BackupManager.BackupResult(true, "✅ بکاپ محلی ذخیره شد", f.length())
        } catch (e: Exception) {
            BackupManager.BackupResult(false, "❌ خطا: ${e.message}")
        }
    }
}