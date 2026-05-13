package com.example.maliar.core.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.maliar.core.api.GapGPTService
import com.example.maliar.core.api.LiaraService
import com.example.maliar.data.model.GapGPTTTSRequest
import com.example.maliar.data.model.LiaraTTSRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.ResponseBody
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.coroutines.resume

class TTSHelper(
    private val context: Context,
    private val gapGPTService: GapGPTService,
    private val liaraService: LiaraService
) {
    private val TAG = "TTSHelper"
    private var androidTTS: TextToSpeech? = null
    private var isAndroidTTSReady = false
    private var mediaPlayer: MediaPlayer? = null
    
    // کش برای فایل‌های صوتی
    private val audioCacheDir = File(context.cacheDir, "tts_audio")
    
    init {
        // ایجاد دایرکتوری کش
        if (!audioCacheDir.exists()) {
            audioCacheDir.mkdirs()
        }
        
        // راه‌اندازی Android TTS
        androidTTS = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                androidTTS?.language = Locale("fa", "IR")
                isAndroidTTSReady = true
                Log.d(TAG, "Android TTS initialized successfully")
            } else {
                Log.e(TAG, "Android TTS initialization failed")
            }
        }
    }
    
    /**
     * تبدیل متن به گفتار با اولویت‌بندی: GapGPT -> Liara -> Android TTS
     * و انتظار تا پایان کامل پخش
     */
    suspend fun speakOnlineFirstAndWait(text: String): Boolean {
        return withContext(Dispatchers.Main) {
            try {
                // اولویت 1: GapGPT
                if (tryGapGPTTTSAndWait(text)) {
                    Log.d(TAG, "GapGPT TTS completed successfully")
                    return@withContext true
                }
                
                // اولویت 2: Liara
                if (tryLiaraTTSAndWait(text)) {
                    Log.d(TAG, "Liara TTS completed successfully")
                    return@withContext true
                }
                
                // اولویت 3: Android TTS
                if (tryAndroidTTSAndWait(text)) {
                    Log.d(TAG, "Android TTS completed successfully")
                    return@withContext true
                }
                
                Log.e(TAG, "All TTS methods failed")
                return@withContext false
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in speakOnlineFirstAndWait", e)
                return@withContext false
            }
        }
    }
    
    /**
     * تلاش برای استفاده از GapGPT TTS و انتظار تا پایان پخش
     */
    private suspend fun tryGapGPTTTSAndWait(text: String): Boolean {
        return try {
            withTimeout(15000L) { // 15 ثانیه timeout
                val response = gapGPTService.textToSpeech(
                    GapGPTTTSRequest(
                        text = text,
                        voice = "fa-IR-DilaraNeural", // صدای فارسی با کیفیت بالا
                        speed = 0.9 // کمی آهسته‌تر برای وضوح بیشتر
                    )
                )
                
                if (response.isSuccessful && response.body() != null) {
                    val audioFile = saveAudioToCache(response.body()!!, "gapgpt_${System.currentTimeMillis()}.mp3")
                    playAudioFileAndWait(audioFile)
                } else {
                    Log.e(TAG, "GapGPT TTS failed: ${response.code()}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "GapGPT TTS error", e)
            false
        }
    }
    
    /**
     * تلاش برای استفاده از Liara TTS و انتظار تا پایان پخش
     */
    private suspend fun tryLiaraTTSAndWait(text: String): Boolean {
        return try {
            withTimeout(15000L) { // 15 ثانیه timeout
                val response = liaraService.textToSpeech(
                    LiaraTTSRequest(
                        text = text,
                        voice = "female", // صدای زنانه
                        speed = 0.9
                    )
                )
                
                if (response.isSuccessful && response.body() != null) {
                    val audioFile = saveAudioToCache(response.body()!!, "liara_${System.currentTimeMillis()}.mp3")
                    playAudioFileAndWait(audioFile)
                } else {
                    Log.e(TAG, "Liara TTS failed: ${response.code()}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Liara TTS error", e)
            false
        }
    }
    
    /**
     * تلاش برای استفاده از Android TTS و انتظار تا پایان پخش
     */
    private suspend fun tryAndroidTTSAndWait(text: String): Boolean {
        if (!isAndroidTTSReady || androidTTS == null) {
            Log.e(TAG, "Android TTS not ready")
            return false
        }
        
        return suspendCancellableCoroutine { continuation ->
            try {
                val utteranceId = "utterance_${System.currentTimeMillis()}"
                
                androidTTS?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d(TAG, "Android TTS started")
                    }
                    
                    override fun onDone(utteranceId: String?) {
                        Log.d(TAG, "Android TTS completed")
                        if (continuation.isActive) {
                            continuation.resume(true)
                        }
                    }
                    
                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "Android TTS error")
                        if (continuation.isActive) {
                            continuation.resume(false)
                        }
                    }
                })
                
                val result = androidTTS?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                
                if (result != TextToSpeech.SUCCESS) {
                    Log.e(TAG, "Android TTS speak failed")
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Android TTS exception", e)
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
        }
    }
    
    /**
     * ذخیره فایل صوتی در کش
     */
    private suspend fun saveAudioToCache(responseBody: ResponseBody, fileName: String): File {
        return withContext(Dispatchers.IO) {
            val audioFile = File(audioCacheDir, fileName)
            FileOutputStream(audioFile).use { output ->
                responseBody.byteStream().use { input ->
                    input.copyTo(output)
                }
            }
            audioFile
        }
    }
    
    /**
     * پخش فایل صوتی و انتظار تا پایان پخش
     */
    private suspend fun playAudioFileAndWait(audioFile: File): Boolean {
        return suspendCancellableCoroutine { continuation ->
            try {
                // آزادسازی MediaPlayer قبلی
                mediaPlayer?.release()
                
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .build()
                    )
                    
                    setDataSource(audioFile.absolutePath)
                    
                    setOnCompletionListener {
                        Log.d(TAG, "Audio playback completed")
                        if (continuation.isActive) {
                            continuation.resume(true)
                        }
                    }
                    
                    setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                        if (continuation.isActive) {
                            continuation.resume(false)
                        }
                        true
                    }
                    
                    prepare()
                    start()
                }
                
                // لغو پخش در صورت لغو coroutine
                continuation.invokeOnCancellation {
                    mediaPlayer?.stop()
                    mediaPlayer?.release()
                    mediaPlayer = null
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error playing audio file", e)
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
        }
    }
    
    /**
     * توقف TTS
     */
    fun stop() {
        try {
            androidTTS?.stop()
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TTS", e)
        }
    }
    
    /**
     * آزادسازی منابع
     */
    fun shutdown() {
        try {
            stop()
            androidTTS?.shutdown()
            androidTTS = null
            
            // پاک کردن کش
            audioCacheDir.listFiles()?.forEach { it.delete() }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down TTS", e)
        }
    }
}
