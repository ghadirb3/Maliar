package com.persianai.assistant.utils

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.drive.Drive
import com.google.android.gms.drive.DriveFile
import com.google.android.gms.drive.MetadataChangeSet
import com.google.android.gms.drive.query.Filters
import com.google.android.gms.drive.query.Query
import com.google.android.gms.drive.query.SearchableField
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class CloudBackupHelper(private val context: Context) {

    companion object {
        private const val TAG = "CloudBackup"
        private const val BACKUP_FILE_NAME = "maliar_backup.json"
        const val REQUEST_CODE_SIGN_IN = 1001
    }

    fun isConnected(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        return account != null && GoogleSignIn.hasPermissions(account, com.google.android.gms.drive.Drive.SCOPE_APPFOLDER)
    }

    fun getSignInIntent(): Intent {
        val opts = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestScopes(com.google.android.gms.drive.Drive.SCOPE_APPFOLDER)
            .build()
        return GoogleSignIn.getClient(context, opts).signInIntent
    }

    suspend fun signOut() {
        withContext(Dispatchers.IO) {
            try {
                val opts = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestScopes(com.google.android.gms.drive.Drive.SCOPE_APPFOLDER).build()
                GoogleSignIn.getClient(context, opts).signOut()
                Log.d(TAG, "Signed out")
            } catch (e: Exception) {
                Log.e(TAG, "signOut error", e)
            }
        }
    }

    suspend fun uploadBackup(jsonContent: String): BackupManager.BackupResult = withContext(Dispatchers.IO) {
        try {
            val localResult = saveLocal(jsonContent)

            if (!isConnected()) {
                return@withContext localResult
            }

            val account = GoogleSignIn.getLastSignedInAccount(context)
                ?: return@withContext localResult

            val driveResourceClient = Drive.getDriveResourceClient(context, account)

            deleteExistingDriveBackups(driveResourceClient)

            val contents = Tasks.await(driveResourceClient.createContents())
            contents.outputStream.use { it.write(jsonContent.toByteArray(Charsets.UTF_8)) }

            val metadata = MetadataChangeSet.Builder()
                .setTitle(BACKUP_FILE_NAME)
                .setMimeType("application/json")
                .build()

            Tasks.await(
                driveResourceClient.createFile(
                    driveResourceClient.appFolder,
                    metadata,
                    contents
                )
            )

            Log.d(TAG, "Backup uploaded to Google Drive app folder")
            BackupManager.BackupResult(
                true,
                "✅ پشتیبان در Google Drive و حافظه داخلی ذخیره شد",
                jsonContent.toByteArray().size.toLong()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Drive upload error", e)
            saveLocal(jsonContent)
        }
    }

    suspend fun downloadLatestBackup(): String? = withContext(Dispatchers.IO) {
        try {
            if (isConnected()) {
                val account = GoogleSignIn.getLastSignedInAccount(context)
                if (account != null) {
                    val driveResourceClient = Drive.getDriveResourceClient(context, account)
                    val query = Query.Builder()
                        .addFilters(Filters.eq(SearchableField.TITLE, BACKUP_FILE_NAME))
                        .build()

                    val metadataBuffer = Tasks.await(driveResourceClient.query(query))
                    try {
                        if (metadataBuffer.count > 0) {
                            val metadata = (0 until metadataBuffer.count)
                                .map { metadataBuffer.get(it) }
                                .maxByOrNull { it.modifiedDate?.time ?: 0L }

                            if (metadata != null) {
                                val contents = Tasks.await(
                                    driveResourceClient.openFile(metadata.driveId, DriveFile.MODE_READ_ONLY)
                                )
                                return@withContext contents.inputStream.bufferedReader().readText()
                            }
                        }
                    } finally {
                        metadataBuffer.release()
                    }
                }
            }

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
            Log.e(TAG, "Error downloading backup", e)
            null
        }
    }

    private fun deleteExistingDriveBackups(driveResourceClient: com.google.android.gms.drive.DriveResourceClient) {
        val query = Query.Builder()
            .addFilters(Filters.eq(SearchableField.TITLE, BACKUP_FILE_NAME))
            .build()

        val metadataBuffer = Tasks.await(driveResourceClient.query(query))
        try {
            for (i in 0 until metadataBuffer.count) {
                val metadata = metadataBuffer.get(i)
                Tasks.await(driveResourceClient.delete(metadata.driveId))
            }
        } finally {
            metadataBuffer.release()
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
