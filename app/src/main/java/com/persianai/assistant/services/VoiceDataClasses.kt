package com.persianai.assistant.services

import java.io.File
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*

// کلاس RecordingResult از فایل RecordingResult.kt خوانده می‌شود. اینجا حذف شد.

data class HybridAnalysisResult(
    val offlineText: String?,
    val onlineText: String?,
    val primaryText: String,
    val confidence: Double,
    val timestamp: Long = System.currentTimeMillis()
)

class SafeVoiceRecordingHelper(private val context: Context) {
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun startSafeRecording(onStart: () -> Unit) {
        recordingJob?.cancel()
        recordingJob = scope.launch {
            try {
                onStart()
            } catch (e: Exception) {
                Log.e("SafeVoice", "خطا در شروع ضبط: ${e.message}")
            }
        }
    }

    fun stopSafeRecording() {
        recordingJob?.cancel()
        recordingJob = null
    }
}
