package com.persianai.assistant.utils

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.media.MediaPlayer
import java.io.File
import java.util.*
import com.persianai.assistant.services.HaaniyeManager
import com.persianai.assistant.config.RemoteAIConfigManager
import com.persianai.assistant.tts.GapGPTTTS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * کمک‌کننده برای تبدیل متن به گفتار فارسی
 * Online-first: tries online TTS providers first, then offline Haaniye, then Android TTS as final fallback
 */
class TTSHelper(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val prefsManager = PreferencesManager(context)
    private val remoteConfigManager = RemoteAIConfigManager.getInstance(context)
    private val gapgptTTS = GapGPTTTS(context)

    companion object {
        private const val TAG = "TTSHelper"
    }

    fun initialize(onReady: (() -> Unit)? = null) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale("fa", "IR"))
                
                if (result == TextToSpeech.LANG_MISSING_DATA || 
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "Persian language not supported on this device; disabling Android TTS")
                    // If Persian is unavailable, rely solely on Haaniye and skip Android TTS
                    tts?.shutdown()
                    tts = null
                    isInitialized = false
                    return@TextToSpeech
                }

                // تنظیمات صدا
                tts?.setPitch(1.0f)
                tts?.setSpeechRate(0.9f) // کمی آهسته‌تر برای وضوح بیشتر
                
                isInitialized = true
                Log.d(TAG, "TTS initialized successfully")
                onReady?.invoke()
            } else {
                Log.e(TAG, "TTS initialization failed")
            }
        }

        // Listener برای رویدادهای TTS
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "TTS started: $utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "TTS finished: $utteranceId")
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS error: $utteranceId")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "TTS error: $utteranceId, code: $errorCode")
            }
        })
    }

    /**
     * Online-first TTS: tries online providers based on remote config priority, then offline Haaniye, then Android TTS
     */
    suspend fun speakOnlineFirst(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH) = withContext(Dispatchers.IO) {
        if (!prefsManager.isTTSEnabled()) {
            Log.d(TAG, "TTS is disabled")
            return@withContext
        }

        val cleanText = cleanTextForTTS(text)
        if (cleanText.isBlank()) {
            Log.d(TAG, "Empty text after cleaning")
            return@withContext
        }

        val ttsPriority = remoteConfigManager.getTTSPriority()
        Log.d(TAG, "TTS priority from remote config: $ttsPriority")

        // Try online TTS providers based on remote config priority
        for (provider in ttsPriority) {
            when (provider.lowercase()) {
                "gapgpt" -> {
                    try {
                        Log.d(TAG, "🎤 تلاش برای TTS با GapGPT gpt-4o-mini-tts...")
                        val audioFile = gapgptTTS.synthesizeSpeech(cleanText)
                        if (audioFile != null && audioFile.exists()) {
                            // پخش فایل صوتی
                            playAudioFile(audioFile)
                            Log.d(TAG, "✅ TTS با موفقیت از GapGPT اجرا شد")
                            return@withContext
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "GapGPT TTS failed: ${e.message}")
                    }
                }
                "liara" -> {
                    try {
                        Log.d(TAG, "🎤 تلاش برای TTS با Liara...")
                        // TODO: Implement Liara TTS call
                        Log.d(TAG, "Liara TTS not yet implemented, skipping")
                    } catch (e: Exception) {
                        Log.w(TAG, "Liara TTS failed: ${e.message}")
                    }
                }
                "openai" -> {
                    try {
                        Log.d(TAG, "🎤 تلاش برای TTS با OpenAI...")
                        // TODO: Implement OpenAI TTS call
                        Log.d(TAG, "OpenAI TTS not yet implemented, skipping")
                    } catch (e: Exception) {
                        Log.w(TAG, "OpenAI TTS failed: ${e.message}")
                    }
                }
                // Skip "haaniye" and "android" here; they will be tried as fallbacks
            }
        }

        // Fallback 1: Haaniye (offline)
        try {
            val handled = HaaniyeManager.speak(context, cleanText)
            if (handled) {
                Log.d(TAG, "TTS via Haaniye (offline)")
                return@withContext
            }
        } catch (e: Exception) {
            Log.w(TAG, "Haaniye TTS failed: ${e.message}")
        }

        // Fallback 2: Android TTS (if initialized)
        if (isInitialized && tts != null) {
            Log.d(TAG, "TTS via Android TTS (final fallback)")
            runOnUiThread {
                tts?.speak(cleanText, queueMode, null, "tts_${System.currentTimeMillis()}")
            }
        } else {
            Log.w(TAG, "No TTS provider available")
        }
    }

    /**
     * Legacy speak method (maintains compatibility)
     */
    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        if (!prefsManager.isTTSEnabled()) {
            Log.d(TAG, "TTS is disabled")
            return
        }

        if (!isInitialized) {
            Log.w(TAG, "TTS not initialized yet")
            return
        }

        val cleanText = cleanTextForTTS(text)
        if (cleanText.isBlank()) {
            Log.d(TAG, "Empty text after cleaning")
            return
        }

        // Previously: offline-first Haaniye -> Android TTS
        // Now: try Haaniye first for compatibility, then Android TTS
        try {
            val handled = HaaniyeManager.speak(context, cleanText)
            if (handled) return
        } catch (e: Exception) {
            Log.w(TAG, "Haaniye TTS failed: ${e.message}")
        }

        Log.d(TAG, "Speaking (Android TTS): $cleanText")
        tts?.speak(cleanText, queueMode, null, "tts_${System.currentTimeMillis()}")
    }
    
    /**
     * پخش فایل صوتی MP3 از GapGPT TTS
     */
    private fun playAudioFile(audioFile: File) {
        try {
            val mediaPlayer = MediaPlayer().apply {
                setDataSource(audioFile.absolutePath)
                prepare()
                setOnCompletionListener {
                    release()
                    // پاک کردن فایل بعد از پخش
                    audioFile.delete()
                }
                setOnErrorListener { _, _, _ ->
                    release()
                    audioFile.delete()
                    true
                }
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطا در پخش فایل صوتی", e)
            audioFile.delete()
        }
    }
    
    /**
     * پاک کردن منابع
     */
    fun cleanup() {
        gapgptTTS.cleanupOldAudioFiles()
    }

    private fun runOnUiThread(action: () -> Unit) {
        (context as? android.app.Activity)?.runOnUiThread(action) ?: run {
            // If context is not an Activity, use Handler with main looper
            android.os.Handler(android.os.Looper.getMainLooper()).post(action)
        }
    }

    /**
     * توقف اعلام فعلی
     */
    fun stop() {
        if (isInitialized) {
            tts?.stop()
        }
    }

    /**
     * آزاد کردن منابع
     */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        isInitialized = false
        Log.d(TAG, "TTS shutdown")
    }

    /**
     * پاکسازی متن برای TTS
     */
    private fun cleanTextForTTS(text: String): String {
        return text
            // حذف emoji
            .replace(Regex("[\\p{So}\\p{Sk}]"), "")
            // حذف لینک‌ها
            .replace(Regex("https?://\\S+"), "")
            // حذف کاراکترهای خاص اضافی
            .replace(Regex("[📱📋✅❌⚠️🔴💬📞🌐⚙️⚡]"), "")
            // حذف خطوط خالی اضافی
            .replace(Regex("\\n{2,}"), "\n")
            // حذف فاصله‌های اضافی
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * چک کردن در دسترس بودن
     */
    fun isAvailable(): Boolean {
        return isInitialized && tts != null
    }

    /**
     * تنظیم سرعت گفتار
     */
    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate)
    }

    /**
     * تنظیم pitch صدا
     */
    fun setPitch(pitch: Float) {
        tts?.setPitch(pitch)
    }
}
