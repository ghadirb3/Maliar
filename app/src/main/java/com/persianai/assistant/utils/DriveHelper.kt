package com.persianai.assistant.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * کلاس کمکی برای دانلود فایل از Google Drive
 */
object DriveHelper {

    private const val DRIVE_DOWNLOAD_URL = "https://drive.google.com/uc?export=download&id="
    private const val ENCRYPTED_KEYS_FILE_ID = "17iwkjyGcxJeDgwQWEcsOdfbOxOah_0u0"
    private const val GIST_KEYS_URL =
        "https://abrehamrahi.ir/o/public/UfAv7lIC/"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * دانلود فایل رمزگذاری شده کلیدها از Google Drive
     */
    suspend fun downloadEncryptedKeys(): String = withContext(Dispatchers.IO) {
        runCatching { downloadFromUrl(GIST_KEYS_URL) }.getOrElse {
            val url = DRIVE_DOWNLOAD_URL + ENCRYPTED_KEYS_FILE_ID
            val request = Request.Builder()
                .url(url)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("خطا در دانلود: ${response.code}")
                }
                response.body?.string() ?: throw IOException("پاسخ خالی است")
            }
        }
    }

    /**
     * دانلود فایل از URL مستقیم
     */
    suspend fun downloadFromUrl(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("خطا در دانلود: ${response.code}")
            }
            response.body?.string() ?: throw IOException("پاسخ خالی است")
        }
    }

    /**
     * آپلود بکاپ به Google Drive (نیاز به Context دارد)
     * از CloudBackupHelper استفاده می‌کند
     */
    suspend fun uploadBackupToDrive(context: Context, content: String): BackupManager.BackupResult {
        val cloudHelper = CloudBackupHelper(context)
        return cloudHelper.uploadBackup(content)
    }

    /**
     * دانلود آخرین بکاپ از Google Drive
     */
    suspend fun downloadLatestBackup(context: Context): String? {
        val cloudHelper = CloudBackupHelper(context)
        return cloudHelper.downloadLatestBackup()
    }
}
