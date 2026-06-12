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
     * Analyze audio file using existing hybrid analyzer
     */
    suspend fun analyzeHybrid(file: File): Result<HybridAnalysisResult> = withContext(Dispatchers.IO) {
        return@withContext try {
            recorder.analyzeHybrid(file)
            val currentTime = System.currentTimeMillis()
            Result.success(HybridAnalysisResult(
                offlineText = null,
                onlineText = null,
                primaryText = "Hybrid analysis not available",
                confidence = 0.0,
                timestamp = currentTime
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing audio file", e)
            Result.failure(e)
        }
    }

}
