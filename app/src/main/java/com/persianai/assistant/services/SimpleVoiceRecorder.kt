package com.persianai.assistant.services

import android.content.Context
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * ضبط صوتی ساده برای جایگزینی NewHybridVoiceRecorder
 */
class SimpleVoiceRecorder(private val context: Context) {
    
    private val TAG = "SimpleVoiceRecorder"
    private var mediaRecorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var startTime: Long = 0
    
    suspend fun startRecording(): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            // ایجاد فایل صوتی
            val outputDir = File(context.cacheDir, "audio_recordings")
            if (!outputDir.exists()) outputDir.mkdirs()
            
            currentFile = File(outputDir, "recording_${System.currentTimeMillis()}.m4a")
            
            // تنظیم MediaRecorder با فرمت MP3 (همانند مستندات GapGPT)
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)      // ← فرمت MP4 (شامل MP3)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)         // ← encoder AAC (کیفیت بالا)
                setAudioEncodingBitRate(64000)                          // ← 64kbps (برای STT بهینه)
                setAudioSamplingRate(16000)                             // ← 16kHz (استاندارد STT)
                setOutputFile(currentFile?.absolutePath)
                
                prepare()
                start()
            }
            
            startTime = System.currentTimeMillis()
            Log.d(TAG, "Recording started to: ${currentFile?.absolutePath}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
            Result.failure(e)
        }
    }
    
    suspend fun stopRecording(): Result<RecordingResult> = withContext(Dispatchers.IO) {
        return@withContext try {
            val file = currentFile
            if (file == null || !file.exists()) {
                return@withContext Result.failure(IOException("No recording file found"))
            }
            
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            
            val duration = System.currentTimeMillis() - startTime
            val result = RecordingResult(file, duration, startTime)
            
            Log.d(TAG, "Recording stopped: ${file.absolutePath}, duration: ${duration}ms")
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
            cleanup()
            Result.failure(e)
        }
    }
    
    fun cancelRecording() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
        } catch (e: Exception) {
            Log.w(TAG, "Error canceling recording", e)
        }
        cleanup()
    }
    
    fun isRecording(): Boolean {
        return mediaRecorder != null
    }
    
    fun isRecordingInProgress(): Boolean = isRecording()
    
    fun getCurrentRecordingDuration(): Long {
        return if (startTime > 0) System.currentTimeMillis() - startTime else 0L
    }
    
    fun getRecordingFile(): File? = currentFile
    
    fun setAmplitudeCallback(callback: ((Int) -> Unit)?) {
        // Simple implementation - could be enhanced with periodic polling
    }
    
    fun analyzeHybrid(file: File) {
        // Placeholder for hybrid analysis
    }
    
    fun analyzeOffline(file: File) {
        // Placeholder for offline analysis
    }
    
    fun getCurrentAmplitude(): Int {
        return try {
            mediaRecorder?.maxAmplitude ?: 0
        } catch (e: Exception) {
            0
        }
    }
    
    private fun cleanup() {
        try {
            currentFile?.let { file ->
                if (file.exists()) file.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up file", e)
        }
        currentFile = null
    }
}
