package com.persianai.assistant.utils

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.drive.*
import com.google.android.gms.drive.query.Filters
import com.google.android.gms.drive.query.SearchableField
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.persianai.assistant.config.FeatureFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*

/**
 * مدیریت پشتیبان‌گیری در Google Drive
 * نیاز به: play-services-drive و Google Sign-In
 */
class CloudBackupHelper(private val context: Context) {

    companion object {
        private const val TAG = "CloudBackup"
        private const val BACKUP_FOLDER_NAME = "MaliarBackups"
        private const val BACKUP_MIME_TYPE = "application/json"
        private const val BACKUP_FILE_NAME = "maliar_backup.json"
        
        // Request codes
        const val REQUEST_CODE_SIGN_IN = 1001
        const val REQUEST_CODE_CREATOR = 1002
        const val REQUEST_CODE_OPEN = 1003
    }

    private val featureFlags = FeatureFlags(context)
    private val prefs = context.getSharedPreferences("cloud_backup", Context.MODE_PRIVATE)
    private val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()

    private var driveClient: DriveClient? = null
    private var driveResourceClient: DriveResourceClient? = null

    /**
     * وضعیت اتصال به Google Drive
     */
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    /**
     * دریافت GoogleSignInClient
     */
    private fun getGoogleSignInClient(): GoogleSignInClient {
        val signInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestScopes(Drive.SCOPE_FILE)
            .requestScopes(Drive.SCOPE_APPFOLDER)
            .build()
        return GoogleSignIn.getClient(context, signInOptions)
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
        return getGoogleSignInClient().signInIntent
    }

    /**
     * برقراری اتصال پس از دریافت نتیجه Sign-In
     */
    suspend fun connectAfterSignIn(): Boolean = withContext(Dispatchers.IO) {
        try {
            val account = GoogleSignIn.getLastSignedInAccount(context)
            if (account == null) {
                Log.e(TAG, "No account found after sign-in")
                return@withContext false
            }

            driveClient = Drive.getDriveClient(context, account)
            driveResourceClient = Drive.getDriveResourceClient(context, account)
            
            Log.d(TAG, "Connected to Google Drive")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to Drive", e)
            return@withContext false
        }
    }

    /**
     * آپلود بکاپ به Google Drive
     */
    suspend fun uploadBackup(jsonContent: String): BackupManager.BackupResult = withContext(Dispatchers.IO) {
        if (!featureFlags.isBackupEnabled()) {
            return@withContext BackupManager.BackupResult(false, "بخش پشتیبان غیرفعال است")
        }

        if (!isConnected()) {
            return@withContext BackupManager.BackupResult(false, "به Google Drive متصل نیستید")
        }

        try {
            val driveResourceClient = driveResourceClient ?: return@withContext BackupManager.BackupResult(false, "Drive client not initialized")
            
            // Find or create backup folder
            val folderId = findOrCreateBackupFolder(driveResourceClient)
            
            // Create file in the backup folder
            val contents = jsonContent.toByteArray()
            val contentStream = ByteArrayInputStream(contents)

            val createFileTask = driveResourceClient.createContents()
            val contentsResult = Tasks.await(createFileTask)
            contentsResult.outputStream.use { outputStream ->
                outputStream.write(contents)
            }

            val metadata = MetadataChangeSet.Builder()
                .setTitle("maliar_backup_${System.currentTimeMillis()}.json")
                .setMimeType(BACKUP_MIME_TYPE)
                .build()

            val createFileOnDrive = driveResourceClient.createFile(
                DriveFolder(folderId),
                metadata,
                contentsResult
            )
            Tasks.await(createFileOnDrive)

            val fileSize = contents.size.toLong()
            BackupManager.BackupResult(true, "✅ پشتیبان با موفقیت در Google Drive ذخیره شد", fileSize)
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading to Drive", e)
            BackupManager.BackupResult(false, "❌ خطا در آپلود به Google Drive: ${e.message}")
        }
    }

    /**
     * دانلود آخرین بکاپ از Google Drive
     */
    suspend fun downloadLatestBackup(): String? = withContext(Dispatchers.IO) {
        if (!isConnected()) return@withContext null

        try {
            val driveResourceClient = driveResourceClient ?: return@withContext null
            
            val folderId = findOrCreateBackupFolder(driveResourceClient)
            val folder = DriveFolder(folderId)

            // Query for backup files
            val query = Query.Builder()
                .addFilter(Filters.eq(SearchableField.MIME_TYPE, BACKUP_MIME_TYPE))
                .addFilter(Filters.contains(SearchableField.TITLE, "maliar_backup"))
                .build()

            val queryTask = driveResourceClient.queryChildren(folder, query)
            val metadataBuffer = Tasks.await(queryTask)

            if (metadataBuffer.count == 0) {
                metadataBuffer.release()
                return@withContext null
            }

            // Get the latest file
            val latestFile = metadataBuffer[0]
            metadataBuffer.release()

            // Download contents
            val openTask = driveResourceClient.openContents(latestFile.driveId.asDriveFile())
            val contents = Tasks.await(openTask)
            
            return@withContext contents.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading from Drive", e)
            return@withContext null
        }
    }

    /**
     * یافتن یا ایجاد پوشه پشتیبان
     */
    private suspend fun findOrCreateBackupFolder(driveResourceClient: DriveResourceClient): String {
        return try {
            // Try to find existing folder
            val query = Query.Builder()
                .addFilter(Filters.eq(SearchableField.TITLE, BACKUP_FOLDER_NAME))
                .addFilter(Filters.eq(SearchableField.MIME_TYPE, "application/vnd.google-apps.folder"))
                .build()

            val queryTask = driveResourceClient.queryChildren(DriveFolder(DriveId.ROOT_RESOURCE_ID), query)
            val metadataBuffer = Tasks.await(queryTask)

            if (metadataBuffer.count > 0) {
                val folderId = metadataBuffer[0].driveId.resourceId
                metadataBuffer.release()
                return folderId
            }
            metadataBuffer.release()

            // Create folder if not exists
            val metadata = MetadataChangeSet.Builder()
                .setTitle(BACKUP_FOLDER_NAME)
                .setMimeType("application/vnd.google-apps.folder")
                .build()

            val createFolderTask = driveResourceClient.createFolder(
                DriveFolder(DriveId.ROOT_RESOURCE_ID),
                metadata
            )
            val folder = Tasks.await(createFolderTask)
            folder.driveId.resourceId
        } catch (e: Exception) {
            Log.e(TAG, "Error finding/creating backup folder", e)
            throw e
        }
    }

    /**
     * خروج از حساب Google
     */
    suspend fun signOut() {
        withContext(Dispatchers.IO) {
            try {
                getGoogleSignInClient().signOut()
                driveClient = null
                driveResourceClient = null
                Log.d(TAG, "Signed out from Google Drive")
            } catch (e: Exception) {
                Log.e(TAG, "Error signing out", e)
            }
        }
    }
}