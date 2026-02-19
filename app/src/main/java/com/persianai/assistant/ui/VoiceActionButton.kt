package com.persianai.assistant.ui

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.persianai.assistant.R
// import com.persianai.assistant.core.voice.SpeechToTextPipeline
import com.persianai.assistant.services.UnifiedVoiceEngine
import com.persianai.assistant.stt.OnlineSTTService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class VoiceActionButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    interface Listener {
        fun onRecordingStarted()
        fun onRecordingCompleted(audioFile: File, durationMs: Long)
        fun onTranscript(text: String)
        fun onRecordingError(error: String)
    }

    private var isListening = false
    private var btn: MaterialButton
    private var listener: Listener? = null
    private val TAG = "VoiceActionButton"
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val engine = UnifiedVoiceEngine(context)
    private val onlineSTT = OnlineSTTService(context)
    // private val sttPipeline = SpeechToTextPipeline(context)

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.view_voice_action_button, this, true)
        btn = view.findViewById(R.id.voice_action_btn)
        btn.setOnClickListener { toggleListening() }
    }

    fun setListener(l: Listener?) {
        listener = l
    }

    private fun toggleListening() {
        if (!isListening) startListening() else stopListening()
    }

    private fun checkPermission(): Boolean {
        val perm = android.Manifest.permission.RECORD_AUDIO
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermission() {
        val act = context as? Activity ?: return
        ActivityCompat.requestPermissions(act, arrayOf(android.Manifest.permission.RECORD_AUDIO), 4001)
    }

    private fun startListening() {
        if (!checkPermission()) {
            requestPermission()
            Toast.makeText(context, "نیاز به مجوز میکروفون", Toast.LENGTH_SHORT).show()
            return
        }

        scope.launch {
            try {
                val start = engine.startRecording()
                if (start.isFailure) {
                    isListening = false
                    btn.text = "🎤 صحبت کن"
                    listener?.onRecordingError(start.exceptionOrNull()?.message ?: "خطا در شروع ضبط")
                    return@launch
                }

                isListening = true
                btn.text = "⏹️ توقف"
                listener?.onRecordingStarted()
                Log.d(TAG, "✅ UnifiedVoiceEngine recording")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Exception starting recording", e)
                isListening = false
                btn.text = "🎤 صحبت کن"
                listener?.onRecordingError(e.message ?: "خطا نامشخص")
            }
        }
    }

    private fun stopListening() {
        scope.launch {
            try {
                isListening = false
                btn.text = "🎤 صحبت کن"

                val stopped = engine.stopRecording()
                if (stopped.isFailure) {
                    val msg = stopped.exceptionOrNull()?.message ?: "خطا در توقف ضبط"
                    listener?.onRecordingError(msg)
                    return@launch
                }

                val result = stopped.getOrNull() ?: run {
                    listener?.onRecordingError("فایل ضبط تولید نشد")
                    return@launch
                }

                listener?.onRecordingCompleted(result.file, result.duration)

                // Use OnlineSTTService with Liara priority and GapGPT fallback
                val sttResult = onlineSTT.transcribeAudio(result.file)
                
                if (sttResult.isSuccess && sttResult.text.isNotBlank()) {
                    listener?.onTranscript(sttResult.text)
                } else {
                    val err = sttResult.error ?: "متنی دریافت نشد"
                    listener?.onRecordingError(err)
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Exception stopping recording", e)
                listener?.onRecordingError(e.message ?: "خطا نامشخص")
            }
        }
    }
 }
