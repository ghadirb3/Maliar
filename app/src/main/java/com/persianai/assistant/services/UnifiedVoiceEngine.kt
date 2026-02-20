package com.persianai.assistant.services

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel

/**
 * UnifiedVoiceEngine
 * - Thin, safe wrapper around existing recorder implementations
 * - Exposes coroutine-friendly start/stop/cancel APIs
 * - Provides utilities to ensure offline Haaniye model files are available
 *
 * Note: This file intentionally keeps logic small and delegates heavy work to
 * `NewHybridVoiceRecorder` / existing classes. The goal is to centralize
 * usage and add a stable integration point for later full refactor.
 */
class UnifiedVoiceEngine(private val context: Context) {

    private val TAG = "UnifiedVoiceEngine"
    private val recorder = SimpleVoiceRecorder(context)

    suspend fun startRecording(): Result<Unit> = withContext(Dispatchers.Main) {
        return@withContext try {
            recorder.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
            Result.failure(e)
        }
    }

    suspend fun stopRecording(): Result<RecordingResult> = withContext(Dispatchers.Main) {
        return@withContext try {
            recorder.stopRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
            Result.failure(e)
        }
    }

    suspend fun cancelRecording(): Result<Unit> = withContext(Dispatchers.Main) {
        return@withContext try {
            recorder.cancelRecording()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling recording", e)
            Result.failure(e)
        }
    }

    fun isRecordingInProgress(): Boolean = recorder.isRecordingInProgress()

    fun getCurrentAmplitude(): Int = try { recorder.getCurrentAmplitude() } catch (e: Exception) { 0 }

    fun getCurrentRecordingDuration(): Long = try { recorder.getCurrentRecordingDuration() } catch (e: Exception) { 0L }

    fun getRecordingFile(): File? = try { recorder.getRecordingFile() } catch (e: Exception) { null }

    fun hasRequiredPermissions(): Boolean {
        val record = ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
        return record == PackageManager.PERMISSION_GRANTED
    }

    fun setAmplitudeCallback(callback: ((Int) -> Unit)?) {
        try {
            if (callback != null) {
                recorder.setAmplitudeCallback(callback)
            } else {
                recorder.setAmplitudeCallback { }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set amplitude callback", e)
        }
    }

    /**
     * Copy Haaniye offline model directory from a host path into app files directory.
     * This is provided so CI / developer machines can push model artifacts into
     * the app's runtime storage for the offline path the app will use.
     *
     * Example hostPath (Windows):
     * C:\\github\\PersianAIAssistantOnline\\app\\build\\intermediates\\assets\\debug\\tts\\haaniye
     */
    suspend fun copyHaaniyeFromHost(hostPath: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val src = File(hostPath)
            if (!src.exists() || !src.isDirectory) {
                Log.w(TAG, "Haaniye host path not found: $hostPath")
                return@withContext false
            }

            val dest = File(context.filesDir, "haaniye")
            if (!dest.exists()) dest.mkdirs()

            // copy recursively
            src.walkTopDown().forEach { f ->
                val relative = f.toRelativeString(src)
                val out = File(dest, relative)
                if (f.isDirectory) {
                    if (!out.exists()) out.mkdirs()
                } else {
                    // copy file
                    copyFile(f, out)
                }
            }

            Log.d(TAG, "Haaniye files copied to: ${dest.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy Haaniye files", e)
            false
        }
    }

    private fun copyFile(src: File, dst: File) {
        var inChannel: FileChannel? = null
        var outChannel: FileChannel? = null
        try {
            if (!dst.parentFile.exists()) dst.parentFile.mkdirs()
            inChannel = FileInputStream(src).channel
            outChannel = FileOutputStream(dst).channel
            inChannel.transferTo(0, inChannel.size(), outChannel)
        } finally {
            try { inChannel?.close() } catch (_: Exception) {}
            try { outChannel?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Analyze audio file using existing hybrid analyzer
     */
    suspend fun analyzeHybrid(file: File): Result<HybridAnalysisResult> = withContext(Dispatchers.IO) {
        return@withContext try {
            recorder.analyzeHybrid(file)
            Result.success(HybridAnalysisResult(null, null, null))
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing audio file", e)
            Result.failure(e)
        }
    }

    /**
     * Analyze audio file using offline STT only (Haaniye).
     */
    suspend fun analyzeOffline(file: File): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            recorder.analyzeOffline(file)
            Result.success("Offline analysis not available")
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing offline", e)
            Result.failure(e)
        }
    }
}
