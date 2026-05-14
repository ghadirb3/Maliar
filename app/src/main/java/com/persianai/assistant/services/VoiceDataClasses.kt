package com.persianai.assistant.services

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import android.util.Log

// کلاس RecordingResult از فایل RecordingResult.kt (در پوشه جدید) خوانده می‌شود.
// برای جلوگیری از خطای Redeclaration، تعریف آن از اینجا حذف شد اما در پروژه موجود است.

data class HybridAnalysisResult(
    val offlineText: String?,
    val onlineText: String?,
    val primaryText: String,
    val confidence: Double,
    val timestamp: Long = System.currentTimeMillis()
)

class SafeVoiceRecordingHelper(private val context: android.content.Context) {
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
